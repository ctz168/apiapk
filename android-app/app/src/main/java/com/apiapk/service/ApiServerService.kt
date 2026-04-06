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
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.Thread.sleep
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API HTTP服务器服务 v3 - 后台稳定版。
 *
 * v3 修复：
 *   - startForeground() 移到 onCreate()（Android 12+ 要求5秒内）
 *   - 去掉 onDestroy() 中级联停止 BackgroundMonitorService
 *   - 去掉 START 方式中级联停止
 *   - 心跳由 BackgroundMonitorService 统一管理
 *   - 添加 /api/debug 端点用于远程诊断
 */
class ApiServerService : Service() {

    companion object {
        private const val TAG = "ApiServerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "apiapk_server_channel"

        @Volatile
        var serverInstance: ApiHttpServer? = null
            private set
        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var conversationStore: ConversationStore
    private lateinit var gson: Gson
    private lateinit var config: ApiConfig
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "=== ApiServerService onCreate ===")

        conversationStore = ConversationStore(this)
        gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        config = conversationStore.loadConfig()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // ★ 关键修复：在 onCreate 中立即调用 startForeground
        // Android 12+ 要求 startForegroundService 后 5 秒内调用 startForeground
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        if (intent?.action == "STOP") {
            // 只停止自己，不级联停止 BackgroundMonitorService
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 如果已经运行，跳过
        if (serverInstance?.isAlive == true) {
            Log.i(TAG, "Server already running, skipping start")
            return START_STICKY
        }

        startServer()
        startBackgroundMonitor()
        return START_STICKY
    }

    private fun startServer() {
        if (serverInstance?.isAlive == true) {
            Log.w(TAG, "Server is already running")
            return
        }

        config = conversationStore.loadConfig()
        serverInstance = ApiHttpServer(config.serverPort, conversationStore, gson, this)
        try {
            serverInstance?.start()
            isRunning = true
            Log.i(TAG, "✅ API Server started on port ${config.serverPort}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start server: ${e.message}")
            isRunning = false
        }
    }

    private fun stopServer() {
        try {
            serverInstance?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
        serverInstance = null
        isRunning = false
        Log.i(TAG, "API Server stopped")
    }

    private fun startBackgroundMonitor() {
        val bgIntent = Intent(this, BackgroundMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(bgIntent)
        } else {
            startService(bgIntent)
        }
        Log.i(TAG, "Background monitor started")
    }

    private fun startForegroundNotification() {
        val notification = createNotification("ApiAPK 运行中", "端口: ${config.serverPort} | SSE就绪")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ApiAPK Server", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "API Server + SSE streaming"
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setPriority(Notification.PRIORITY_LOW).build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "=== ApiServerService onDestroy ===")
        // ★ 关键修复：不级联停止 BackgroundMonitorService
        // 让它独立运行，继续守护系统
        stopServer()
        super.onDestroy()
    }

    /**
     * NanoHTTPD HTTP服务器 v3。
     */
    inner class ApiHttpServer(
        port: Int,
        private val store: ConversationStore,
        private val json: Gson,
        private val context: android.content.Context
    ) : NanoHTTPD(port) {

        private val adbHelper = AdbHelper()
        private val sseConnections = CopyOnWriteArrayList<SSEConnection>()

        inner class SSEConnection(
            val appFilter: String?,
            val id: String = java.util.UUID.randomUUID().toString(),
            val created: Long = System.currentTimeMillis()
        ) {
            var isActive = AtomicBoolean(true)
            var lastActivity = System.currentTimeMillis()
        }

        override fun serve(session: IHTTPSession): Response {
            addCorsHeaders(session)
            if (session.method == Method.OPTIONS) {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            }

            val uri = session.uri
            val path = uri.substringBefore("?")
            val rawParams = session.parameters
            val params = rawParams.mapValues { it.value.firstOrNull() ?: "" }

            return try {
                when {
                    path == "/api/status" -> handleStatus()
                    path == "/api/debug" -> handleDebug()
                    path == "/api/conversations" && session.method == Method.GET ->
                        handleGetConversations(params)
                    path.matches(Regex("/api/conversations/(deepseek|doubao|xiaoai|custom)")) && session.method == Method.GET ->
                        handleGetConversationsByApp(path.split("/").last())
                    path.matches(Regex("/api/conversations/[a-f0-9\\-]+")) && session.method == Method.GET ->
                        handleGetConversation(path.split("/").last())
                    path.matches(Regex("/api/send/(deepseek|doubao|xiaoai)")) && session.method == Method.POST ->
                        handleSendMessage(session, path.split("/").last())
                    path.matches(Regex("/api/conversations/[a-f0-9\\-]+")) && session.method == Method.DELETE ->
                        handleDeleteConversation(path.split("/").last())
                    path == "/api/stats" -> handleStats()
                    path == "/api/config" && session.method == Method.GET -> handleGetConfig()
                    path == "/api/config" && session.method == Method.POST -> handleUpdateConfig(session)
                    path == "/api/adb/command" && session.method == Method.POST -> handleAdbCommand(session)
                    path == "/api/adb/devices" && session.method == Method.GET -> handleAdbDevices()
                    path == "/api/adb/shell" && session.method == Method.POST -> handleAdbShell(session)
                    path == "/api/stream" -> handleSSE(session, null)
                    path.matches(Regex("/api/stream/(deepseek|doubao|xiaoai)")) ->
                        handleSSE(session, path.split("/").last())
                    path == "/api/ui/dump" -> handleUIDump()
                    path == "/api/ui/snapshot" -> handleUISnapshot()
                    path == "/v1/models" -> handleV1Models()
                    path == "/v1/chat/completions" && session.method == Method.POST ->
                        handleV1ChatCompletions(session)
                    path == "/" -> handleIndex()
                    path.startsWith("/api/") -> jsonResponse(Response.Status.NOT_FOUND,
                        ApiResponse(success = false, message = "Not found: $path"))
                    else -> jsonResponse(Response.Status.NOT_FOUND,
                        ApiResponse(success = false, message = "Not found"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}", e)
                jsonResponse(Response.Status.INTERNAL_ERROR,
                    ApiResponse(success = false, message = "Server error: ${e.message}"))
            }
        }

        private fun addCorsHeaders(session: IHTTPSession) {
            session.headers["Access-Control-Allow-Origin"] = "*"
            session.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
            session.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization, X-API-Key, Accept"
            session.headers["Access-Control-Expose-Headers"] = "Content-Type"
        }

        // ★ 新增调试端点
        private fun handleDebug(): Response {
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "Debug info",
                data = AICaptureService.getDebugInfo()
            ))
        }

        // ===================== SSE =====================

        private fun handleSSE(session: IHTTPSession, appFilter: String?): Response {
            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut)
            val writer = java.io.PrintWriter(pipeOut, true)
            val conn = SSEConnection(appFilter)
            sseConnections.add(conn)

            Thread({
                try {
                    writer.println(": ApiAPK SSE connected")
                    writer.println(": Apps: deepseek, doubao, xiaoai")
                    writer.println()

                    val recentEvents = StreamEventBus.getRecentEvents(filterApp = appFilter)
                    if (recentEvents.isNotEmpty()) {
                        writer.println(": Replaying ${recentEvents.size} events...")
                        writer.println()
                        for (event in recentEvents) {
                            writeSSEEvent(writer, event)
                        }
                    }

                    var lastCheckTime = System.currentTimeMillis()
                    while (conn.isActive.get()) {
                        val newEvents = StreamEventBus.getRecentEvents(
                            filterApp = appFilter,
                            sinceTimestamp = lastCheckTime
                        )
                        for (event in newEvents) {
                            if (event.timestamp > lastCheckTime) {
                                writeSSEEvent(writer, event)
                                lastCheckTime = event.timestamp
                                conn.lastActivity = System.currentTimeMillis()
                            }
                        }
                        sleep(500)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "SSE closed: ${e.message}")
                } finally {
                    try { pipeOut.close() } catch (_: Exception) {}
                }
            }, "sse-$appFilter-${conn.id}").apply { isDaemon = true; start() }

            return newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", pipeIn)
        }

        private fun writeSSEEvent(writer: java.io.PrintWriter, delta: StreamDelta) {
            val eventType = if (delta.finishReason != null) "finish" else "delta"
            writer.println("event: $eventType")
            writer.println("data: ${json.toJson(delta)}")
            writer.println()
            writer.flush()
        }

        // ===================== UI探测 =====================

        private fun handleUIDump(): Response {
            val tree = AICaptureService.getLastDumpedTree()
            return if (tree.isNotEmpty()) {
                jsonResponse(Response.Status.OK, ApiResponse(
                    success = true, message = "UI tree dumped",
                    data = mapOf("tree" to tree, "lines" to tree.lines().count { it.trim().isNotEmpty() })
                ))
            } else {
                jsonResponse(Response.Status.OK, ApiResponse(
                    success = true, message = "No tree yet (inspector is always-on in v4)"
                ))
            }
        }

        private fun handleUISnapshot(): Response {
            val tree = AICaptureService.getLastDumpedTree()
            val nodes = tree.lines()
                .filter { it.contains("text=\"") && it.contains("text=\"\"").not() }
                .map { it.trim() }
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true, message = "Text snapshot",
                data = mapOf("nodes" to nodes, "count" to nodes.size)
            ))
        }

        // ===================== OpenAI兼容 =====================

        private fun handleV1Models(): Response {
            val stats = store.getStats()
            val byApp = stats["byApp"] as? Map<*, *> ?: emptyMap<String, Any>()
            val models = AIConversation.AppType.entries.map { app ->
                val appStats = (byApp[app.identifier] as? Map<*, *>) ?: emptyMap<String, Any>()
                mapOf(
                    "id" to app.identifier, "object" to "model",
                    "created" to (System.currentTimeMillis() / 1000),
                    "owned_by" to "apiapk",
                    "description" to "${app.displayName} (captured)",
                    "conversations" to (appStats["conversations"] ?: 0),
                    "messages" to (appStats["messages"] ?: 0)
                )
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                json.toJson(mapOf("object" to "list", "data" to models)))
        }

        private fun handleV1ChatCompletions(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""
            val requestData = try { json.fromJson(body, Map::class.java) } catch (e: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Invalid JSON"))
            }
            val model = requestData["model"]?.toString()
            val messages = requestData["messages"] as? List<*>
            val stream = requestData["stream"]?.toString()?.toBoolean() ?: false
            if (model == null || messages.isNullOrEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Missing model/messages"))
            }
            val appType = AIConversation.AppType.entries.find { it.identifier == model }
            if (appType == null) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Unknown model: $model"))
            }
            val userContent = (messages.lastOrNull() as? Map<*, *>)?.get("content")?.toString() ?: ""
            if (userContent.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "No user message"))
            }
            val sentViaAdb = adbHelper.sendViaAdb(appType, userContent)
            val conversation = store.getOrAddMessage(appType, "user", userContent)
            if (stream) {
                return handleV1StreamResponse(model, conversation.id ?: "")
            } else {
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toJson(mapOf(
                    "id" to "chatcmpl-${conversation.id}", "object" to "chat.completion",
                    "created" to (System.currentTimeMillis() / 1000), "model" to model,
                    "choices" to listOf(mapOf("index" to 0,
                        "message" to mapOf("role" to "assistant",
                            "content" to "Sent to $model. Use /api/stream/$model for streaming response."),
                        "finish_reason" to "stop")),
                    "usage" to mapOf("prompt_tokens" to userContent.length, "completion_tokens" to 0, "total_tokens" to userContent.length)
                )))
            }
        }

        private fun handleV1StreamResponse(model: String, conversationId: String): Response {
            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut)
            val writer = java.io.PrintWriter(pipeOut, true)
            Thread({
                try {
                    val sessionId = "v1-${System.currentTimeMillis()}"
                    var receivedAny = false
                    val timeout = System.currentTimeMillis() + 60000
                    while (System.currentTimeMillis() < timeout) {
                        val events = StreamEventBus.getRecentEvents(filterApp = model, sinceTimestamp = System.currentTimeMillis() - 2000)
                        for (event in events) {
                            receivedAny = true
                            val chunk = mapOf(
                                "id" to "chatcmpl-$sessionId", "object" to "chat.completion.chunk",
                                "created" to (event.timestamp / 1000), "model" to model,
                                "choices" to listOf(mapOf("index" to 0,
                                    "delta" to mapOf("content" to event.delta, "role" to if (!receivedAny) "assistant" else null),
                                    "finish_reason" to event.finishReason)))
                            writer.println("data: ${json.toJson(chunk)}")
                            writer.flush()
                            if (event.finishReason != null) {
                                writer.println("data: [DONE]")
                                writer.flush()
                                return@Thread
                            }
                        }
                        sleep(300)
                    }
                    if (!receivedAny) {
                        val chunk = mapOf(
                            "id" to "chatcmpl-$sessionId", "object" to "chat.completion.chunk",
                            "created" to (System.currentTimeMillis() / 1000), "model" to model,
                            "choices" to listOf(mapOf("index" to 0,
                                "delta" to mapOf("content" to "（等待超时，请确认APP在前台）"), "finish_reason" to "stop")))
                        writer.println("data: ${json.toJson(chunk)}")
                        writer.println("data: [DONE]")
                        writer.flush()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "V1 stream closed: ${e.message}")
                } finally {
                    try { pipeOut.close() } catch (_: Exception) {}
                }
            }, "v1stream").apply { isDaemon = true; start() }
            return newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", pipeIn)
        }

        // ===================== 常规端点 =====================

        private fun handleStatus(): Response {
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true, message = "ApiAPK Server v3",
                data = mapOf(
                    "version" to "3.0.0",
                    "serverRunning" to isRunning,
                    "captureActive" to AICaptureService.isServiceRunning(),
                    "bgMonitorActive" to BackgroundMonitorService.isRunning,
                    "conversations" to store.getConversationCount(),
                    "port" to config.serverPort,
                    "endpoints" to listOf("/api/status", "/api/debug", "/api/conversations", "/api/stream", "/api/ui/dump", "/v1/chat/completions")
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
                success = true, message = "OK",
                data = mapOf("conversations" to summaries.subList(start, end), "total" to summaries.size, "page" to page)
            ))
        }

        private fun handleGetConversationsByApp(app: String): Response {
            val summaries = store.getConversationSummariesByApp(app)
            return jsonResponse(Response.Status.OK, ApiResponse(success = true, message = "OK", data = mapOf("conversations" to summaries, "total" to summaries.size)))
        }

        private fun handleGetConversation(id: String): Response {
            val conv = store.getConversation(id)
            return if (conv != null) {
                jsonResponse(Response.Status.OK, ApiResponse(success = true, message = "OK", data = conv))
            } else {
                jsonResponse(Response.Status.NOT_FOUND, ApiResponse(success = false, message = "Not found"))
            }
        }

        private fun handleSendMessage(session: IHTTPSession, app: String): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val data = try { json.fromJson(files["postData"] ?: "", Map::class.java) } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Invalid JSON"))
            }
            val message = data["message"]?.toString()
            if (message.isNullOrEmpty()) return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Missing message"))
            val appType = AIConversation.AppType.entries.find { it.identifier == app }
            if (appType == null) return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Unknown app"))
            val sent = adbHelper.sendViaAdb(appType, message)
            val conv = store.getOrAddMessage(appType, "user", message)
            return jsonResponse(Response.Status.OK, ApiResponse(success = true, message = if (sent) "Sent via ADB" else "Captured", data = mapOf("sent" to sent)))
        }

        private fun handleDeleteConversation(id: String): Response {
            return if (store.deleteConversation(id)) {
                jsonResponse(Response.Status.OK, ApiResponse(success = true, message = "Deleted"))
            } else {
                jsonResponse(Response.Status.NOT_FOUND, ApiResponse(success = false, message = "Not found"))
            }
        }

        private fun handleStats() = jsonResponse(Response.Status.OK, ApiResponse(success = true, message = "OK", data = store.getStats()))

        private fun handleGetConfig() = jsonResponse(Response.Status.OK, ApiResponse(success = true, message = "OK", data = store.loadConfig()))

        private fun handleUpdateConfig(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val newConfig = try { json.fromJson(files["postData"] ?: "", ApiConfig::class.java) } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Invalid config"))
            }
            store.saveConfig(newConfig)
            return jsonResponse(Response.Status.OK, ApiResponse(success = true, message = "Config updated", data = newConfig))
        }

        private fun handleAdbCommand(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>(); session.parseBody(files)
            val data = try { json.fromJson(files["postData"] ?: "", Map::class.java) } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Invalid JSON"))
            }
            val cmd = data["command"]?.toString() ?: return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Missing command"))
            val result = adbHelper.executeCommand(cmd)
            return jsonResponse(Response.Status.OK, ApiResponse(success = result.success, message = if (result.success) "OK" else "Failed", data = mapOf("stdout" to result.stdout, "exitCode" to result.exitCode)))
        }

        private fun handleAdbDevices() = jsonResponse(Response.Status.OK, ApiResponse(success = true, message = "OK", data = mapOf("devices" to adbHelper.getDevices())))

        private fun handleAdbShell(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>(); session.parseBody(files)
            val data = try { json.fromJson(files["postData"] ?: "", Map::class.java) } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Invalid JSON"))
            }
            val cmd = data["command"]?.toString() ?: return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Missing command"))
            val r = adbHelper.executeShell(cmd)
            return jsonResponse(Response.Status.OK, ApiResponse(success = r.success, message = if (r.success) "OK" else "Failed", data = mapOf("stdout" to r.stdout, "exitCode" to r.exitCode)))
        }

        private fun handleIndex(): Response {
            val html = """
<!DOCTYPE html><html><head><title>ApiAPK v3</title>
<style>
body{font-family:-apple-system,sans-serif;max-width:860px;margin:40px auto;padding:20px;background:#0d1117;color:#c9d1d9}
h1{color:#ff7b72}h2{color:#58a6ff;border-bottom:1px solid #21262d;padding-bottom:8px}
code{background:#161b22;padding:2px 6px;border-radius:4px;color:#79c0ff;font-family:monospace;font-size:14px}
.ep{margin:6px 0;padding:8px 12px;background:#161b22;border-radius:6px;border-left:3px solid #238636}
.m{color:#ff7b72;font-weight:bold;display:inline-block;width:60px}
</style></head><body>
<h1>ApiAPK v3</h1>
<p>全量文本捕获 | 后台持久 | 分屏 | OpenAI兼容</p>
<h2>API</h2>
<div class="ep"><span class="m">GET</span><code>/api/status</code></div>
<div class="ep"><span class="m">GET</span><code>/api/debug</code> 调试信息</div>
<div class="ep"><span class="m">GET</span><code>/api/stream</code> SSE</div>
<div class="ep"><span class="m">GET</span><code>/api/ui/dump</code> 节点树</div>
<div class="ep"><span class="m">POST</span><code>/v1/chat/completions</code></div>
<h2>调试</h2>
<pre>curl http://PHONE_IP:8765/api/debug
curl http://PHONE_IP:8765/api/ui/dump
curl -N http://PHONE_IP:8765/api/stream</pre>
</body></html>""".trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun jsonResponse(status: Response.Status, data: Any): Response {
            return newFixedLengthResponse(status, "application/json; charset=utf-8", json.toJson(data))
        }
    }
}
