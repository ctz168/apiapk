package com.apiapk.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.apiapk.R
import com.apiapk.model.ApiConfig
import com.apiapk.model.ConversationStore
import com.apiapk.service.AICaptureService
import com.apiapk.service.ApiServerService
import com.apiapk.service.BackgroundMonitorService

/**
 * 主Activity v2 - 增加电池优化白名单、后台监控状态、分屏使用提示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: ConversationStore
    private lateinit var config: ApiConfig

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
            }
        }

        btnToggleCapture.setOnCheckedChangeListener { _, isChecked ->
            AICaptureService.setCaptureEnabled(isChecked)
            tvCaptureStatus.text = if (isChecked) "🟢 AI捕获已开启" else "🔴 AI捕获已关闭"
            Toast.makeText(this, if (isChecked) "AI捕获已开启" else "AI捕获已关闭", Toast.LENGTH_SHORT).show()
        }

        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnViewLogs.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        btnTestConnection.setOnClickListener { testServerConnection() }

        btnBatteryOpt.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        if (!isAccessibilityServiceEnabled()) {
            btnToggleCapture.isChecked = false
            tvCaptureStatus.text = "🔴 无障碍服务未启用"
        }
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

    /**
     * 请求加入电池优化白名单 - 防止系统杀掉后台服务。
     * 这是后台运行的关键步骤。
     */
    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // 某些设备可能不支持此intent
                }
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

    private fun testServerConnection() {
        Thread {
            try {
                val url = "http://localhost:${config.serverPort}/api/status"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 3000; conn.readTimeout = 3000
                val code = conn.responseCode
                runOnUiThread {
                    Toast.makeText(this, if (code == 200) "✅ 连接成功" else "⚠️ HTTP $code", Toast.LENGTH_SHORT).show()
                }
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "❌ ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

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

    override fun onResume() { super.onResume(); updateUI() }
}
