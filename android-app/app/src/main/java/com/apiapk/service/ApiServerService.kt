package com.apiapk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.apiapk.model.*
import com.apiapk.util.AdbHelper
import fi.iki.elonen.NanoHTTPD
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.IOException

/**
 * API HTTP服务器服务 - 在Android设备上运行轻量级HTTP服务器（NanoHTTPD），
 * 将捕获的AI对话数据通过RESTful API暴露给外部客户端。
 * 支持CORS跨域请求，提供完整的会话查询、消息发送和状态监控接口。
 *
 * API端点：
 *   GET  /api/status              - 服务状态
 *   GET  /api/conversations       - 所有会话列表
 *   GET  /api/conversations/:app  - 按应用过滤会话
 *   GET  /api/conversations/:id   - 获取特定会话详情
 *   POST /api/send/:app           - 向AI应用发送消息
 *   DELETE /api/conversations/:id - 删除会话
 *   GET  /api/stats               - 捕获统计信息
 *   POST /api/config              - 更新配置
 *   GET  /api/config              - 获取当前配置
 *   POST /api/adb/command         - 执行ADB命令
 *   GET  /api/adb/devices         - 获取ADB设备列表
 *   POST /api/adb/shell           - 执行ADB shell命令
 */
class ApiServerService : Service() {

    companion object {
        private const val TAG = "ApiServerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "apiapk_server_channel"
        const val EXTRA_CONFIG = "config"

        var serverInstance: ApiHttpServer? = null
        var isRunning = false
    }

    private lateinit var conversationStore: ConversationStore
    private lateinit var gson: Gson
    private lateinit var config: ApiConfig
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        conversationStore = ConversationStore(this)
        gson = GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create()
        config = conversationStore.loadConfig()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (serverInstance?.isAlive == true) {
            Log.w(TAG, "Server is already running")
            return
        }

        config = conversationStore.loadConfig()
        serverInstance = ApiHttpServer(config.serverPort, conversationStore, gson)
        try {
            serverInstance?.start()
            isRunning = true
            Log.i(TAG, "API Server started on port ${config.serverPort}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            isRunning = false
        }
    }

    private fun stopServer() {
        serverInstance?.stop()
        serverInstance = null
        isRunning = false
        Log.i(TAG, "API Server stopped")
    }

    private fun startForegroundNotification() {
        val notification = createNotification("ApiAPK Server Running", "Port: ${config.serverPort}")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ApiAPK Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "API Server foreground service notification"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    /**
     * NanoHTTPD HTTP服务器实现 - 处理所有RESTful API请求。
     * 包含路由分发、请求验证、CORS处理和JSON序列化。
     */
    inner class ApiHttpServer(
        port: Int,
        private val store: ConversationStore,
        private val json: Gson
    ) : NanoHTTPD(port) {

        private val adbHelper = AdbHelper()

        override fun serve(session: IHTTPSession): Response {
            addCorsHeaders(session)
            if (session.method == Method.OPTIONS) {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            }

            val uri = session.uri
            val path = uri.substringBefore("?")
            val params = session.parameters

            Log.d(TAG, "Request: ${session.method} $path")

            return try {
                when {
                    path == "/api/status" -> handleStatus()
                    path == "/api/conversations" && session.method == Method.GET ->
                        handleGetConversations(params)
                    path.matches(Regex("/api/conversations/(deepseek|doubao|xiaoai|custom)")) && session.method == Method.GET -> {
                        val app = path.split("/").last()
                        handleGetConversationsByApp(app)
                    }
                    path.matches(Regex("/api/conversations/[a-f0-9\\-]+")) && session.method == Method.GET -> {
                        val id = path.split("/").last()
                        handleGetConversation(id)
                    }
                    path.matches(Regex("/api/send/(deepseek|doubao|xiaoai)")) && session.method == Method.POST -> {
                        val app = path.split("/").last()
                        handleSendMessage(session, app)
                    }
                    path.matches(Regex("/api/conversations/[a-f0-9\\-]+")) && session.method == Method.DELETE -> {
                        val id = path.split("/").last()
                        handleDeleteConversation(id)
                    }
                    path == "/api/stats" -> handleStats()
                    path == "/api/config" && session.method == Method.GET -> handleGetConfig()
                    path == "/api/config" && session.method == Method.POST -> handleUpdateConfig(session)
                    path == "/api/adb/command" && session.method == Method.POST -> handleAdbCommand(session)
                    path == "/api/adb/devices" && session.method == Method.GET -> handleAdbDevices()
                    path == "/api/adb/shell" && session.method == Method.POST -> handleAdbShell(session)
                    path == "/" -> handleIndex()
                    path.startsWith("/api/") -> jsonResponse(
                        Response.Status.NOT_FOUND,
                        ApiResponse(success = false, message = "Endpoint not found: $path")
                    )
                    else -> jsonResponse(
                        Response.Status.NOT_FOUND,
                        ApiResponse(success = false, message = "Not found")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling request: ${e.message}", e)
                jsonResponse(
                    Response.Status.INTERNAL_ERROR,
                    ApiResponse(success = false, message = "Server error: ${e.message}")
                )
            }
        }

        private fun addCorsHeaders(session: IHTTPSession) {
            session.headers["Access-Control-Allow-Origin"] = "*"
            session.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
            session.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization, X-API-Key"
        }

        private fun handleStatus(): Response {
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "ApiAPK Server is running",
                data = mapOf(
                    "version" to "1.0.0",
                    "uptime" to System.currentTimeMillis(),
                    "captureServiceActive" to AICaptureService.isServiceRunning(),
                    "conversationsCount" to store.getConversationCount(),
                    "adbAvailable" to adbHelper.isAdbAvailable(),
                    "endpoints" to listOf(
                        "GET /api/status",
                        "GET /api/conversations",
                        "GET /api/conversations/{app}",
                        "GET /api/conversations/{id}",
                        "POST /api/send/{app}",
                        "DELETE /api/conversations/{id}",
                        "GET /api/stats",
                        "GET/POST /api/config",
                        "POST /api/adb/command",
                        "GET /api/adb/devices",
                        "POST /api/adb/shell"
                    )
                )
            ))
        }

        private fun handleGetConversations(params: Map<String, String>): Response {
            val page = params["page"]?.toIntOrNull() ?: 1
            val limit = params["limit"]?.toIntOrNull() ?: 50

            val summaries = store.getConversationSummaries()
            val start = ((page - 1) * limit).coerceAtMost(summaries.size)
            val end = (start + limit).coerceAtMost(summaries.size)

            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "Conversations retrieved",
                data = mapOf(
                    "conversations" to summaries.subList(start, end),
                    "total" to summaries.size,
                    "page" to page,
                    "limit" to limit
                )
            ))
        }

        private fun handleGetConversationsByApp(appIdentifier: String): Response {
            val summaries = store.getConversationSummariesByApp(appIdentifier)
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "Conversations for $appIdentifier retrieved",
                data = mapOf(
                    "conversations" to summaries,
                    "total" to summaries.size,
                    "app" to appIdentifier
                )
            ))
        }

        private fun handleGetConversation(id: String): Response {
            val conversation = store.getConversation(id)
            return if (conversation != null) {
                jsonResponse(Response.Status.OK, ApiResponse(
                    success = true,
                    message = "Conversation retrieved",
                    data = conversation
                ))
            } else {
                jsonResponse(Response.Status.NOT_FOUND, ApiResponse(
                    success = false,
                    message = "Conversation not found: $id"
                ))
            }
        }

        private fun handleSendMessage(session: IHTTPSession, appIdentifier: String): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""

            val messageData = try {
                json.fromJson(body, Map::class.java)
            } catch (e: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(
                    success = false,
                    message = "Invalid JSON body"
                ))
            }

            val message = messageData["message"]?.toString()
            if (message.isNullOrEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(
                    success = false,
                    message = "Missing 'message' field"
                ))
            }

            val appType = AIConversation.AppType.entries.find { it.identifier == appIdentifier }
            if (appType == null) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(
                    success = false,
                    message = "Unknown app: $appIdentifier. Supported: deepseek, doubao, xiaoai"
                ))
            }

            val sentViaAdb = adbHelper.sendViaAdb(appType, message)
            val conversation = store.getOrAddMessage(appType, "user", message)

            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = if (sentViaAdb) "Message sent via ADB and captured" else "Message captured (ADB not available)",
                data = mapOf(
                    "conversation" to conversation.toSummary(),
                    "sentViaAdb" to sentViaAdb
                )
            ))
        }

        private fun handleDeleteConversation(id: String): Response {
            val deleted = store.deleteConversation(id)
            return if (deleted) {
                jsonResponse(Response.Status.OK, ApiResponse(
                    success = true,
                    message = "Conversation deleted: $id"
                ))
            } else {
                jsonResponse(Response.Status.NOT_FOUND, ApiResponse(
                    success = false,
                    message = "Conversation not found: $id"
                ))
            }
        }

        private fun handleStats(): Response {
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "Statistics retrieved",
                data = store.getStats()
            ))
        }

        private fun handleGetConfig(): Response {
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "Configuration retrieved",
                data = store.loadConfig()
            ))
        }

        private fun handleUpdateConfig(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""

            val newConfig = try {
                json.fromJson(body, ApiConfig::class.java)
            } catch (e: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(
                    success = false,
                    message = "Invalid config JSON"
                ))
            }

            store.saveConfig(newConfig)
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "Configuration updated. Restart server for port changes.",
                data = newConfig
            ))
        }

        private fun handleAdbCommand(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""

            val data = try {
                json.fromJson(body, Map::class.java)
            } catch (e: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(
                    success = false,
                    message = "Invalid JSON body"
                ))
            }

            val command = data["command"]?.toString()
            if (command.isNullOrEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(
                    success = false,
                    message = "Missing 'command' field"
                ))
            }

            val result = adbHelper.executeCommand(command)
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = result.success,
                message = if (result.success) "ADB command executed" else "ADB command failed",
                data = mapOf(
                    "stdout" to result.stdout,
                    "stderr" to result.stderr,
                    "exitCode" to result.exitCode
                )
            ))
        }

        private fun handleAdbDevices(): Response {
            val devices = adbHelper.getDevices()
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "ADB devices listed",
                data = mapOf("devices" to devices)
            ))
        }

        private fun handleAdbShell(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""

            val data = try {
                json.fromJson(body, Map::class.java)
            } catch (e: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(
                    success = false,
                    message = "Invalid JSON body"
                ))
            }

            val shellCommand = data["command"]?.toString()
            if (shellCommand.isNullOrEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(
                    success = false,
                    message = "Missing 'command' field"
                ))
            }

            val result = adbHelper.executeShell(shellCommand)
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = result.success,
                message = "Shell command executed",
                data = mapOf(
                    "stdout" to result.stdout,
                    "stderr" to result.stderr,
                    "exitCode" to result.exitCode
                )
            ))
        }

        private fun handleIndex(): Response {
            val html = """
            <!DOCTYPE html>
            <html>
            <head><title>ApiAPK Server</title>
            <style>
                body { font-family: -apple-system, sans-serif; max-width: 800px; margin: 40px auto; padding: 20px; background: #1a1a2e; color: #eee; }
                h1 { color: #e94560; } h2 { color: #0f3460; background: #16213e; padding: 10px; border-radius: 5px; }
                code { background: #16213e; padding: 2px 6px; border-radius: 3px; font-family: monospace; color: #00d9ff; }
                pre { background: #16213e; padding: 15px; border-radius: 5px; overflow-x: auto; }
                .endpoint { margin: 8px 0; padding: 8px; background: #16213e; border-radius: 5px; }
                .method { color: #e94560; font-weight: bold; }
            </style></head>
            <body>
                <h1>🚀 ApiAPK Server v1.0.0</h1>
                <p>AI Model I/O Capture & API Proxy</p>
                <h2>📡 API Endpoints</h2>
                <div class="endpoint"><span class="method">GET</span> <code>/api/status</code> - Server status</div>
                <div class="endpoint"><span class="method">GET</span> <code>/api/conversations</code> - All conversations</div>
                <div class="endpoint"><span class="method">GET</span> <code>/api/conversations/{app}</code> - By app (deepseek|doubao|xiaoai)</div>
                <div class="endpoint"><span class="method">GET</span> <code>/api/conversations/{id}</code> - Conversation detail</div>
                <div class="endpoint"><span class="method">POST</span> <code>/api/send/{app}</code> - Send message</div>
                <div class="endpoint"><span class="method">GET</span> <code>/api/stats</code> - Capture statistics</div>
                <div class="endpoint"><span class="method">POST</span> <code>/api/adb/command</code> - Execute ADB command</div>
                <div class="endpoint"><span class="method">POST</span> <code>/api/adb/shell</code> - Execute shell command</div>
                <h2>💡 Usage Example</h2>
                <pre>curl http://localhost:8765/api/status
curl http://localhost:8765/api/conversations/deepseek
curl -X POST http://localhost:8765/api/send/deepseek -d '{"message":"Hello!"}'</pre>
                <p>Install the <a href="https://www.npmjs.com/package/apiapk" style="color:#00d9ff">apiapk</a> npm package for CLI access.</p>
            </body></html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun jsonResponse(status: Response.Status, data: Any): Response {
            val jsonStr = json.toJson(data)
            return newFixedLengthResponse(status, "application/json; charset=utf-8", jsonStr)
        }
    }
}
