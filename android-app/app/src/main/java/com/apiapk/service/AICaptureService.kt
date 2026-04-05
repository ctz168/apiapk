package com.apiapk.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.apiapk.model.AIConversation
import com.apiapk.model.StreamDelta
import com.apiapk.model.StreamEventBus
import com.apiapk.model.ConversationStore
import com.apiapk.util.AdbHelper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AI捕获无障碍服务 v3 - 自动化测试集成。
 *
 * v3 新增：
 *   - 首次检测到目标APP窗口时，自动发送"你好"进行测试
 *   - 捕获到AI回复后，通过通知栏显示"成功捕获 xxx"
 *   - 自动启动目标APP（由BackgroundMonitorService触发）
 *
 * 核心机制：
 *   1. 监听所有目标APP的窗口变化事件
 *   2. 遍历完整的UI节点树（深度优先），发现所有有文本内容的节点
 *   3. 通过启发式规则判断哪些是"用户输入框"、"AI回复气泡"、"发送按钮"
 *   4. 跟踪文本变化，计算出增量delta，推送到StreamEventBus（SSE流式输出）
 *   5. 支持分屏模式
 *   6. 首次检测到APP时自动发送测试消息
 */
class AICaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "AICaptureService"
        private var conversationStore: ConversationStore? = null
        private var captureEnabled = true

        /** 当前正在跟踪的各APP的文本快照 */
        private val textSnapshots = ConcurrentHashMap<String, List<TextNodeInfo>>()

        /** 各APP最后一次检测到的assistant输出全文 */
        private val lastAssistantText = ConcurrentHashMap<String, String>()

        /** 各APP的当前流式会话ID */
        private val streamSessionIds = ConcurrentHashMap<String, String>()

        /** UI探测器模式 */
        private var inspectorMode = false
        private var lastDumpedTree = ""

        // 目标AI应用
        private val TARGET_PACKAGES = setOf(
            AIConversation.AppType.DEEPSEEK.packageName,
            AIConversation.AppType.DOUBAO.packageName,
            AIConversation.AppType.XIAOAI.packageName
        )

        private val INPUT_HINTS = listOf("input", "edit", "chat_input", "message_input", "text_editor", "compose")
        private val SEND_HINTS = listOf("send", "发送", "submit", "go")
        private val AI_BUBBLE_HINTS = listOf("ai_response", "reply", "answer", "message", "bubble", "chat_item", "response", "bot")

        // ========== 自动测试相关 ==========

        /** 记录哪些APP已经被首次检测到（用于自动发送你好） */
        private val firstDetectedApps = ConcurrentHashMap<String, Boolean>()

        /** 记录哪些APP已经发送了测试消息 */
        private val testSentApps = ConcurrentHashMap<String, Boolean>()

        /** 记录哪些APP已经捕获到了回复 */
        private val replyCapturedApps = ConcurrentHashMap<String, Boolean>()

        private var adbHelper: AdbHelper? = null

        /** 自动测试消息 */
        const val TEST_MESSAGE = "你好"

        fun setConversationStore(store: ConversationStore) {
            conversationStore = store
        }

        fun setCaptureEnabled(enabled: Boolean) {
            captureEnabled = enabled
        }

        fun isServiceRunning(): Boolean {
            return conversationStore != null
        }

        fun setInspectorMode(enabled: Boolean) {
            inspectorMode = enabled
        }

        fun getLastDumpedTree(): String = lastDumpedTree

        /** 重置首次检测状态（用于重新开始自动测试） */
        fun resetAutoTest() {
            firstDetectedApps.clear()
            testSentApps.clear()
            replyCapturedApps.clear()
            Log.i(TAG, "Auto test state reset")
        }
    }

    data class TextNodeInfo(
        val text: String,
        val className: String,
        val isEditable: Boolean,
        val isClickable: Boolean,
        val viewId: String?,
        val contentDesc: String?,
        val boundsInScreen: android.graphics.Rect?,
        val depth: Int,
        val hint: String?
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AI Capture Service v3 connected")

        conversationStore = ConversationStore(this)
        adbHelper = AdbHelper()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS

            notificationTimeout = 150
        }

        serviceInfo = info
        Log.i(TAG, "Accessibility service v3 configured (auto-test enabled)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !captureEnabled) return
        if (conversationStore == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in TARGET_PACKAGES) return

        val appType = AIConversation.AppType.fromPackage(packageName) ?: return
        val appId = appType.identifier

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 首次检测到APP窗口内容 → 标记为已检测
                handleFirstDetection(appType, appId, packageName)

                processWindowChange(event, appType, packageName)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleFirstDetection(appType, appId, packageName)
                processTextChange(event, appType, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "Window state changed: $packageName")
                textSnapshots.remove(packageName)
                lastAssistantText.remove(packageName)

                // 窗口状态变化也可能是首次检测
                handleFirstDetection(appType, appId, packageName)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                processClickEvent(event, appType, packageName)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                processFocusEvent(event, appType, packageName)
            }
        }
    }

    // ========== 自动测试逻辑 ==========

    /**
     * 处理首次检测到APP的逻辑。
     * 当第一次检测到某个AI APP的UI事件时：
     *   1. 发送通知"检测到 xxx"
     *   2. 等待APP加载
     *   3. 自动发送"你好"测试消息
     */
    private fun handleFirstDetection(
        appType: AIConversation.AppType,
        appId: String,
        packageName: String
    ) {
        if (firstDetectedApps[appId] == true) return
        firstDetectedApps[appId] = true

        Log.i(TAG, "[$appId] 首次检测到 ${appType.displayName}！准备发送测试消息...")

        // 发送通知：检测到APP
        showCaptureNotification(
            "🔍 检测到 ${appType.displayName}",
            "正在准备发送测试消息..."
        )

        // 在后台线程延迟发送测试消息（等待APP完全加载）
        Thread({
            try {
                // 等待APP界面完全加载
                Thread.sleep(3000)

                Log.i(TAG, "[$appId] 自动发送测试消息: \"$TEST_MESSAGE\"")
                showCaptureNotification(
                    "🚀 ${appType.displayName}",
                    "正在发送测试消息: $TEST_MESSAGE"
                )

                val helper = adbHelper ?: AdbHelper()
                val sent = helper.sendViaAdb(appType, TEST_MESSAGE)
                testSentApps[appId] = true

                if (sent) {
                    Log.i(TAG, "[$appId] 测试消息已发送，等待AI回复...")
                    showCaptureNotification(
                        "⏳ ${appType.displayName}",
                        "测试消息已发送，等待AI回复..."
                    )
                } else {
                    Log.w(TAG, "[$appId] 测试消息发送失败")
                    showCaptureNotification(
                        "⚠️ ${appType.displayName}",
                        "测试消息发送失败，请检查ADB权限"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$appId] 自动测试失败: ${e.message}")
                showCaptureNotification(
                    "❌ ${appType.displayName}",
                    "自动测试失败: ${e.message}"
                )
            }
        }, "auto-test-$appId").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 显示捕获状态通知。
     */
    private fun showCaptureNotification(title: String, text: String) {
        try {
            val channelId = "apiapk_capture_status"
            val notificationId = 2001

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "ApiAPK 捕获状态",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "AI应用捕获状态通知"
                    setShowBadge(true)
                    enableVibration(false)
                    setSound(null, null)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setAutoCancel(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .build()
            }

            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification: ${e.message}")
        }
    }

    // ========== 核心捕获逻辑 ==========

    private fun processWindowChange(
        event: AccessibilityEvent,
        appType: AIConversation.AppType,
        packageName: String
    ) {
        val rootNode = rootInActiveWindow ?: return
        val currentNodes = mutableListOf<TextNodeInfo>()

        try {
            collectTextNodes(rootNode, currentNodes, 0)

            if (inspectorMode) {
                lastDumpedTree = formatNodeTree(rootNode, 0)
            }

            val previousNodes = textSnapshots[packageName] ?: emptyList()
            detectTextChanges(previousNodes, currentNodes, appType, packageName)

            textSnapshots[packageName] = currentNodes

        } catch (e: Exception) {
            Log.e(TAG, "Error processing window change for $packageName: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    private fun collectTextNodes(
        node: AccessibilityNodeInfo,
        result: MutableList<TextNodeInfo>,
        depth: Int
    ) {
        if (node == null || depth > 30) return

        val text = node.text?.toString()?.trim() ?: ""
        val hint = node.hintText?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName

        if (text.isNotEmpty() || hint.isNotEmpty() || contentDesc.isNotEmpty()) {
            result.add(TextNodeInfo(
                text = text,
                className = className,
                isEditable = node.isEditable,
                isClickable = node.isClickable,
                viewId = viewId,
                contentDesc = if (contentDesc.isNotEmpty()) contentDesc else null,
                boundsInScreen = if (text.isNotEmpty()) {
                    android.graphics.Rect().also { node.getBoundsInScreen(it) }
                } else null,
                depth = depth,
                hint = if (hint.isNotEmpty()) hint else null
            ))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectTextNodes(child, result, depth + 1)
            }
        }
    }

    private fun detectTextChanges(
        previous: List<TextNodeInfo>,
        current: List<TextNodeInfo>,
        appType: AIConversation.AppType,
        packageName: String
    ) {
        val appId = appType.identifier
        val screenMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(screenMetrics)
        val screenHeight = screenMetrics.heightPixels

        for (node in current) {
            if (node.text.isEmpty()) continue

            // === 检测用户输入 ===
            if (node.isEditable && isLikelyInputBox(node)) {
                val prevMatch = previous.find {
                    it.isEditable && isLikelyInputBox(it) && it.viewId == node.viewId
                }
                if (prevMatch == null || prevMatch.text != node.text) {
                    Log.d(TAG, "[$appId] User input detected: '${node.text.take(100)}'")
                    conversationStore?.getOrAddMessage(appType, "user", node.text)
                }
                continue
            }

            // === 检测AI回复（流式增量） ===
            if (!node.isEditable && isLikelyAIResponse(node, screenHeight)) {
                val prevText = lastAssistantText[packageName] ?: ""
                val currentText = node.text

                if (currentText != prevText && currentText.length > prevText.length) {
                    val delta = currentText.substring(prevText.length)
                    Log.d(TAG, "[$appId] Stream delta: +'$delta' (total: ${currentText.length})")

                    val sessionId = streamSessionIds.getOrPut(appId) { UUID.randomUUID().toString() }

                    StreamEventBus.pushDelta(
                        StreamDelta.textDelta(
                            id = sessionId,
                            app = appId,
                            role = "assistant",
                            delta = delta,
                            accumulated = currentText
                        )
                    )

                    conversationStore?.updateLastAssistantMessage(appType, currentText)
                    BackgroundMonitorService.recordAssistantActivity(appId)
                    lastAssistantText[packageName] = currentText

                    // 如果是自动测试且还没捕获到回复 → 显示通知
                    if (testSentApps[appId] == true && replyCapturedApps[appId] != true) {
                        replyCapturedApps[appId] = true
                        val preview = currentText.take(50)
                        Log.i(TAG, "[$appId] ✅ 成功捕获 ${appType.displayName} 的回复!")
                        showCaptureNotification(
                            "✅ 成功捕获 ${appType.displayName}",
                            "回复: $preview..."
                        )
                    }

                } else if (currentText != prevText && currentText.length < prevText.length) {
                    Log.d(TAG, "[$appId] Text reset detected (new conversation?)")
                    if (prevText.isNotEmpty()) {
                        val sessionId = streamSessionIds.getOrPut(appId) { UUID.randomUUID().toString() }
                        StreamEventBus.pushDelta(
                            StreamDelta.finish(sessionId, appId, "assistant", prevText)
                        )
                        conversationStore?.getOrAddMessage(appType, "assistant", prevText)
                    }
                    streamSessionIds[appId] = UUID.randomUUID().toString()
                    lastAssistantText[packageName] = currentText
                }
            }
        }

        // 检查之前有文本但现在消失了的节点
        for (prevNode in previous) {
            if (prevNode.text.isEmpty() || prevNode.isEditable) continue
            val stillExists = current.any {
                !it.isEditable && it.text == prevNode.text
            }
            if (!stillExists && prevNode.text.length > 10) {
                val lastText = lastAssistantText[packageName]
                if (lastText == prevNode.text) {
                    Log.d(TAG, "[$appId] Previous response disappeared, finalizing")
                    val sessionId = streamSessionIds.getOrPut(appId) { UUID.randomUUID().toString() }
                    StreamEventBus.pushDelta(
                        StreamDelta.finish(sessionId, appId, "assistant", prevNode.text)
                    )
                    lastAssistantText.remove(packageName)
                }
            }
        }
    }

    private fun processTextChange(
        event: AccessibilityEvent,
        appType: AIConversation.AppType,
        packageName: String
    ) {
        val text = event.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        val className = event.className?.toString() ?: ""
        val isEditText = className.contains("EditText", ignoreCase = true)

        val source = event.source
        if (source != null) {
            try {
                if (source.isEditable || isEditText) {
                    Log.d(TAG, "[${appType.identifier}] Text input: '$text'")
                    conversationStore?.getOrAddMessage(appType, "user", text)
                }
            } finally {
                source.recycle()
            }
        }
    }

    private fun processClickEvent(
        event: AccessibilityEvent,
        appType: AIConversation.AppType,
        packageName: String
    ) {
        val source = event.source ?: return

        try {
            val text = source.text?.toString()?.trim() ?: ""
            val contentDesc = source.contentDescription?.toString()?.trim() ?: ""
            val className = source.className?.toString() ?: ""
            val viewId = source.viewIdResourceName ?: ""

            val isSendButton = source.isClickable && (
                SEND_HINTS.any { hint ->
                    text.contains(hint, ignoreCase = true) ||
                    contentDesc.contains(hint, ignoreCase = true) ||
                    viewId.contains(hint, ignoreCase = true)
                } ||
                (className.contains("Image", ignoreCase = true) && source.isClickable &&
                    isNearBottom(source, 0.75f))
            )

            if (isSendButton) {
                Log.d(TAG, "[${appType.identifier}] Send button clicked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing click: ${e.message}")
        } finally {
            source.recycle()
        }
    }

    private fun processFocusEvent(
        event: AccessibilityEvent,
        appType: AIConversation.AppType,
        packageName: String
    ) {
        val source = event.source ?: return
        try {
            if (source.isEditable) {
                Log.d(TAG, "[${appType.identifier}] Input box focused (viewId: ${source.viewIdResourceName})")
            }
        } finally {
            source.recycle()
        }
    }

    // ========== 启发式判断方法 ==========

    private fun isLikelyInputBox(node: TextNodeInfo): Boolean {
        if (node.className.contains("EditText", ignoreCase = true)) return true
        if (node.viewId != null) {
            if (INPUT_HINTS.any { node.viewId.contains(it, ignoreCase = true) }) return true
        }
        if (node.isEditable && node.hint != null) return true
        if (node.isEditable && node.boundsInScreen != null) {
            val screenMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(screenMetrics)
            val bottomRatio = node.boundsInScreen.bottom.toFloat() / screenMetrics.heightPixels
            if (bottomRatio > 0.75f) return true
        }
        return false
    }

    private fun isLikelyAIResponse(node: TextNodeInfo, screenHeight: Int): Boolean {
        if (node.text.length <= 5) return false
        if (node.isEditable) return false
        if (node.isClickable) return false
        if (node.text.length <= 10 && node.isClickable) return false
        if (node.text == node.hint) return false
        if (node.depth <= 2 && node.boundsInScreen != null && node.boundsInScreen.top < screenHeight * 0.1f) {
            return false
        }
        if (node.boundsInScreen != null) {
            val bottomRatio = node.boundsInScreen.bottom.toFloat() / screenHeight
            if (bottomRatio > 0.92f) return false
        }
        if (node.viewId != null) {
            if (AI_BUBBLE_HINTS.any { node.viewId.contains(it, ignoreCase = true) }) return true
        }
        if (node.className.contains("Text", ignoreCase = true) || node.className.contains("TextView", ignoreCase = true)) {
            if (node.text.length > 10) return true
        }
        return false
    }

    private fun isNearBottom(node: AccessibilityNodeInfo, threshold: Float): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val screenMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(screenMetrics)
        return rect.bottom.toFloat() / screenMetrics.heightPixels > threshold
    }

    // ========== UI探测器 ==========

    private fun formatNodeTree(node: AccessibilityNodeInfo, depth: Int): String {
        if (node == null || depth > 25) return ""

        val indent = "  ".repeat(depth)
        val text = node.text?.toString()?.trim() ?: ""
        val hint = node.hintText?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val cls = node.className?.toString()?.split(".")?.lastOrNull() ?: "?"
        val viewId = node.viewIdResourceName ?: ""
        val editable = if (node.isEditable) "[EDIT]" else ""
        val clickable = if (node.isClickable) "[CLICK]" else ""

        val info = mutableListOf<String>()
        if (text.isNotEmpty()) info.add("text=\"$text\"")
        if (hint.isNotEmpty()) info.add("hint=\"$hint\"")
        if (desc.isNotEmpty()) info.add("desc=\"$desc\"")
        if (viewId.isNotEmpty()) info.add("id=$viewId")

        val line = "$indent$cls $editable$clickable ${if (info.isNotEmpty()) info.joinToString(" ") else "(empty)"}"

        val sb = StringBuilder()
        sb.append(line).append("\n")

        for (i in 0 until node.childCount) {
            sb.append(formatNodeTree(node.getChild(i), depth + 1))
        }

        return sb.toString()
    }

    override fun onInterrupt() {
        Log.w(TAG, "AI Capture Service interrupted")
    }

    override fun onDestroy() {
        textSnapshots.clear()
        lastAssistantText.clear()
        streamSessionIds.clear()
        firstDetectedApps.clear()
        testSentApps.clear()
        replyCapturedApps.clear()
        Log.i(TAG, "AI Capture Service v3 destroyed")
        super.onDestroy()
    }
}
