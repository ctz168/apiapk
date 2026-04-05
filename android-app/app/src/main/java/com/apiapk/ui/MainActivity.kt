package com.apiapk.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.apiapk.model.AIConversation
import com.apiapk.model.ApiConfig
import com.apiapk.model.ConversationStore
import com.apiapk.service.AICaptureService
import com.apiapk.service.ApiServerService

/**
 * 主Activity - 应用的入口界面，提供服务器控制、无障碍服务管理、配置查看和快捷操作。
 * 界面简洁明了，核心功能包括：启动/停止API服务器、开启/关闭AI捕获、查看连接地址。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: ConversationStore
    private lateinit var config: ApiConfig

    private lateinit var tvStatus: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvCaptureStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnToggleServer: Button
    private lateinit var btnToggleCapture: ToggleButton
    private lateinit var btnSettings: Button
    private lateinit var btnViewLogs: Button
    private lateinit var btnTestConnection: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            val denied = permissions.entries.filter { !it.value }.map { it.key }
            Toast.makeText(this, "权限被拒绝: $denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = ConversationStore(this)
        config = store.loadConfig()

        initViews()
        requestRequiredPermissions()
        updateUI()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvServerUrl = findViewById(R.id.tv_server_url)
        tvCaptureStatus = findViewById(R.id.tv_capture_status)
        tvStats = findViewById(R.id.tv_stats)
        btnToggleServer = findViewById(R.id.btn_toggle_server)
        btnToggleCapture = findViewById(R.id.btn_toggle_capture)
        btnSettings = findViewById(R.id.btn_settings)
        btnViewLogs = findViewById(R.id.btn_view_logs)
        btnTestConnection = findViewById(R.id.btn_test_connection)

        btnToggleServer.setOnClickListener {
            if (ApiServerService.isRunning) {
                stopServer()
            } else {
                startServer()
            }
        }

        btnToggleCapture.setOnClickListener { toggle ->
            AICaptureService.setCaptureEnabled(toggle)
            tvCaptureStatus.text = if (toggle) "🟢 AI捕获已开启" else "🔴 AI捕获已关闭"
            Toast.makeText(
                this,
                if (toggle) "AI捕获已开启" else "AI捕获已关闭",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        btnTestConnection.setOnClickListener {
            testServerConnection()
        }

        // 检查无障碍服务是否已启用
        if (!isAccessibilityServiceEnabled()) {
            btnToggleCapture.isChecked = false
            tvCaptureStatus.text = "🔴 无障碍服务未启用"
        }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                permissionsToRequest.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
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
        Toast.makeText(this, "API服务器已启动 端口: ${config.serverPort}", Toast.LENGTH_SHORT).show()
    }

    private fun stopServer() {
        val intent = Intent(this, ApiServerService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
        updateUI()
        Toast.makeText(this, "API服务器已停止", Toast.LENGTH_SHORT).show()
    }

    private fun testServerConnection() {
        Thread {
            try {
                val url = "http://localhost:${config.serverPort}/api/status"
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                val code = connection.responseCode

                runOnUiThread {
                    if (code == 200) {
                        Toast.makeText(this, "✅ 服务器连接成功 (HTTP $code)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "⚠️ 服务器响应异常 (HTTP $code)", Toast.LENGTH_SHORT).show()
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "❌ 无法连接服务器: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun updateUI() {
        val isRunning = ApiServerService.isRunning
        tvStatus.text = if (isRunning) "🟢 服务器运行中" else "🔴 服务器未启动"
        tvServerUrl.text = "http://localhost:${config.serverPort}"

        val localIp = getLocalIpAddress()
        if (localIp != null) {
            tvServerUrl.text = "http://$localIp:${config.serverPort}\nhttp://localhost:${config.serverPort}"
        }

        btnToggleServer.text = if (isRunning) "停止服务器" else "启动服务器"

        val stats = store.getStats()
        val totalConvs = stats["totalConversations"] as? Int ?: 0
        val totalMsgs = stats["totalMessages"] as? Int ?: 0
        tvStats.text = "会话: $totalConvs | 消息: $totalMsgs"

        if (isAccessibilityServiceEnabled()) {
            tvCaptureStatus.text = "🟢 无障碍服务已启用"
            btnToggleCapture.isChecked = true
        } else {
            tvCaptureStatus.text = "🔴 无障碍服务未启用 (点击开启)"
            btnToggleCapture.isChecked = false
            btnToggleCapture.setOnClickListener {
                openAccessibilitySettings()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/.service.AICaptureService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service) == true
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("开启无障碍服务")
            .setMessage("ApiAPK需要无障碍服务权限来捕获AI应用的对话内容。\n\n请在设置中找到\"ApiAPK\"并开启服务。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.equals("wlan0", ignoreCase = true)) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address.hostAddress?.contains(":") == false) {
                            return address.hostAddress
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
