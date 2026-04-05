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
 * 主Activity v4 - 自动化捕获流程。
 *
 * 核心改进：
 *   1. 点击"开始捕获" → 检查无障碍权限 → 最小化APP → 后台自动打开DeepSeek/豆包
 *   2. 记住无障碍权限状态，首次才提示，以后直接最小化
 *   3. AICaptureService 首次检测到APP时自动发送"你好"
 *   4. 通知栏实时显示捕获状态
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREF_NAME = "apiapk_prefs"
        private const val PREF_ACCESSIBILITY_GRANTED = "accessibility_granted"
        private const val PREF_CAPTURE_STARTED_BEFORE = "capture_started_before"
    }

    private lateinit var store: ConversationStore
    private lateinit var config: ApiConfig
    private val handler = Handler(Looper.getMainLooper())
    private val adbHelper = AdbHelper()

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
        updateUI()
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
                handler.postDelayed({ updateUI() }, 2000)
            }
        }

        // 核心改动：点击"开始捕获" → 自动最小化
        btnToggleCapture.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startCaptureAndMinimize()
            } else {
                stopCapture()
            }
        }

        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnViewLogs.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        btnTestConnection.setOnClickListener { testAndRun() }
        btnBatteryOpt.setOnClickListener { openBatteryOptimizationSettings() }

        if (!isAccessibilityServiceEnabled()) {
            btnToggleCapture.isChecked = false
            tvCaptureStatus.text = "🔴 无障碍服务未启用"
        }
    }

    // ===================== 核心流程：开始捕获并最小化 =====================

    /**
     * 开始捕获流程：
     *   1. 检查无障碍权限
     *   2. 首次无权限 → 提示去开启
     *   3. 已有权限 → 直接开启捕获 → 最小化APP
     *   4. 后台服务自动打开DeepSeek和豆包
     */
    private fun startCaptureAndMinimize() {
        if (!isAccessibilityServiceEnabled()) {
            // 无障碍权限未开启
            val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val hadPermissionBefore = prefs.getBoolean(PREF_ACCESSIBILITY_GRANTED, false)

            if (hadPermissionBefore) {
                // 之前开过但现在没开（可能被关闭了），直接最小化让用户知道需要重新开启
                btnToggleCapture.isChecked = false
                Toast.makeText(this, "⚠️ 无障碍服务被关闭了，请重新开启", Toast.LENGTH_LONG).show()
                BackgroundMonitorService.showNotification(
                    this, "⚠️ 需要无障碍权限", "无障碍服务未启用，请手动开启"
                )
                openAccessibilitySettings()
                return
            }

            // 首次使用，提示用户去开启无障碍服务
            btnToggleCapture.isChecked = false
            showFirstTimeAccessibilityDialog()
            return
        }

        // 无障碍权限OK → 开启捕获
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
     * 首次提示无障碍权限对话框。
     */
    private fun showFirstTimeAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔑 首次使用 - 需要无障碍权限")
            .setMessage(
                "ApiAPK 需要无障碍服务权限来捕获AI应用的对话内容。\n\n" +
                "操作步骤：\n" +
                "1. 点击\"去设置\"\n" +
                "2. 找到 \"ApiAPK\" 并开启\n" +
                "3. 返回本APP，再次点击\"开始捕获\"\n\n" +
                "💡 开启后，以后使用就不再需要这个提示了。\n" +
                "💡 支持分屏运行豆包+DeepSeek，两个APP的对话都会被捕获。"
            )
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("稍后再说", null)
            .setCancelable(false)
            .show()
    }

    /**
     * 停止捕获。
     */
    private fun stopCapture() {
        AICaptureService.setCaptureEnabled(false)
        BackgroundMonitorService.stopAutoCapture()
        tvCaptureStatus.text = "🔴 AI捕获已关闭"
        Toast.makeText(this, "AI捕获已关闭", Toast.LENGTH_SHORT).show()
    }

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
        updateUI()
        Toast.makeText(this, "服务器+后台监控已启动 端口: ${config.serverPort}", Toast.LENGTH_SHORT).show()
    }

    private fun stopServer() {
        val intent = Intent(this, ApiServerService::class.java).apply { action = "STOP" }
        startService(intent)
        updateUI()
        Toast.makeText(this, "服务器已停止", Toast.LENGTH_SHORT).show()
    }

    // ===================== 手动测试（保留可选功能） =====================

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
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "⚠️ 请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            openAccessibilitySettings()
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

                var foundResponse = false
                val startTime = System.currentTimeMillis()
                val timeout = 30000L

                while (System.currentTimeMillis() - startTime < timeout) {
                    if (accumulatedResponse.isNotEmpty()) {
                        foundResponse = true
                        break
                    }

                    val currentMsgCount = store.getConversationsByApp(testAppType).sumOf { it.messages.size }
                    if (currentMsgCount > preTestMsgCount) {
                        val latestConv = store.getLatestConversation(testAppType)
                        if (latestConv != null) {
                            val lastMsg = latestConv.messages.lastOrNull()
                            if (lastMsg != null && lastMsg.role == "assistant" && lastMsg.content.isNotBlank()) {
                                accumulatedResponse.append(lastMsg.content)
                                foundResponse = true
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
                            updateUI()
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

    // ===================== UI 更新 =====================

    private fun updateUI() {
        val isServer = ApiServerService.isRunning
        val isBg = BackgroundMonitorService.isRunning

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

        if (isAccessibilityServiceEnabled()) {
            tvCaptureStatus.text = "🟢 无障碍服务已启用"
            btnToggleCapture.isChecked = true
        } else {
            tvCaptureStatus.text = "🔴 无障碍服务未启用 (点击开启)"
            btnToggleCapture.isChecked = false
            btnToggleCapture.setOnClickListener { openAccessibilitySettings() }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/.service.AICaptureService"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(service) == true
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("开启无障碍服务")
            .setMessage("ApiAPK需要无障碍服务权限来捕获AI应用的对话内容。\n\n请在设置中找到\"ApiAPK\"并开启。\n\n💡 提示：开启后可以分屏运行豆包+DeepSeek，两个APP的对话都会被捕获。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("取消", null)
            .show()
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

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupTest()
    }
}
