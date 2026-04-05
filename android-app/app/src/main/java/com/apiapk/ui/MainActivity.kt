package com.apiapk.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.apiapk.R
import com.apiapk.model.AIConversation
import com.apiapk.model.ApiConfig
import com.apiapk.model.ConversationStore
import com.apiapk.model.StreamDelta
import com.apiapk.model.StreamEventBus
import com.apiapk.service.AICaptureService
import com.apiapk.service.ApiServerService
import com.apiapk.service.BackgroundMonitorService
import com.apiapk.util.AdbHelper

/**
 * 主Activity v5 - 修复无障碍检测和循环触发问题。
 *
 * v5 修复：
 *   1. isAccessibilityServiceEnabled() 同时匹配短格式和长格式组件名
 *   2. 用 skipToggleListener 标志防止初始化时循环触发
 *   3. 去掉 updateUI() 里重复的 setOnClickListener 和弹窗逻辑
 *   4. 确保 onResume 时不会误触发自动最小化
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_NAME = "apiapk_prefs"
        private const val PREF_ACCESSIBILITY_GRANTED = "accessibility_granted"

        // 无障碍服务组件 - 多种格式都检查（兼容不同Android版本存储方式）
        private const val SERVICE_CLASS = "com.apiapk.service.AICaptureService"
    }

    private lateinit var store: ConversationStore
    private lateinit var config: ApiConfig
    private val handler = Handler(Looper.getMainLooper())
    private val adbHelper = AdbHelper()

    /** 防止代码设 isChecked 时触发 listener 的标志 */
    private var skipToggleListener = false

    // 测试状态
    private var testAppType: AIConversation.AppType = AIConversation.AppType.DEEPSEEK
    private var testDialog: AlertDialog? = null
    private var testPhase = 0
    private var preTestMsgCount = 0
    private var streamSubscriber: StreamEventBus.StreamSubscriber? = null
    private var accumulatedResponse = StringBuilder()

    private lateinit var tvStatus: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvCaptureStatus: TextView
    private lateinit var tvBgStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnToggleServer: Button
    private lateinit var btnToggleCapture: ToggleButton
    private lateinit var btnSettings: Button
    private lateinit var btnViewLogs: Button
    private lateinit var btnTestConnection: Button
    private lateinit var btnBatteryOpt: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "部分权限被拒绝", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = ConversationStore(this)
        config = store.loadConfig()

        initViews()
        requestRequiredPermissions()
        requestBatteryOptimization()

        // 初始化UI（不会触发listener因为 skipToggleListener = true）
        refreshStatusUI()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvServerUrl = findViewById(R.id.tv_server_url)
        tvCaptureStatus = findViewById(R.id.tv_capture_status)
        tvBgStatus = findViewById(R.id.tv_bg_status)
        tvStats = findViewById(R.id.tv_stats)
        btnToggleServer = findViewById(R.id.btn_toggle_server)
        btnToggleCapture = findViewById(R.id.btn_toggle_capture)
        btnSettings = findViewById(R.id.btn_settings)
        btnViewLogs = findViewById(R.id.btn_view_logs)
        btnTestConnection = findViewById(R.id.btn_test_connection)
        btnBatteryOpt = findViewById(R.id.btn_battery_opt)

        btnToggleServer.setOnClickListener {
            if (ApiServerService.isRunning) {
                stopServer()
            } else {
                startServer()
                handler.postDelayed({ refreshStatusUI() }, 2000)
            }
        }

        // ★ 核心按钮：唯一的捕获开关
        btnToggleCapture.setOnCheckedChangeListener { _, isChecked ->
            // 如果是代码设置的状态（非用户点击），跳过
            if (skipToggleListener) return@setOnCheckedChangeListener

            if (isChecked) {
                handleStartCapture()
            } else {
                handleStopCapture()
            }
        }

        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnViewLogs.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        btnTestConnection.setOnClickListener { testAndRun() }
        btnBatteryOpt.setOnClickListener { openBatteryOptimizationSettings() }
    }

    // ===================== 核心：开始/停止捕获 =====================

    /**
     * 用户点击"开始捕获"的入口。
     * 统一入口，只从这里弹对话框，确保不会弹两个。
     */
    private fun handleStartCapture() {
        val enabled = checkAccessibilityServiceEnabled()

        Log.d(TAG, "handleStartCapture: accessibility enabled = $enabled")

        if (!enabled) {
            // 无障碍未开启 → 把按钮弹回去，弹一个对话框
            skipToggleListener = true
            btnToggleCapture.isChecked = false
            skipToggleListener = false

            showAccessibilityNeededDialog()
            return
        }

        // 无障碍OK → 开启捕获
        doStartCapture()
    }

    /**
     * 实际执行开始捕获。
     */
    private fun doStartCapture() {
        AICaptureService.setCaptureEnabled(true)
        AICaptureService.setConversationStore(store)

        // 记住已授权过
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ACCESSIBILITY_GRANTED, true)
            .apply()

        tvCaptureStatus.text = "🟢 AI捕获已开启"

        // 确保服务器和后台监控都在运行
        if (!ApiServerService.isRunning) {
            startServer()
        }

        // 通知后台服务开始自动检测和启动APP
        BackgroundMonitorService.startAutoCapture(this)

        // 最小化APP
        Toast.makeText(this, "✅ 捕获已开启，正在后台工作...", Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            moveTaskToBack(true)
        }, 300)
    }

    /**
     * 停止捕获。
     */
    private fun handleStopCapture() {
        AICaptureService.setCaptureEnabled(false)
        BackgroundMonitorService.stopAutoCapture()
        tvCaptureStatus.text = "🔴 AI捕获已关闭"
        Toast.makeText(this, "AI捕获已关闭", Toast.LENGTH_SHORT).show()
    }

    // ===================== 无障碍服务检测（修复版）=====================

    /**
     * 检查无障碍服务是否已启用。
     *
     * 修复：Android 系统在 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 中存储的格式可能是：
     *   - "com.apiapk/com.apiapk.service.AICaptureService"  (完整组件名，多数设备)
     *   - "com.apiapk/.service.AICaptureService"            (短格式，部分设备)
     *   - "com.apiapk/com.apiapk.service.AICaptureService:com.apiapk" (带user，多用户设备)
     *
     * 所以必须同时检查多种格式。
     */
    private fun checkAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        Log.d(TAG, "ENABLED_ACCESSIBILITY_SERVICES = $enabledServices")

        // 检查多种可能的格式
        val patterns = listOf(
            "$packageName/$SERVICE_CLASS",           // 完整: com.apiapk/com.apiapk.service.AICaptureService
            "$packageName/.service.AICaptureService",  // 短格式: com.apiapk/.service.AICaptureService
            SERVICE_CLASS,                             // 只包含类名
            "AICaptureService"                         // 只包含简单类名
        )

        for (pattern in patterns) {
            if (enabledServices.contains(pattern)) {
                Log.d(TAG, "Accessibility service ENABLED (matched: $pattern)")
                return true
            }
        }

        Log.d(TAG, "Accessibility service NOT enabled (no pattern matched)")
        return false
    }

    /**
     * 显示需要无障碍权限的对话框（唯一入口，不会重复弹）。
     */
    private fun showAccessibilityNeededDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要无障碍权限")
            .setMessage(
                "ApiAPK 需要无障碍服务权限来捕获AI应用的对话内容。\n\n" +
                "操作步骤：\n" +
                "1. 点击下方\"去设置\"\n" +
                "2. 在列表中找到 \"ApiAPK\"\n" +
                "3. 点击开启\n" +
                "4. 按返回键回到本APP\n\n" +
                "💡 只需设置一次，以后不会再提示。"
            )
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===================== 权限和服务器 =====================

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try { startActivity(intent) } catch (_: Exception) {}
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "✅ 已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("电池优化白名单")
                    .setMessage("将ApiAPK加入电池优化白名单，防止系统在后台杀掉服务。\n\n操作：设置 → 电池 → 电池优化 → 找到ApiAPK → 选择\"不优化\"")
                    .setPositiveButton("去设置") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun startServer() {
        AICaptureService.setConversationStore(store)
        val intent = Intent(this, ApiServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "服务器+后台监控已启动 端口: ${config.serverPort}", Toast.LENGTH_SHORT).show()
    }

    private fun stopServer() {
        val intent = Intent(this, ApiServerService::class.java).apply { action = "STOP" }
        startService(intent)
        Toast.makeText(this, "服务器已停止", Toast.LENGTH_SHORT).show()
    }

    // ===================== 手动测试 =====================

    private fun testAndRun() {
        btnTestConnection.isEnabled = false
        btnTestConnection.text = "测试中..."

        Thread {
            var reachable = false
            var statusCode = -1
            try {
                val url = "http://127.0.0.1:${config.serverPort}/api/status"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = false
                statusCode = conn.responseCode
                conn.disconnect()
                reachable = (statusCode == 200)
            } catch (_: Exception) {}

            runOnUiThread {
                btnTestConnection.isEnabled = true
                btnTestConnection.text = "🧪 测试并发送你好"

                if (reachable) {
                    Toast.makeText(this, "✅ 服务器连接成功 (HTTP $statusCode)", Toast.LENGTH_SHORT).show()
                    runEndToEndTest()
                } else {
                    Toast.makeText(this, "❌ 连接失败，请先启动服务器", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runEndToEndTest() {
        if (!checkAccessibilityServiceEnabled()) {
            Toast.makeText(this, "⚠️ 请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            showAccessibilityNeededDialog()
            return
        }

        val appNames = arrayOf("DeepSeek", "豆包", "小爱同学")
        val appTypes = arrayOf(
            AIConversation.AppType.DEEPSEEK,
            AIConversation.AppType.DOUBAO,
            AIConversation.AppType.XIAOAI
        )

        AlertDialog.Builder(this)
            .setTitle("🧪 端到端测试")
            .setMessage("服务器连接正常 ✅\n\n选择要测试的AI应用，将自动发送\"你好\"并等待回复：")
            .setItems(appNames) { _, which ->
                testAppType = appTypes[which]
                executeTest(appNames[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeTest(appDisplayName: String) {
        testPhase = 1
        accumulatedResponse.clear()
        preTestMsgCount = store.getConversationsByApp(testAppType).sumOf { it.messages.size }

        val dialogView = layoutInflater.inflate(R.layout.dialog_test_progress, null)
        val tvTestTitle = dialogView.findViewById<TextView>(R.id.tv_test_title)
        val tvTestStep = dialogView.findViewById<TextView>(R.id.tv_test_step)
        val tvTestResponse = dialogView.findViewById<TextView>(R.id.tv_test_response)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progress_test)

        tvTestTitle.text = "🧪 测试 ${appDisplayName}"
        tvTestStep.text = "① 检查API服务器..."
        tvTestResponse.text = ""
        progressBar.visibility = View.VISIBLE
        tvTestResponse.visibility = View.GONE

        testDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                cleanupTest()
                testDialog = null
            }
            .show()

        subscribeToStreamEvents(tvTestResponse, progressBar)

        Thread {
            try {
                runOnUiThread { tvTestStep.text = "① 检查API服务器..." }
                val url = "http://127.0.0.1:${config.serverPort}/api/status"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val code = conn.responseCode
                conn.disconnect()

                if (code != 200) {
                    runOnUiThread {
                        tvTestStep.text = "❌ API服务器无响应 (HTTP $code)"
                        progressBar.visibility = View.GONE
                    }
                    return@Thread
                }

                runOnUiThread { tvTestStep.text = "② 启动 ${appDisplayName} 并发送\"你好\"..." }
                Thread.sleep(500)

                val sent = adbHelper.sendViaAdb(testAppType, "你好")
                if (!sent) {
                    runOnUiThread { tvTestStep.text = "⚠️ ADB发送失败，尝试通过API发送..." }
                }

                runOnUiThread { tvTestStep.text = "③ 等待AI回复（最长30秒）..." }

                val startTime = System.currentTimeMillis()
                val timeout = 30000L

                while (System.currentTimeMillis() - startTime < timeout) {
                    if (accumulatedResponse.isNotEmpty()) {
                        break
                    }

                    val currentMsgCount = store.getConversationsByApp(testAppType).sumOf { it.messages.size }
                    if (currentMsgCount > preTestMsgCount) {
                        val latestConv = store.getLatestConversation(testAppType)
                        if (latestConv != null) {
                            val lastMsg = latestConv.messages.lastOrNull()
                            if (lastMsg != null && lastMsg.role == "assistant" && lastMsg.content.isNotBlank()) {
                                accumulatedResponse.append(lastMsg.content)
                                break
                            }
                        }
                    }

                    Thread.sleep(800)
                }

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    tvTestResponse.visibility = View.VISIBLE

                    val response = accumulatedResponse.toString().trim()
                    if (response.isNotEmpty()) {
                        tvTestStep.text = "✅ 捕获成功！"
                        tvTestResponse.text = "📝 AI回复：\n\n${response}"

                        handler.postDelayed({
                            testDialog?.dismiss()
                            testDialog = null
                            cleanupTest()
                            refreshStatusUI()
                            Toast.makeText(this, "✅ 测试成功！${appDisplayName} 回复已捕获", Toast.LENGTH_LONG).show()
                        }, 3000)
                    } else {
                        tvTestStep.text = "⏱️ 等待超时"
                        tvTestResponse.text = "未能捕获到AI回复。可能原因：\n\n" +
                                "• ${appDisplayName} 未安装\n" +
                                "• ${appDisplayName} 未在前台运行\n" +
                                "• 无障碍服务未正确启用\n" +
                                "• AI应用加载较慢\n\n" +
                                "请确保目标APP在前台运行后重试。"
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    tvTestStep.text = "❌ 测试出错"
                    progressBar.visibility = View.GONE
                    tvTestResponse.visibility = View.VISIBLE
                    tvTestResponse.text = "错误: ${e.message}"
                }
            }
        }.start()
    }

    private fun subscribeToStreamEvents(
        tvResponse: TextView,
        progressBar: ProgressBar
    ) {
        streamSubscriber?.let { StreamEventBus.unsubscribe(it) }

        val appId = testAppType.identifier
        streamSubscriber = object : StreamEventBus.StreamSubscriber {
            override fun onDelta(delta: StreamDelta) {
                if (delta.app != appId) return

                runOnUiThread {
                    if (accumulatedResponse.isEmpty()) {
                        tvResponse.visibility = View.VISIBLE
                    }

                    if (delta.delta.isNotEmpty()) {
                        accumulatedResponse.append(delta.delta)
                        val display = accumulatedResponse.toString().take(500)
                        tvResponse.text = "📝 AI回复（实时）：\n\n$display"
                        if (accumulatedResponse.length > 500) {
                            tvResponse.append("...")
                        }
                    }

                    if (delta.finishReason != null) {
                        progressBar.visibility = View.GONE
                    }
                }
            }

            override fun isActive(): Boolean = testPhase > 0
        }

        StreamEventBus.subscribe(streamSubscriber!!)
    }

    private fun cleanupTest() {
        testPhase = 0
        streamSubscriber?.let { StreamEventBus.unsubscribe(it) }
        streamSubscriber = null
        accumulatedResponse.clear()
        handler.removeCallbacksAndMessages(null)
    }

    // ===================== UI 刷新（不触发listener）=====================

    /**
     * 纯粹刷新状态文本，不改变 ToggleButton 的状态，不弹窗。
     * 这样 onResume 时不会误触发自动最小化。
     */
    private fun refreshStatusUI() {
        val isServer = ApiServerService.isRunning
        val isBg = BackgroundMonitorService.isRunning
        val isAccessible = checkAccessibilityServiceEnabled()

        tvStatus.text = if (isServer) "🟢 服务器运行中" else "🔴 服务器未启动"
        tvServerUrl.text = "http://localhost:${config.serverPort}"

        val localIp = getLocalIpAddress()
        if (localIp != null) {
            tvServerUrl.text = "http://$localIp:${config.serverPort}\nhttp://localhost:${config.serverPort}"
        }

        btnToggleServer.text = if (isServer) "停止服务器" else "启动服务器"
        tvBgStatus.text = if (isBg) "🟢 后台监控运行中" else "🔴 后台监控未启动"

        val stats = store.getStats()
        tvStats.text = "会话: ${stats["totalConversations"] ?: 0} | 消息: ${stats["totalMessages"] ?: 0}"

        // ★ 关键：用 skipToggleListener 防止循环触发
        if (isAccessible) {
            tvCaptureStatus.text = "🟢 无障碍服务已启用"
            skipToggleListener = true
            btnToggleCapture.isChecked = true
            skipToggleListener = false
        } else {
            tvCaptureStatus.text = "🔴 无障碍服务未启用 (点击开启)"
            skipToggleListener = true
            btnToggleCapture.isChecked = false
            skipToggleListener = false
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.name.equals("wlan0", ignoreCase = true)) {
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    /**
     * onResume 时只刷新状态文字，不自动最小化。
     */
    override fun onResume() {
        super.onResume()
        refreshStatusUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupTest()
    }
}
