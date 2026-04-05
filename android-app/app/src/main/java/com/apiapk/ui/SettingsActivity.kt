package com.apiapk.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.apiapk.model.ApiConfig
import com.apiapk.model.ConversationStore

/**
 * 设置Activity - 允许用户配置API服务器参数。
 * 包括服务器端口、API密钥、最大会话数、日志级别等配置项。
 * 所有配置变更会立即保存到SharedPreferences并生效。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var store: ConversationStore
    private lateinit var config: ApiConfig

    private lateinit var etServerPort: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etMaxConversations: EditText
    private lateinit var etLogLevel: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = ConversationStore(this)
        config = store.loadConfig()

        initViews()
        loadConfig()
    }

    private fun initViews() {
        etServerPort = findViewById(R.id.et_server_port)
        etApiKey = findViewById(R.id.et_api_key)
        etMaxConversations = findViewById(R.id.et_max_conversations)
        etLogLevel = findViewById(R.id.et_log_level)

        val btnSave = findViewById<Button>(R.id.btn_save_config)
        val btnReset = findViewById<Button>(R.id.btn_reset_config)

        btnSave.setOnClickListener {
            saveConfig()
        }

        btnReset.setOnClickListener {
            store.saveConfig(ApiConfig())
            config = ApiConfig()
            loadConfig()
            Toast.makeText(this, "配置已重置为默认值", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadConfig() {
        etServerPort.setText(config.serverPort.toString())
        etApiKey.setText(config.apiKey)
        etMaxConversations.setText(config.maxConversations.toString())
        etLogLevel.setText(config.logLevel)
    }

    private fun saveConfig() {
        val port = etServerPort.text.toString().toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            Toast.makeText(this, "端口必须是 1024-65535 之间的数字", Toast.LENGTH_SHORT).show()
            return
        }

        val maxConvs = etMaxConversations.text.toString().toIntOrNull()
        if (maxConvs == null || maxConvs < 10) {
            Toast.makeText(this, "最大会话数必须 ≥ 10", Toast.LENGTH_SHORT).show()
            return
        }

        val newConfig = ApiConfig(
            serverHost = config.serverHost,
            serverPort = port,
            apiKey = etApiKey.text.toString().trim(),
            enableCors = config.enableCors,
            maxConversations = maxConvs,
            captureEnabled = config.captureEnabled,
            autoStart = config.autoStart,
            logLevel = etLogLevel.text.toString().trim().uppercase()
        )

        store.saveConfig(newConfig)
        config = newConfig
        Toast.makeText(this, "配置已保存（端口更改需重启服务器）", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
