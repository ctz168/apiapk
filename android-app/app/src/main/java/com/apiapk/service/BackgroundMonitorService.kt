package com.apiapk.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.apiapk.model.ConversationStore
import com.apiapk.model.StreamEventBus
import com.apiapk.service.AICaptureService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 后台监控服务 - 核心后台存活保障组件。
 *
 * 功能职责：
 *   1. 前台服务 + 常驻通知，防止被系统回收（stopWithTask=false 确保切到后台不被杀）
 *   2. 持有 WakeLock 防止CPU休眠
 *   3. 定时心跳检测：确认无障碍服务和API服务器都在运行，如果掉线则自动重启
 *   4. 管理StreamEventBus的生命周期
 *   5. 维护StreamEventBus引用，确保SSE推送不中断
 *
 * 为什么需要这个独立服务？
 *   - AccessibilityService 虽然是系统级服务，但Android 8+ 仍然可能被杀
 *   - ApiServerService 如果没有前台通知也可能在后台被回收
 *   - 此服务作为"守护进程"，确保整个链路不中断
 */
class BackgroundMonitorService : Service() {

    companion object {
        private const val TAG = "BgMonitorService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "apiapk_bg_monitor"
        private const val WAKELOCK_TAG = "ApiAPK:BackgroundMonitor"
        private const val HEARTBEAT_INTERVAL_MS = 10000L // 10秒心跳
        private const val STREAM_IDLE_TIMEOUT_MS = 30000L // 30秒无新delta则发送finish

        @Volatile
        var isRunning = false
            private set

        /** 流式输出：跟踪每个app最后一次看到assistant文本的时间（静态，供AICaptureService调用） */
        private val lastAssistantActivity = mutableMapOf<String, Long>()

        /** 记录某个app有新的assistant活动（由AICaptureService静态调用） */
        @JvmStatic
        fun recordAssistantActivity(app: String) {
            lastAssistantActivity[app] = System.currentTimeMillis()
        }
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var store: ConversationStore
    private var heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()

    private var idleCheckExecutor = Executors.newSingleThreadScheduledExecutor()

    override fun onCreate() {
        super.onCreate()
        store = ConversationStore(this)
        isRunning = true

        acquireWakeLock()
        createNotificationChannel()
        startForegroundNotification()

        Log.i(TAG, "Background Monitor Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        startHeartbeat()
        startStreamIdleChecker()
        return START_STICKY // 系统杀掉后会自动重启
    }

    /**
     * 获取WakeLock防止CPU休眠，确保后台网络和事件处理正常。
     * 使用PARTIAL_WAKE_LOCK级别，只保持CPU运行，不影响屏幕。
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            acquire(12 * 60 * 60 * 1000L) // 最长12小时，系统会自动释放
        }
        Log.i(TAG, "WakeLock acquired")
    }

    /**
     * 创建通知渠道（Android 8+ 必须）
     */
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

    /**
     * 启动前台通知 - 包含当前状态和快捷操作按钮。
     * 通知使用低优先级，不打扰用户。
     */
    private fun startForegroundNotification() {
        updateNotification("ApiAPK 后台运行中", "服务器: ${if (ApiServerService.isRunning) "🟢 运行中" else "🔴 未启动"}")
    }

    /**
     * 更新通知内容 - 心跳检测时定期刷新状态。
     */
    private fun updateNotification(title: String, text: String) {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true) // 不可滑动删除
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
     * 如果发现服务掉线，尝试自动重启。
     * 同时更新通知栏状态。
     */
    private fun startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate({
            try {
                val captureActive = AICaptureService.isServiceRunning()
                val serverActive = ApiServerService.isRunning
                val config = store.loadConfig()

                // 更新通知
                val captureStatus = if (captureActive) "🟢" else "🔴"
                val serverStatus = if (serverActive) "🟢" else "🔴"
                val eventCount = StreamEventBus.getSubscriberCount()

                updateNotification(
                    "ApiAPK 后台运行中",
                    "捕获:$captureStatus 服务器:$serverStatus SSE连接:$eventCount"
                )

                // 自动恢复API服务器（如果配置了自动启动）
                if (!serverActive && config.autoStart) {
                    Log.w(TAG, "API server is down, attempting restart...")
                    val restartIntent = Intent(this, ApiServerService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(restartIntent)
                    } else {
                        startService(restartIntent)
                    }
                }

                Log.d(TAG, "Heartbeat: capture=$captureActive, server=$serverActive, sse=$eventCount")
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat error: ${e.message}")
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * 流式空闲检测 - 如果某个app的assistant输出超过30秒没有新delta，
     * 自动发送一个finish事件，标记当前流式输出结束。
     * 这确保了客户端不会永远等待一个永远不会结束的流。
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
                        // 超时，发送finish
                        Log.d(TAG, "Stream idle timeout for $app, sending finish")
                        // 获取当前累积文本
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
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }

        isRunning = false
        super.onDestroy()
    }
}
