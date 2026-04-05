package com.apiapk.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.apiapk.model.AIConversation
import com.apiapk.model.ConversationStore
import com.apiapk.model.StreamEventBus
import com.apiapk.util.AdbHelper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 后台监控服务 v2 - 自动化流程 + 后台存活保障。
 *
 * v2 新增功能：
 *   - startAutoCapture(): 启动后自动打开DeepSeek和豆包
 *   - 通知栏实时显示捕获状态
 *   - 自动检测APP是否安装并提示
 *
 * 功能职责：
 *   1. 前台服务 + 常驻通知，防止被系统回收
 *   2. 持有 WakeLock 防止CPU休眠
 *   3. 定时心跳检测：确认无障碍服务和API服务器都在运行
 *   4. 管理StreamEventBus的生命周期
 *   5. 自动打开目标AI应用进行捕获
 */
class BackgroundMonitorService : Service() {

    companion object {
        private const val TAG = "BgMonitorService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "apiapk_bg_monitor"
        private const val WAKELOCK_TAG = "ApiAPK:BackgroundMonitor"
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val STREAM_IDLE_TIMEOUT_MS = 30000L

        @Volatile
        var isRunning = false
            private set

        /** 自动捕获是否激活 */
        @Volatile
        private var autoCaptureActive = false

        /** 各APP的安装/启动状态 */
        private val appLaunchStatus = mutableMapOf<String, String>() // "deepseek" -> "launched" / "not_installed" / "failed"

        /** 流式输出：跟踪每个app最后一次看到assistant文本的时间 */
        private val lastAssistantActivity = mutableMapOf<String, Long>()

        @JvmStatic
        fun recordAssistantActivity(app: String) {
            lastAssistantActivity[app] = System.currentTimeMillis()
        }

        /**
         * 启动自动捕获流程。
         * 由MainActivity在用户点击"开始捕获"后调用。
         * 该方法会启动后台监控服务（如果未运行），然后自动打开目标APP。
         */
        @JvmStatic
        fun startAutoCapture(context: Context) {
            autoCaptureActive = true
            appLaunchStatus.clear()

            // 确保后台监控服务在运行
            val intent = Intent(context, BackgroundMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.i(TAG, "Auto capture activated")
        }

        /**
         * 停止自动捕获。
         */
        @JvmStatic
        fun stopAutoCapture() {
            autoCaptureActive = false
            Log.i(TAG, "Auto capture deactivated")
        }

        /**
         * 显示一个一次性通知（从其他组件调用）。
         */
        @JvmStatic
        fun showNotification(context: Context, title: String, text: String) {
            try {
                val channelId = "apiapk_capture_status"
                val notificationId = 2002

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        channelId,
                        "ApiAPK 捕获状态",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = "AI应用捕获状态通知"
                        setShowBadge(true)
                    }
                    val nm = context.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(channel)
                }

                val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, channelId)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setAutoCancel(true)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setAutoCancel(true)
                        .setPriority(Notification.PRIORITY_DEFAULT)
                        .build()
                }

                val nm = context.getSystemService(NotificationManager::class.java)
                nm.notify(notificationId, notification)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show notification: ${e.message}")
            }
        }
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var store: ConversationStore
    private var heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    private var idleCheckExecutor = Executors.newSingleThreadScheduledExecutor()
    private var appLauncherExecutor = Executors.newSingleThreadScheduledExecutor()
    private var adbHelper: AdbHelper? = null

    override fun onCreate() {
        super.onCreate()
        store = ConversationStore(this)
        adbHelper = AdbHelper()
        isRunning = true

        acquireWakeLock()
        createNotificationChannel()
        startForegroundNotification()

        Log.i(TAG, "Background Monitor Service v2 created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        startHeartbeat()
        startStreamIdleChecker()

        // 如果自动捕获激活，延迟后启动APP
        if (autoCaptureActive) {
            scheduleAutoLaunchApps()
        }

        return START_STICKY
    }

    /**
     * 自动打开目标AI应用。
     * 延迟2秒后开始，分别打开DeepSeek和豆包。
     * 打开策略：先打开第一个APP，等5秒再打开第二个（避免冲突）。
     */
    private fun scheduleAutoLaunchApps() {
        appLauncherExecutor.schedule({
            try {
                val helper = adbHelper ?: AdbHelper()

                // 检查并启动DeepSeek
                launchTargetApp(helper, AIConversation.AppType.DEEPSEEK)

                // 5秒后启动豆包
                appLauncherExecutor.schedule({
                    launchTargetApp(helper, AIConversation.AppType.DOUBAO)
                }, 5000, TimeUnit.MILLISECONDS)

            } catch (e: Exception) {
                Log.e(TAG, "Auto launch error: ${e.message}")
                updateNotification("⚠️ 自动启动失败", "错误: ${e.message}")
            }
        }, 2000, TimeUnit.MILLISECONDS)
    }

    /**
     * 启动单个目标APP。
     */
    private fun launchTargetApp(helper: AdbHelper, appType: AIConversation.AppType) {
        val appId = appType.identifier

        // 检查是否已安装
        val installed = helper.isAppInstalled(appType.packageName)
        if (!installed) {
            appLaunchStatus[appId] = "not_installed"
            Log.w(TAG, "[$appId] ${appType.displayName} 未安装")
            showNotification(
                this,
                "⚠️ ${appType.displayName} 未安装",
                "请先安装 ${appType.displayName}，然后再尝试捕获"
            )
            return
        }

        Log.i(TAG, "[$appId] 正在启动 ${appType.displayName}...")
        updateNotification("🚀 正在启动 ${appType.displayName}...", "请稍候")

        // 使用monkey启动APP（比am start更可靠）
        val result = helper.executeShell(
            "monkey -p ${appType.packageName} -c android.intent.category.LAUNCHER 1"
        )

        if (result.success || result.stdout.contains("Events injected")) {
            appLaunchStatus[appId] = "launched"
            Log.i(TAG, "[$appId] ${appType.displayName} 启动成功")
            showNotification(
                this,
                "✅ ${appType.displayName} 已启动",
                "正在等待检测窗口内容..."
            )
        } else {
            // 降级方案
            val fallbackResult = helper.executeShell("am start -n ${appType.packageName}/.MainActivity")
            if (fallbackResult.success) {
                appLaunchStatus[appId] = "launched"
                Log.i(TAG, "[$appId] ${appType.displayName} 启动成功 (fallback)")
                showNotification(
                    this,
                    "✅ ${appType.displayName} 已启动",
                    "正在等待检测窗口内容..."
                )
            } else {
                appLaunchStatus[appId] = "failed"
                Log.e(TAG, "[$appId] ${appType.displayName} 启动失败")
                showNotification(
                    this,
                    "❌ ${appType.displayName} 启动失败",
                    "请手动打开 ${appType.displayName}"
                )
            }
        }
    }

    // ========== 后台存活机制 ==========

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            acquire(12 * 60 * 60 * 1000L)
        }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ApiAPK 后台监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持AI捕获和API服务持续运行"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        updateNotification("ApiAPK 后台运行中", "服务器: ${if (ApiServerService.isRunning) "🟢 运行中" else "🔴 未启动"}")
    }

    /**
     * 更新通知内容 - 包含详细的捕获状态。
     */
    private fun updateNotification(title: String, text: String) {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .setSound(null, null)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 心跳检测 - 每10秒检查一次关键服务状态。
     * 同时更新通知栏为详细的状态信息。
     */
    private fun startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate({
            try {
                val captureActive = AICaptureService.isServiceRunning()
                val serverActive = ApiServerService.isRunning
                val config = store.loadConfig()
                val eventCount = StreamEventBus.getSubscriberCount()

                // 构建详细的通知内容
                val captureStatus = if (captureActive) "🟢" else "🔴"
                val serverStatus = if (serverActive) "🟢" else "🔴"

                // APP启动状态
                val appStatusBuilder = StringBuilder()
                for (appType in listOf(AIConversation.AppType.DEEPSEEK, AIConversation.AppType.DOUBAO, AIConversation.AppType.XIAOAI)) {
                    val appId = appType.identifier
                    val status = appLaunchStatus[appId]
                    val statusEmoji = when (status) {
                        "launched" -> "✅"
                        "not_installed" -> "❌"
                        "failed" -> "⚠️"
                        else -> "⏳"
                    }
                    appStatusBuilder.append("${appType.displayName}$statusEmoji ")
                }

                val subTitle = if (autoCaptureActive) {
                    "捕获: $captureStatus | 服务器: $serverStatus | SSE: $eventCount"
                } else {
                    "捕获: $captureStatus | 服务器: $serverStatus | SSE: $eventCount"
                }

                updateNotification(
                    "ApiAPK 运行中  $appStatusBuilder",
                    subTitle
                )

                // 自动恢复API服务器
                if (!serverActive && config.autoStart) {
                    Log.w(TAG, "API server is down, attempting restart...")
                    val restartIntent = Intent(this, ApiServerService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(restartIntent)
                    } else {
                        startService(restartIntent)
                    }
                }

                Log.d(TAG, "Heartbeat: capture=$captureActive, server=$serverActive, sse=$eventCount, autoCapture=$autoCaptureActive")
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e.message}")
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * 流式空闲检测 - 超时发送finish事件。
     */
    private fun startStreamIdleChecker() {
        idleCheckExecutor.scheduleAtFixedRate({
            try {
                val now = System.currentTimeMillis()
                val config = store.loadConfig()
                if (!config.streamEnabled) return@scheduleAtFixedRate

                val apps = listOf("deepseek", "doubao", "xiaoai")
                for (app in apps) {
                    val lastActivity = lastAssistantActivity[app] ?: continue
                    if (now - lastActivity > STREAM_IDLE_TIMEOUT_MS) {
                        Log.d(TAG, "Stream idle timeout for $app, sending finish")
                        val recentEvents = StreamEventBus.getRecentEvents(filterApp = app)
                        val lastAccumulated = recentEvents.lastOrNull()?.accumulated ?: ""

                        StreamEventBus.pushDelta(
                            com.apiapk.model.StreamDelta.finish(
                                id = "idle_${app}_${now}",
                                app = app,
                                role = "assistant",
                                accumulated = lastAccumulated,
                                reason = com.apiapk.model.StreamDelta.FINISH_STOP
                            )
                        )
                        lastAssistantActivity.remove(app)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Idle checker error: ${e.message}")
            }
        }, STREAM_IDLE_TIMEOUT_MS, STREAM_IDLE_TIMEOUT_MS / 3, TimeUnit.MILLISECONDS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Background Monitor Service destroying")

        try {
            heartbeatExecutor.shutdown()
            idleCheckExecutor.shutdown()
            appLauncherExecutor.shutdown()
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }

        autoCaptureActive = false
        isRunning = false
        super.onDestroy()
    }
}
