package com.apiapk.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.apiapk.model.AIConversation
import com.apiapk.model.StreamDelta
import com.apiapk.model.StreamEventBus
import com.apiapk.model.ConversationStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AI捕获无障碍服务 v2 - 完全重写的智能捕获引擎。
 *
 * 设计哲学：
 *   不硬编码任何 resource-id（因为每个APP版本都可能变），
 *   而是通过**节点树遍历 + 启发式规则**自动发现聊天区域。
 *
 * 核心机制：
 *   1. 监听所有目标APP的窗口变化事件
 *   2. 遍历完整的UI节点树（深度优先），发现所有有文本内容的节点
 *   3. 通过启发式规则判断哪些是"用户输入框"、"AI回复气泡"、"发送按钮"
 *   4. 跟踪文本变化，计算出增量delta，推送到StreamEventBus（SSE流式输出）
 *   5. 支持分屏模式：AccessibilityService天然支持多窗口，分屏时两个APP的事件都会收到
 *
 * 启发式规则（不依赖resource-id）：
 *   - 用户输入框：EditText + isEditable + 在屏幕底部1/3区域
 *   - AI回复气泡：TextView/ScrollView内的文本 + 非可编辑 + 文本长度>5 + 在聊天区域
 *   - 发送按钮：Button/ImageButton + 在输入框附近 + 包含"发送"/"send"等文本或desc
 *   - 新消息检测：对比前后两次节点树的文本快照，找出新增/变化的文本
 *
 * 关于分屏：
 *   Android的AccessibilityService在分屏模式下仍然能收到两个APP的事件。
 *   TYPE_WINDOW_CONTENT_CHANGED 和 TYPE_WINDOW_STATE_CHANGED 事件
 *   会分别来自两个APP的窗口，通过 packageName 区分即可。
 */
class AICaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "AICaptureService"
        private var conversationStore: ConversationStore? = null
        private var captureEnabled = true

        /** 当前正在跟踪的各APP的文本快照 - key: "packageName", value: 节点文本列表 */
        private val textSnapshots = ConcurrentHashMap<String, List<TextNodeInfo>>()

        /** 各APP最后一次检测到的assistant输出全文（用于计算delta） */
        private val lastAssistantText = ConcurrentHashMap<String, String>()

        /** 各APP的当前流式会话ID */
        private val streamSessionIds = ConcurrentHashMap<String, String>()

        /** UI探测器模式 - 开启后会通过API暴露完整的节点树 */
        private var inspectorMode = false
        private var lastDumpedTree = ""

        // 目标AI应用
        private val TARGET_PACKAGES = setOf(
            AIConversation.AppType.DEEPSEEK.packageName,
            AIConversation.AppType.DOUBAO.packageName,
            AIConversation.AppType.XIAOAI.packageName
        )

        // 已知的用户输入框线索（用于启发式匹配，不要求精确匹配resource-id）
        private val INPUT_HINTS = listOf("input", "edit", "chat_input", "message_input", "text_editor", "compose")
        private val SEND_HINTS = listOf("send", "发送", "submit", "go")
        private val AI_BUBBLE_HINTS = listOf("ai_response", "reply", "answer", "message", "bubble", "chat_item", "response", "bot")

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
    }

    /**
     * 文本节点信息 - 记录UI节点树中每个有文本的节点的关键信息。
     * 用于前后对比，检测新增/变化的文本。
     */
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
        Log.i(TAG, "AI Capture Service v2 connected")

        conversationStore = ConversationStore(this)

        val info = AccessibilityServiceInfo().apply {
            // 监听所有相关事件类型
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

            notificationTimeout = 150 // 150ms - 足够快以捕获流式输出
            // 不过滤包名，这样可以支持未来添加更多APP
            // packageNames = TARGET_PACKAGES.toTypedArray()
        }

        serviceInfo = info
        Log.i(TAG, "Accessibility service v2 configured (heuristic mode, no hardcoded IDs)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !captureEnabled) return
        if (conversationStore == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in TARGET_PACKAGES) return

        val appType = AIConversation.AppType.fromPackage(packageName) ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                processWindowChange(event, appType, packageName)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                processTextChange(event, appType, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "Window state changed: $packageName")
                // 新窗口出现，清空该APP的文本快照
                textSnapshots.remove(packageName)
                lastAssistantText.remove(packageName)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                processClickEvent(event, appType, packageName)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                processFocusEvent(event, appType, packageName)
            }
        }
    }

    /**
     * 处理窗口内容变化 - 核心方法。
     *
     * 策略：
     *   1. 遍历当前窗口的完整节点树
     *   2. 与上一次的快照对比，找出新增/变化的文本
     *   3. 通过启发式规则判断变化的是用户输入还是AI回复
     *   4. 计算delta并推送到事件总线
     */
    private fun processWindowChange(
        event: AccessibilityEvent,
        appType: AIConversation.AppType,
        packageName: String
    ) {
        val rootNode = rootInActiveWindow ?: return
        val currentNodes = mutableListOf<TextNodeInfo>()

        try {
            // 深度遍历整个节点树
            collectTextNodes(rootNode, currentNodes, 0)

            if (inspectorMode) {
                lastDumpedTree = formatNodeTree(rootNode, 0)
            }

            // 和上一次的快照对比
            val previousNodes = textSnapshots[packageName] ?: emptyList()
            detectTextChanges(previousNodes, currentNodes, appType, packageName)

            // 更新快照
            textSnapshots[packageName] = currentNodes

        } catch (e: Exception) {
            Log.e(TAG, "Error processing window change for $packageName: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 递归收集所有包含文本的节点信息。
     * 不做任何过滤，完整记录整个UI树。
     */
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

        // 记录所有有文本内容的节点
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

        // 递归遍历子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectTextNodes(child, result, depth + 1)
            }
        }
    }

    /**
     * 对比前后两次文本快照，检测变化。
     *
     * 启发式判断规则：
     *   - isEditable = true → 用户输入框
     *   - isEditable = false + className包含"Text" + 文本长度>5 + 在上半屏 → 可能是AI回复
     *   - 通过同位置文本长度变化检测流式输出的增量
     */
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
                    // 文本变长了 → AI在流式输出！
                    // 计算增量delta
                    val delta = currentText.substring(prevText.length)

                    Log.d(TAG, "[$appId] Stream delta: +'$delta' (total: ${currentText.length})")

                    // 推送到流式事件总线
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

                    // 存储到会话（只在完成时存一次完整的）
                    // 流式过程中持续更新
                    conversationStore?.updateLastAssistantMessage(appType, currentText)

                    // 记录活动时间（供后台监控服务判断超时）
                    BackgroundMonitorService.recordAssistantActivity(appId)

                    // 检测流式输出是否结束（文本不再变化会在下次事件中体现）
                    lastAssistantText[packageName] = currentText
                } else if (currentText != prevText && currentText.length < prevText.length) {
                    // 文本变短了 → 可能是新的一轮对话开始了
                    Log.d(TAG, "[$appId] Text reset detected (new conversation?)")
                    if (prevText.isNotEmpty()) {
                        // 上一轮结束，发送finish
                        val sessionId = streamSessionIds.getOrPut(appId) { UUID.randomUUID().toString() }
                        StreamEventBus.pushDelta(
                            StreamDelta.finish(sessionId, appId, "assistant", prevText)
                        )
                        // 最终保存上一轮的完整消息
                        conversationStore?.getOrAddMessage(appType, "assistant", prevText)
                    }
                    // 开始新一轮
                    streamSessionIds[appId] = UUID.randomUUID().toString()
                    lastAssistantText[packageName] = currentText
                }
                // 文本没变 → AI输出暂停或结束（由BackgroundMonitorService的超时机制发送finish）
            }
        }

        // 检查之前有文本但现在消失了的节点 → 可能是上一轮对话被清除
        for (prevNode in previous) {
            if (prevNode.text.isEmpty() || prevNode.isEditable) continue
            val stillExists = current.any {
                !it.isEditable && it.text == prevNode.text
            }
            if (!stillExists && prevNode.text.length > 10) {
                // 消失了的长文本，可能是AI回复被清除（新对话开始）
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

    /**
     * 处理文本变化事件（TYPE_VIEW_TEXT_CHANGED）
     * 通常在用户正在输入时触发，比WINDOW_CONTENT_CHANGED更精准。
     */
    private fun processTextChange(
        event: AccessibilityEvent,
        appType: AIConversation.AppType,
        packageName: String
    ) {
        val text = event.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        val className = event.className?.toString() ?: ""
        val isEditText = className.contains("EditText", ignoreCase = true)

        // 从事件来源获取更多信息
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

    /**
     * 处理点击事件 - 检测发送按钮点击。
     * 当用户点击发送按钮时，确认当前输入框的内容为最终用户消息。
     */
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

            // 检测是否是发送按钮
            val isSendButton = source.isClickable && (
                SEND_HINTS.any { hint ->
                    text.contains(hint, ignoreCase = true) ||
                    contentDesc.contains(hint, ignoreCase = true) ||
                    viewId.contains(hint, ignoreCase = true)
                } ||
                // ImageView + clickable + 在底部区域 可能是发送图标按钮
                (className.contains("Image", ignoreCase = true) && source.isClickable &&
                    isNearBottom(source, 0.75f))
            )

            if (isSendButton) {
                Log.d(TAG, "[${appType.identifier}] Send button clicked")
                // 输入框的内容已经在processWindowChange中被捕获了
                // 这里只需要记录发送事件
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing click: ${e.message}")
        } finally {
            source.recycle()
        }
    }

    /**
     * 处理焦点变化 - 用户切换到输入框时记录。
     */
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

    /**
     * 判断一个节点是否可能是用户输入框。
     * 不依赖精确的resource-id，而是综合多个信号判断。
     */
    private fun isLikelyInputBox(node: TextNodeInfo): Boolean {
        // 1. EditText类名
        if (node.className.contains("EditText", ignoreCase = true)) return true

        // 2. viewId包含输入相关的关键词
        if (node.viewId != null) {
            if (INPUT_HINTS.any { node.viewId.contains(it, ignoreCase = true) }) return true
        }

        // 3. 可编辑 + 有hint文本
        if (node.isEditable && node.hint != null) return true

        // 4. 可编辑 + 在屏幕底部区域
        if (node.isEditable && node.boundsInScreen != null) {
            val screenMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(screenMetrics)
            val bottomRatio = node.boundsInScreen.bottom.toFloat() / screenMetrics.heightPixels
            if (bottomRatio > 0.75f) return true
        }

        return false
    }

    /**
     * 判断一个节点是否可能是AI回复气泡。
     *
     * 策略：
     *   - 不可编辑的文本节点
     *   - 不像按钮/标题
     *   - 文本长度 > 5（排除短标签）
     *   - 在屏幕中上区域（排除底部输入区）
     *   - 可选：viewId包含回复相关关键词
     */
    private fun isLikelyAIResponse(node: TextNodeInfo, screenHeight: Int): Boolean {
        // 必须有实际文本
        if (node.text.length <= 5) return false

        // 不能是可编辑的（那是输入框）
        if (node.isEditable) return false

        // 不能是按钮
        if (node.isClickable) return false

        // 排除短文本标签（如"发送"、"复制"等）
        if (node.text.length <= 10 && node.isClickable) return false

        // 排除hint文本
        if (node.text == node.hint) return false

        // 排除标题栏（通常在顶部，depth很浅）
        if (node.depth <= 2 && node.boundsInScreen != null && node.boundsInScreen.top < screenHeight * 0.1f) {
            return false
        }

        // 位置在屏幕底部1/4区域 → 大概率是输入框附近，不是AI回复
        if (node.boundsInScreen != null) {
            val bottomRatio = node.boundsInScreen.bottom.toFloat() / screenHeight
            if (bottomRatio > 0.92f) return false
        }

        // viewId包含回复相关关键词 → 强信号
        if (node.viewId != null) {
            if (AI_BUBBLE_HINTS.any { node.viewId.contains(it, ignoreCase = true) }) return true
        }

        // className 包含 Text/TextView → 很可能是文本展示区域
        if (node.className.contains("Text", ignoreCase = true) || node.className.contains("TextView", ignoreCase = true)) {
            // 在聊天区域内（屏幕10%-90%范围）且文本较长
            if (node.text.length > 10) return true
        }

        return false
    }

    /**
     * 判断节点是否在屏幕底部附近。
     */
    private fun isNearBottom(node: AccessibilityNodeInfo, threshold: Float): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val screenMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(screenMetrics)
        return rect.bottom.toFloat() / screenMetrics.heightPixels > threshold
    }

    // ========== UI探测器 ==========

    /**
     * 格式化节点树为可读字符串（用于UI探测器/API调试）。
     */
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
        // 清理所有状态
        textSnapshots.clear()
        lastAssistantText.clear()
        streamSessionIds.clear()
        Log.i(TAG, "AI Capture Service v2 destroyed")
        super.onDestroy()
    }
}
