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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.Thread.sleep
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API HTTP服务器服务 v2 - 在Android设备上运行轻量级HTTP服务器。
 *
 * v2新增功能：
 *   - SSE流式端点：实时推送AI模型的增量输出（与DeepSeek/豆包的流式体验一致）
 *   - UI探测器端点：dump当前窗口的完整节点树，用于调试和发现正确的UI控件
 *   - OpenAI兼容端点：/v1/chat/completions 支持流式(stream=true)输出
 *   - 后台监控集成：启动时同时启动BackgroundMonitorService保持后台存活
 *
 * API端点：
 *   常规：
 *     GET  /api/status              - 服务状态
 *     GET  /api/conversations       - 所有会话列表
 *     GET  /api/conversations/:app  - 按应用过滤会话
 *     GET  /api/conversations/:id   - 获取特定会话详情
 *     POST /api/send/:app           - 向AI应用发送消息
 *     DELETE /api/conversations/:id - 删除会话
 *     GET  /api/stats               - 捕获统计信息
 *     GET/POST /api/config          - 配置管理
 *     POST /api/adb/command         - 执行ADB命令
 *     GET  /api/adb/devices         - ADB设备列表
 *     POST /api/adb/shell           - 执行Shell命令
 *
 *   流式（新增）：
 *     GET  /api/stream              - SSE流式端点（所有应用）
 *     GET  /api/stream/:app         - SSE流式端点（指定应用）
 *     GET  /api/stream/:app/:id     - SSE流式端点（指定会话）
 *     GET  /api/ui/dump             - UI探测器（dump节点树）
 *     GET  /api/ui/snapshot         - 当前窗口文本快照
 *
 *   OpenAI兼容（新增）：
 *     GET  /v1/models               - 模型列表
 *     POST /v1/chat/completions     - Chat Completions（支持stream）
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
        gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        config = conversationStore.loadConfig()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            // 同时停止后台监控
            val bgIntent = Intent(this, BackgroundMonitorService::class.java).apply { action = "STOP" }
            startService(bgIntent)
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
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
            Log.i(TAG, "API Server v2 started on port ${config.serverPort}")
        } catch (e: Exception) {
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

    /** 同时启动后台监控服务，保持整体链路存活 */
    private fun startBackgroundMonitor() {
        val bgIntent = Intent(this, BackgroundMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(bgIntent)
        } else {
            startService(bgIntent)
        }
        Log.i(TAG, "Background monitor service started")
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
        val bgIntent = Intent(this, BackgroundMonitorService::class.java).apply { action = "STOP" }
        startService(bgIntent)
        stopServer()
        super.onDestroy()
    }

    /**
     * NanoHTTPD HTTP服务器 v2 - 支持SSE流式和UI探测器。
     */
    inner class ApiHttpServer(
        port: Int,
        private val store: ConversationStore,
        private val json: Gson,
        private val context: android.content.Context
    ) : NanoHTTPD(port) {

        private val adbHelper = AdbHelper()
        private val sseConnections = CopyOnWriteArrayList<SSEConnection>()

        /** SSE连接管理 */
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

            Log.d(TAG, "Request: ${session.method} $path")

            return try {
                when {
                    // ========== 常规端点 ==========
                    path == "/api/status" -> handleStatus()
                    path == "/api/conversations" && session.method == Method.GET ->
                        handleGetConversations(params)
                    path.matches(Regex("/api/conversations/(deepseek|doubao|xiaoai|custom)")) && session.method == Method.GET -> {
                        handleGetConversationsByApp(path.split("/").last())
                    }
                    path.matches(Regex("/api/conversations/[a-f0-9\\-]+")) && session.method == Method.GET -> {
                        handleGetConversation(path.split("/").last())
                    }
                    path.matches(Regex("/api/send/(deepseek|doubao|xiaoai)")) && session.method == Method.POST -> {
                        handleSendMessage(session, path.split("/").last())
                    }
                    path.matches(Regex("/api/conversations/[a-f0-9\\-]+")) && session.method == Method.DELETE -> {
                        handleDeleteConversation(path.split("/").last())
                    }
                    path == "/api/stats" -> handleStats()
                    path == "/api/config" && session.method == Method.GET -> handleGetConfig()
                    path == "/api/config" && session.method == Method.POST -> handleUpdateConfig(session)

                    // ========== ADB端点 ==========
                    path == "/api/adb/command" && session.method == Method.POST -> handleAdbCommand(session)
                    path == "/api/adb/devices" && session.method == Method.GET -> handleAdbDevices()
                    path == "/api/adb/shell" && session.method == Method.POST -> handleAdbShell(session)

                    // ========== SSE流式端点（新增） ==========
                    path == "/api/stream" -> handleSSE(session, null)
                    path.matches(Regex("/api/stream/(deepseek|doubao|xiaoai)")) ->
                        handleSSE(session, path.split("/").last())

                    // ========== UI探测器端点（新增） ==========
                    path == "/api/ui/dump" -> handleUIDump()
                    path == "/api/ui/snapshot" -> handleUISnapshot()

                    // ========== OpenAI兼容端点（新增） ==========
                    path == "/v1/models" -> handleV1Models()
                    path == "/v1/chat/completions" && session.method == Method.POST ->
                        handleV1ChatCompletions(session)

                    // ========== 首页 ==========
                    path == "/" -> handleIndex()
                    path.startsWith("/api/") -> jsonResponse(Response.Status.NOT_FOUND,
                        ApiResponse(success = false, message = "Endpoint not found: $path"))
                    else -> jsonResponse(Response.Status.NOT_FOUND,
                        ApiResponse(success = false, message = "Not found"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling request: ${e.message}", e)
                jsonResponse(Response.Status.INTERNAL_ERROR,
                    ApiResponse(success = false, message = "Server error: ${e.message}"))
            }
        }

        private fun addCorsHeaders(session: IHTTPSession) {
            session.headers["Access-Control-Allow-Origin"] = "*"
            session.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
            session.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization, X-API-Key, Accept, Cache-Control"
            session.headers["Access-Control-Expose-Headers"] = "Content-Type"
        }

        // ===================== SSE流式端点 =====================

        /**
         * SSE (Server-Sent Events) 流式端点。
         *
         * 客户端连接后会持续接收AI模型的增量输出事件：
         *   event: delta
         *   data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"你好","accumulated":"你好"}
         *
         *   event: delta
         *   data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"，","accumulated":"你好，"}
         *
         *   event: finish
         *   data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"","accumulated":"你好，我是DeepSeek","finishReason":"stop"}
         *
         * 兼容 OpenAI SSE 格式，可以直接用 OpenAI SDK 连接。
         */
        private fun handleSSE(session: IHTTPSession, appFilter: String?): Response {
            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut)
            val writer = java.io.PrintWriter(pipeOut, true)

            val conn = SSEConnection(appFilter)
            sseConnections.add(conn)

            // 在后台线程持续推送事件
            val pushThread = Thread({
                try {
                    // 发送SSE头
                    writer.println(": ApiAPK SSE stream connected")
                    writer.println(": Available apps: deepseek, doubao, xiaoai")
                    writer.println()

                    // 如果有历史事件，先推送最近的
                    val recentEvents = StreamEventBus.getRecentEvents(filterApp = appFilter)
                    if (recentEvents.isNotEmpty()) {
                        writer.println(": Replaying ${recentEvents.size} recent events...")
                        writer.println()
                        for (event in recentEvents) {
                            writeSSEEvent(writer, event)
                        }
                    }

                    // 然后进入轮询模式：从事件总线拉取新事件
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

                        // 每500ms检查一次
                        sleep(500)
                    }

                    writer.println(": SSE stream disconnected")
                    writer.flush()
                } catch (e: Exception) {
                    Log.d(TAG, "SSE connection closed: ${e.message}")
                } finally {
                    try { pipeOut.close() } catch (_: Exception) {}
                }
            }, "sse-$appFilter-${conn.id}")

            pushThread.isDaemon = true
            pushThread.start()

            // NanoHTTPD 的 Chunked Response
            return newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", pipeIn)
        }

        /** 格式化一个SSE事件并写入输出流 */
        private fun writeSSEEvent(writer: java.io.PrintWriter, delta: StreamDelta) {
            val eventType = if (delta.finishReason != null) "finish" else "delta"
            val jsonStr = json.toJson(delta)
            writer.println("event: $eventType")
            writer.println("data: $jsonStr")
            writer.println()
            writer.flush()
        }

        // ===================== UI探测器端点 =====================

        /**
         * UI探测器 - dump当前窗口的完整节点树。
         * 用于真机调试：装上APK后打开AI应用，调用此接口就能看到所有控件的真实信息。
         *
         * 返回数据包含每个节点的：className, text, viewId, isEditable, isClickable, hint, bounds
         * 拿到这些信息后，可以精确配置捕获规则。
         */
        private fun handleUIDump(): Response {
            val tree = AICaptureService.getLastDumpedTree()
            return if (tree.isNotEmpty()) {
                jsonResponse(Response.Status.OK, ApiResponse(
                    success = true,
                    message = "UI tree dumped (inspector mode must be enabled)",
                    data = mapOf(
                        "tree" to tree,
                        "nodeCount" to tree.lines().count { it.trim().isNotEmpty() },
                        "hint" to "Enable inspector mode via POST /api/config with {\"inspectorMode\": true}, then open the target AI app and call this endpoint again"
                    )
                ))
            } else {
                jsonResponse(Response.Status.OK, ApiResponse(
                    success = true,
                    message = "No UI tree captured yet. Please: 1) Enable inspector mode 2) Open target AI app 3) Wait for content to appear 4) Call this endpoint",
                    data = mapOf(
                        "tree" to "",
                        "hint" to "POST /api/config {\"inspectorMode\": true}"
                    )
                ))
            }
        }

        /**
         * UI快照 - 返回当前窗口中所有可见文本的简要列表。
         * 比UIDump更简洁，只显示有文本内容的节点。
         */
        private fun handleUISnapshot(): Response {
            val tree = AICaptureService.getLastDumpedTree()
            val nodes = tree.lines()
                .filter { it.contains("text=\"") && it.contains("text=\"\"").not() }
                .map { it.trim() }

            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "Text snapshot from current window",
                data = mapOf(
                    "textNodes" to nodes,
                    "count" to nodes.size
                )
            ))
        }

        // ===================== OpenAI兼容端点 =====================

        /**
         * OpenAI兼容 - 列出可用模型。
         * 将每个捕获的AI应用映射为一个"模型"。
         */
        private fun handleV1Models(): Response {
            val stats = store.getStats()
            val byApp = stats["byApp"] as? Map<*, *> ?: emptyMap<String, Any>()

            val models = AIConversation.AppType.entries.map { app ->
                val appStats = (byApp[app.identifier] as? Map<*, *>) ?: emptyMap<String, Any>()
                mapOf(
                    "id" to app.identifier,
                    "object" to "model",
                    "created" to (System.currentTimeMillis() / 1000),
                    "owned_by" to "apiapk",
                    "description" to "${app.displayName} (captured via accessibility service)",
                    "conversations" to (appStats["conversations"] ?: 0),
                    "messages" to (appStats["messages"] ?: 0)
                )
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json",
                json.toJson(mapOf("object" to "list", "data" to models)))
        }

        /**
         * OpenAI兼容 - Chat Completions。
         * 支持 stream=true 实现流式输出（SSE格式，与OpenAI SDK兼容）。
         *
         * 请求格式（与OpenAI一致）：
         * {
         *   "model": "deepseek",          // deepseek/doubao/xiaoai
         *   "messages": [{"role": "user", "content": "你好"}],
         *   "stream": true                // 可选，是否流式
         * }
         */
        private fun handleV1ChatCompletions(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""

            val requestData = try {
                json.fromJson(body, Map::class.java)
            } catch (e: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                    ApiResponse(success = false, message = "Invalid JSON body"))
            }

            val model = requestData["model"]?.toString()
            val messages = requestData["messages"] as? List<*>
            val stream = requestData["stream"]?.toString()?.toBoolean() ?: false

            if (model == null || messages.isNullOrEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                    ApiResponse(success = false, message = "Missing 'model' or 'messages'"))
            }

            val appType = AIConversation.AppType.entries.find { it.identifier == model }
            if (appType == null) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                    ApiResponse(success = false, message = "Unknown model: $model. Use: deepseek, doubao, xiaoai"))
            }

            val lastUserMsg = messages.lastOrNull()
            val userContent = (lastUserMsg as? Map<*, *>)?.get("content")?.toString() ?: ""

            if (userContent.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                    ApiResponse(success = false, message = "No user message found"))
            }

            // 通过ADB发送消息到AI应用
            val sentViaAdb = adbHelper.sendViaAdb(appType, userContent)
            val conversation = store.getOrAddMessage(appType, "user", userContent)

            if (stream) {
                // 流式响应 - SSE格式（OpenAI兼容）
                return handleV1StreamResponse(model, conversation.id ?: "")
            } else {
                // 非流式响应
                return newFixedLengthResponse(Response.Status.OK, "application/json",
                    json.toJson(mapOf(
                        "id" to "chatcmpl-${conversation.id}",
                        "object" to "chat.completion",
                        "created" to (System.currentTimeMillis() / 1000),
                        "model" to model,
                        "choices" to listOf(mapOf(
                            "index" to 0,
                            "message" to mapOf(
                                "role" to "assistant",
                                "content" to "Message sent to $model via ${if (sentViaAdb) "ADB" else "capture"}. The AI response will be captured automatically. Use SSE endpoint /api/stream/$model to receive the streaming response."
                            ),
                            "finish_reason" to "stop"
                        )),
                        "usage" to mapOf(
                            "prompt_tokens" to userContent.length,
                            "completion_tokens" to 0,
                            "total_tokens" to userContent.length
                        )
                    )))
            }
        }

        /**
         * OpenAI兼容的SSE流式响应。
         * 格式完全兼容OpenAI API，可以直接用openai SDK连接。
         *
         * 数据格式：
         *   data: {"id":"...","object":"chat.completion.chunk","model":"deepseek","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}
         *   data: [DONE]
         */
        private fun handleV1StreamResponse(model: String, conversationId: String): Response {
            val pipeOut = PipedOutputStream()
            val pipeIn = PipedInputStream(pipeOut)
            val writer = java.io.PrintWriter(pipeOut, true)

            Thread({
                try {
                    val sessionId = "v1-${System.currentTimeMillis()}"
                    var receivedAny = false
                    val timeout = System.currentTimeMillis() + 60000 // 60秒超时

                    while (System.currentTimeMillis() < timeout) {
                        // 从事件总线获取新的delta
                        val events = StreamEventBus.getRecentEvents(
                            filterApp = model,
                            sinceTimestamp = System.currentTimeMillis() - 2000
                        )

                        for (event in events) {
                            receivedAny = true
                            val chunk = mapOf(
                                "id" to "chatcmpl-$sessionId",
                                "object" to "chat.completion.chunk",
                                "created" to (event.timestamp / 1000),
                                "model" to model,
                                "choices" to listOf(mapOf(
                                    "index" to 0,
                                    "delta" to mapOf(
                                        "content" to event.delta,
                                        "role" to if (!receivedAny) "assistant" else null
                                    ),
                                    "finish_reason" to event.finishReason
                                ))
                            )
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

                    // 超时，发送完成
                    if (!receivedAny) {
                        val chunk = mapOf(
                            "id" to "chatcmpl-$sessionId",
                            "object" to "chat.completion.chunk",
                            "created" to (System.currentTimeMillis() / 1000),
                            "model" to model,
                            "choices" to listOf(mapOf(
                                "index" to 0,
                                "delta" to mapOf("content" to "（等待AI响应超时，请确认目标APP在前台运行）"),
                                "finish_reason" to "stop"
                            ))
                        )
                        writer.println("data: ${json.toJson(chunk)}")
                        writer.println("data: [DONE]")
                        writer.flush()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "V1 stream closed: ${e.message}")
                } finally {
                    try { pipeOut.close() } catch (_: Exception) {}
                }
            }, "v1stream").apply {
                isDaemon = true
                start()
            }

            return newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", pipeIn)
        }

        // ===================== 常规端点 =====================

        private fun handleStatus(): Response {
            val connCount = sseConnections.count { it.isActive.get() }
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = "ApiAPK Server v2 is running",
                data = mapOf(
                    "version" to "2.0.0",
                    "uptime" to System.currentTimeMillis(),
                    "captureServiceActive" to AICaptureService.isServiceRunning(),
                    "backgroundMonitorActive" to BackgroundMonitorService.isRunning,
                    "conversationsCount" to store.getConversationCount(),
                    "adbAvailable" to adbHelper.isAdbAvailable(),
                    "sseConnections" to connCount,
                    "streamEnabled" to config.streamEnabled,
                    "endpoints" to listOf(
                        "GET /api/status", "GET /api/conversations",
                        "GET /api/conversations/{app}", "GET /api/conversations/{id}",
                        "POST /api/send/{app}", "DELETE /api/conversations/{id}",
                        "GET /api/stats", "GET/POST /api/config",
                        "POST /api/adb/command", "GET /api/adb/devices", "POST /api/adb/shell",
                        "GET /api/stream (SSE)", "GET /api/stream/{app} (SSE)",
                        "GET /api/ui/dump", "GET /api/ui/snapshot",
                        "GET /v1/models", "POST /v1/chat/completions (stream)"
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
                success = true, message = "Conversations retrieved",
                data = mapOf("conversations" to summaries.subList(start, end),
                    "total" to summaries.size, "page" to page, "limit" to limit)
            ))
        }

        private fun handleGetConversationsByApp(appIdentifier: String): Response {
            val summaries = store.getConversationSummariesByApp(appIdentifier)
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true, message = "Conversations for $appIdentifier",
                data = mapOf("conversations" to summaries, "total" to summaries.size, "app" to appIdentifier)
            ))
        }

        private fun handleGetConversation(id: String): Response {
            val conv = store.getConversation(id)
            return if (conv != null) {
                jsonResponse(Response.Status.OK, ApiResponse(success = true, message = "Conversation retrieved", data = conv))
            } else {
                jsonResponse(Response.Status.NOT_FOUND, ApiResponse(success = false, message = "Not found: $id"))
            }
        }

        private fun handleSendMessage(session: IHTTPSession, appIdentifier: String): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""
            val data = try { json.fromJson(body, Map::class.java) } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Invalid JSON"))
            }
            val message = data["message"]?.toString()
            if (message.isNullOrEmpty()) return jsonResponse(Response.Status.BAD_REQUEST,
                ApiResponse(success = false, message = "Missing 'message'"))
            val appType = AIConversation.AppType.entries.find { it.identifier == appIdentifier }
            if (appType == null) return jsonResponse(Response.Status.BAD_REQUEST,
                ApiResponse(success = false, message = "Unknown app: $appIdentifier"))
            val sent = adbHelper.sendViaAdb(appType, message)
            val conv = store.getOrAddMessage(appType, "user", message)
            return jsonResponse(Response.Status.OK, ApiResponse(
                success = true,
                message = if (sent) "Sent via ADB" else "Captured only",
                data = mapOf("conversation" to conv.toSummary(), "sentViaAdb" to sent)
            ))
        }

        private fun handleDeleteConversation(id: String): Response {
            return if (store.deleteConversation(id)) {
                jsonResponse(Response.Status.OK, ApiResponse(success = true, message = "Deleted: $id"))
            } else {
                jsonResponse(Response.Status.NOT_FOUND, ApiResponse(success = false, message = "Not found: $id"))
            }
        }

        private fun handleStats(): Response = jsonResponse(Response.Status.OK,
            ApiResponse(success = true, message = "Stats", data = store.getStats()))

        private fun handleGetConfig(): Response = jsonResponse(Response.Status.OK,
            ApiResponse(success = true, message = "Config", data = store.loadConfig()))

        private fun handleUpdateConfig(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""
            val newConfig = try { json.fromJson(body, ApiConfig::class.java) } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Invalid config"))
            }
            store.saveConfig(newConfig)

            // 处理探测器模式
            if (newConfig.streamEnabled) {
                AICaptureService.setInspectorMode(true)
            }

            return jsonResponse(Response.Status.OK,
                ApiResponse(success = true, message = "Config updated", data = newConfig))
        }

        private fun handleAdbCommand(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>(); session.parseBody(files)
            val body = files["postData"] ?: ""
            val data = try { json.fromJson(body, Map::class.java) } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Invalid JSON"))
            }
            val cmd = data["command"]?.toString() ?: return jsonResponse(Response.Status.BAD_REQUEST,
                ApiResponse(success = false, message = "Missing 'command'"))
            val result = adbHelper.executeCommand(cmd)
            return jsonResponse(Response.Status.OK, ApiResponse(success = result.success,
                message = if (result.success) "OK" else "Failed",
                data = mapOf("stdout" to result.stdout, "stderr" to result.stderr, "exitCode" to result.exitCode)))
        }

        private fun handleAdbDevices(): Response = jsonResponse(Response.Status.OK,
            ApiResponse(success = true, message = "Devices", data = mapOf("devices" to adbHelper.getDevices())))

        private fun handleAdbShell(session: IHTTPSession): Response {
            val files = mutableMapOf<String, String>(); session.parseBody(files)
            val body = files["postData"] ?: ""
            val data = try { json.fromJson(body, Map::class.java) } catch (_: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, ApiResponse(success = false, message = "Invalid JSON"))
            }
            val cmd = data["command"]?.toString() ?: return jsonResponse(Response.Status.BAD_REQUEST,
                ApiResponse(success = false, message = "Missing 'command'"))
            val r = adbHelper.executeShell(cmd)
            return jsonResponse(Response.Status.OK, ApiResponse(success = r.success,
                message = if (r.success) "OK" else "Failed",
                data = mapOf("stdout" to r.stdout, "stderr" to r.stderr, "exitCode" to r.exitCode)))
        }

        private fun handleIndex(): Response {
            val html = """
<!DOCTYPE html><html><head><title>ApiAPK v2</title>
<style>
body{font-family:-apple-system,sans-serif;max-width:860px;margin:40px auto;padding:20px;background:#0d1117;color:#c9d1d9}
h1{color:#ff7b72}h2{color:#58a6ff;border-bottom:1px solid #21262d;padding-bottom:8px}
code{background:#161b22;padding:2px 6px;border-radius:4px;color:#79c0ff;font-family:monospace;font-size:14px}
pre{background:#161b22;padding:16px;border-radius:8px;overflow-x:auto;color:#79c0ff;line-height:1.5}
.ep{margin:6px 0;padding:8px 12px;background:#161b22;border-radius:6px;border-left:3px solid #238636}
.m{color:#ff7b72;font-weight:bold;display:inline-block;width:60px}
.badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:12px;font-weight:bold}
.badge-new{background:#238636;color:#fff}
.badge-stream{background:#a371f7;color:#fff}
</style></head><body>
<h1>ApiAPK Server v2.0</h1>
<p>AI对话捕获 + 流式输出 + OpenAI兼容 API | 后台持久运行 | 分屏支持</p>

<h2>📡 API端点</h2>
<div class="ep"><span class="m">GET</span><code>/api/status</code></div>
<div class="ep"><span class="m">GET</span><code>/api/conversations</code> <span class="badge">分页</span></div>
<div class="ep"><span class="m">GET</span><code>/api/conversations/{deepseek|doubao|xiaoai}</code></div>
<div class="ep"><span class="m">POST</span><code>/api/send/{app}</code> {"message":"..."}</div>
<div class="ep"><span class="m">GET</span><code>/api/stats</code></div>

<h2>🌊 流式输出 <span class="badge badge-new">NEW</span></h2>
<p>AI模型逐token输出时，通过SSE实时推送增量（delta）。</p>
<div class="ep"><span class="m">GET</span><code>/api/stream</code> <span class="badge badge-stream">SSE</span> 所有应用</div>
<div class="ep"><span class="m">GET</span><code>/api/stream/deepseek</code> <span class="badge badge-stream">SSE</span> 指定应用</div>

<h2>🔬 UI探测器 <span class="badge badge-new">NEW</span></h2>
<p>Dump当前窗口的完整UI节点树，用于调试和发现控件ID。</p>
<div class="ep"><span class="m">GET</span><code>/api/ui/dump</code> 完整节点树</div>
<div class="ep"><span class="m">GET</span><code>/api/ui/snapshot</code> 文本快照</div>

<h2>🤖 OpenAI兼容 <span class="badge badge-new">NEW</span></h2>
<p>可直接用 OpenAI SDK 连接，支持 stream=true 流式。</p>
<div class="ep"><span class="m">GET</span><code>/v1/models</code></div>
<div class="ep"><span class="m">POST</span><code>/v1/chat/completions</code> {"model":"deepseek","messages":[...],"stream":true}</div>

<h2>⚡ 使用示例</h2>
<pre>
# 查看状态
curl http://PHONE_IP:8765/api/status

# 流式接收DeepSeek的输出（SSE）
curl -N http://PHONE_IP:8765/api/stream/deepseek

# 探测当前UI节点树（调试用）
curl http://PHONE_IP:8765/api/ui/dump

# 用OpenAI格式发消息（流式）
curl -N http://PHONE_IP:8765/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek","messages":[{"role":"user","content":"你好"}],"stream":true}'
</pre>

<h2>📱 分屏模式</h2>
<p>AccessibilityService 天然支持多窗口。分屏运行豆包+DeepSeek时，两个APP的事件都会被捕获。
通过 <code>/api/stream</code> 可以同时接收两个APP的流式输出（通过 <code>app</code> 字段区分）。</p>

<h2>🔄 后台运行</h2>
<p>ApiAPK 使用前台服务 + WakeLock + 电池优化白名单 三重保障后台存活。
AI应用需要保持可见（前台/分屏/悬浮窗），因为无障碍服务只能读取可见窗口的内容。</p>

</body></html>""".trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun jsonResponse(status: Response.Status, data: Any): Response {
            return newFixedLengthResponse(status, "application/json; charset=utf-8", json.toJson(data))
        }
    }
}
