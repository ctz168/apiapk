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
 * AI捕获无障碍服务 v4 - 全量文本追踪模式。
 *
 * v4 核心改动：
 *   - 不再用启发式规则过滤"是不是AI回复"，而是追踪所有非输入框文本
 *   - 每个 APP 维护一个"文本全文快照"，直接比对增量
 *   - 新出现的文本 = 用户输入（editable）或 AI 回复（非 editable）
 *   - 最低门槛：只要有文本变化就记录
 *
 * 为什么 v3 的启发式规则不行？
 *   DeepSeek/豆包的UI用的是 WebView/自定义View，className 不一定是 "TextView"
 *   要求 text.length > 10 会漏掉短回复
 *   要求非 clickable 但 AI 气泡容器可能是 clickable 的
 *
 * v4 策略：
 *   editable 的变化 → 用户输入
 *   非 editable 的新增文本 → AI 回复候选
 *   只排除：hint 文本、超短纯数字文本（UI元素）
 */
class AICaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "AICaptureService"
        private var conversationStore: ConversationStore? = null
        private var captureEnabled = false  // 默认关闭，需要用户主动开启

        /** 每个 APP 上一次的完整文本集合（用于检测增量） */
        private val prevTextSet = ConcurrentHashMap<String, MutableSet<String>>()

        /** 每个 APP 上一次检测到的最大文本（用于流式增量跟踪） */
        private val lastMaxText = ConcurrentHashMap<String, String>()

        /** 各APP的当前流式会话ID */
        private val streamSessionIds = ConcurrentHashMap<String, String>()

        private var inspectorMode = true  // 默认开启探测器模式，方便调试
        private var lastDumpedTree = ""
        private var lastDumpedTime = 0L

        // 目标AI应用包名
        private val TARGET_PACKAGES = setOf(
            AIConversation.AppType.DEEPSEEK.packageName,
            AIConversation.AppType.DOUBAO.packageName,
            AIConversation.AppType.XIAOAI.packageName
        )

        // 自动测试
        private val firstDetectedApps = ConcurrentHashMap<String, Boolean>()
        private val testSentApps = ConcurrentHashMap<String, Boolean>()
        private val replyCapturedApps = ConcurrentHashMap<String, Boolean>()
        private var adbHelper: AdbHelper? = null
        const val TEST_MESSAGE = "你好"

        // 事件统计
        @Volatile private var totalEventsReceived = 0
        @Volatile private var totalEventsFromTarget = 0
        @Volatile private var totalTextChanges = 0

        fun setConversationStore(store: ConversationStore) {
            conversationStore = store
            Log.i(TAG, "conversationStore set: ${store != null}")
        }

        fun setCaptureEnabled(enabled: Boolean) {
            val old = captureEnabled
            captureEnabled = enabled
            Log.i(TAG, "captureEnabled: $old -> $enabled")
        }

        fun isServiceRunning(): Boolean = conversationStore != null

        fun setInspectorMode(enabled: Boolean) { inspectorMode = enabled }
        fun getLastDumpedTree(): String = lastDumpedTree

        fun getDebugInfo(): Map<String, Any> = mapOf(
            "captureEnabled" to captureEnabled,
            "conversationStore" to (conversationStore != null),
            "totalEventsReceived" to totalEventsReceived,
            "totalEventsFromTarget" to totalEventsFromTarget,
            "totalTextChanges" to totalTextChanges,
            "prevTextSet_keys" to prevTextSet.keys.toList(),
            "lastMaxText_keys" to lastMaxText.keys.toList(),
            "inspectorMode" to inspectorMode
        )

        fun resetAutoTest() {
            firstDetectedApps.clear()
            testSentApps.clear()
            replyCapturedApps.clear()
            prevTextSet.clear()
            lastMaxText.clear()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "=== AICaptureService v4 CONNECTED ===")

        conversationStore = ConversationStore(this)
        adbHelper = AdbHelper()

        val info = AccessibilityServiceInfo().apply {
            // 监听所有事件类型（不遗漏任何变化）
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            notificationTimeout = 100  // 100ms，足够快以捕获流式
        }

        serviceInfo = info
        Log.i(TAG, "Service configured: ALL_MASK events, 100ms timeout")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        totalEventsReceived++

        if (!captureEnabled) return
        if (conversationStore == null) return

        val packageName = event.packageName?.toString() ?: return

        // 记录所有来自目标APP的事件
        if (packageName in TARGET_PACKAGES) {
            totalEventsFromTarget++
        } else {
            return  // 不是目标APP，跳过
        }

        val appType = AIConversation.AppType.fromPackage(packageName) ?: return
        val appId = appType.identifier

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleFirstDetection(appType, appId, packageName)
                processContentChange(event, appType, appId, packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "[$appId] WINDOW_STATE_CHANGED")
                // 新窗口 → 重置追踪（但不清空lastMaxText，避免丢失正在进行的流式）
                prevTextSet.remove(packageName)
                handleFirstDetection(appType, appId, packageName)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d(TAG, "[$appId] CLICK on: ${event.source?.className}")
            }
        }
    }

    // ========== 自动测试 ==========

    private fun handleFirstDetection(appType: AIConversation.AppType, appId: String, packageName: String) {
        if (firstDetectedApps[appId] == true) return
        firstDetectedApps[appId] = true

        Log.i(TAG, "[$appId] ★ 首次检测到 ${appType.displayName}！")
        showCaptureNotification("🔍 检测到 ${appType.displayName}", "正在准备发送测试消息...")

        Thread({
            try {
                Thread.sleep(3000)
                Log.i(TAG, "[$appId] 自动发送: \"$TEST_MESSAGE\"")
                showCaptureNotification("🚀 ${appType.displayName}", "正在发送: $TEST_MESSAGE")

                val helper = adbHelper ?: AdbHelper()
                val sent = helper.sendViaAdb(appType, TEST_MESSAGE)
                testSentApps[appId] = true

                if (sent) {
                    Log.i(TAG, "[$appId] 测试消息已发送")
                    showCaptureNotification("⏳ ${appType.displayName}", "已发送，等待回复...")
                } else {
                    Log.w(TAG, "[$appId] 发送失败")
                    showCaptureNotification("⚠️ ${appType.displayName}", "发送失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$appId] 自动测试失败: ${e.message}")
            }
        }, "auto-test-$appId").apply { isDaemon = true; start() }
    }

    private fun showCaptureNotification(title: String, text: String) {
        try {
            val channelId = "apiapk_capture_status"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "ApiAPK 捕获", NotificationManager.IMPORTANCE_DEFAULT)
                channel.description = "捕获状态"
                channel.enableVibration(false)
                channel.setSound(null, null)
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
                    .setContentTitle(title).setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setAutoCancel(true).build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle(title).setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setAutoCancel(true).setPriority(Notification.PRIORITY_DEFAULT).build()
            }
            getSystemService(NotificationManager::class.java).notify(2001, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Notification error: ${e.message}")
        }
    }

    // ========== 核心捕获：全量文本追踪 ==========

    private fun processContentChange(
        event: AccessibilityEvent,
        appType: AIConversation.AppType,
        appId: String,
        packageName: String
    ) {
        val rootNode = rootInActiveWindow ?: return

        try {
            // 1. 收集当前窗口所有文本节点
            val currentTexts = mutableSetOf<String>()
            val editableTexts = mutableListOf<String>()
            val nonEditableTexts = mutableListOf<Pair<String, Int>>() // text -> depth

            collectAllText(rootNode, currentTexts, editableTexts, nonEditableTexts, 0)

            // 2. 更新探测器 dump（每5秒最多一次，避免卡顿）
            val now = System.currentTimeMillis()
            if (inspectorMode && now - lastDumpedTime > 5000) {
                lastDumpedTree = formatNodeTree(rootNode, 0)
                lastDumpedTime = now
            }

            // 3. 检测用户输入（editable 且文本变化）
            val prevTexts = prevTextSet[packageName] ?: emptySet()
            for (text in editableTexts) {
                val trimmed = text.trim()
                if (trimmed.length >= 1 && trimmed !in prevTexts) {
                    Log.i(TAG, "[$appId] 📝 用户输入: '$trimmed'")
                    conversationStore?.getOrAddMessage(appType, "user", trimmed)
                }
            }

            // 4. 检测 AI 回复（非 editable 的新增/变化文本）
            val currentNonEditableSet = nonEditableTexts.map { it.first }.toSet()
            val prevNonEditableSet = prevTexts.filter { it !in editableTexts.map { t -> t.trim() } }.toSet()

            // 找出新增的非editable文本
            val newTexts = currentNonEditableSet - prevNonEditableSet
            // 找出长度变化的非editable文本
            val prevNonEditable = (prevTexts - editableTexts.map { it.trim() }).filter { it.length > 2 }.toSet()

            // 方法A：找新增文本
            for (text in newTexts) {
                if (text.length > 2 && isNotUIElement(text)) {
                    Log.d(TAG, "[$appId] 新增文本 (${text.length}字): '${text.take(80)}'")
                    totalTextChanges++
                }
            }

            // 方法B：直接找最长的非editable文本作为AI回复（流式跟踪）
            // 这是最可靠的方法：AI回复通常是屏幕上最长的非输入文本
            if (nonEditableTexts.isNotEmpty()) {
                // 找最长的文本（很可能是AI的完整回复）
                val longest = nonEditableTexts.maxByOrNull { it.first.length }
                if (longest != null && longest.first.length > 2) {
                    val prevLongest = lastMaxText[packageName] ?: ""

                    if (longest.first != prevLongest) {
                        if (longest.first.length > prevLongest.length) {
                            // 文本变长了 → 流式输出增量
                            val delta = longest.first.substring(prevLongest.length)
                            Log.i(TAG, "[$appId] 🤖 AI回复增量 +${delta.length}字: '${delta.take(50)}'")

                            val sessionId = streamSessionIds.getOrPut(appId) { UUID.randomUUID().toString() }
                            StreamEventBus.pushDelta(
                                StreamDelta.textDelta(sessionId, appId, "assistant", delta, longest.first)
                            )
                            conversationStore?.updateLastAssistantMessage(appType, longest.first)
                            BackgroundMonitorService.recordAssistantActivity(appId)

                            // 自动测试：检测到回复
                            if (testSentApps[appId] == true && replyCapturedApps[appId] != true) {
                                replyCapturedApps[appId] = true
                                val preview = longest.first.take(50)
                                Log.i(TAG, "[$appId] ✅ 成功捕获 ${appType.displayName} 回复!")
                                showCaptureNotification("✅ 成功捕获 ${appType.displayName}", "回复: $preview...")
                            }
                        } else if (longest.first.length < prevLongest.length * 0.5 && prevLongest.length > 20) {
                            // 文本大幅缩短 → 新对话开始
                            if (prevLongest.isNotEmpty()) {
                                Log.d(TAG, "[$appId] 文本重置 (新对话)")
                                val sessionId = streamSessionIds.getOrPut(appId) { UUID.randomUUID().toString() }
                                StreamEventBus.pushDelta(StreamDelta.finish(sessionId, appId, "assistant", prevLongest))
                                conversationStore?.getOrAddMessage(appType, "assistant", prevLongest)
                                streamSessionIds[appId] = UUID.randomUUID().toString()
                            }
                        }

                        lastMaxText[packageName] = longest.first
                    }
                }
            }

            // 5. 更新快照
            prevTextSet[packageName] = currentTexts.toMutableSet()

        } catch (e: Exception) {
            Log.e(TAG, "[$appId] processContentChange error: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 递归收集所有文本节点。
     * 分两类收集：editable（输入框）和非 editable（显示文本）。
     */
    private fun collectAllText(
        node: AccessibilityNodeInfo,
        allTexts: MutableSet<String>,
        editableTexts: MutableList<String>,
        nonEditableTexts: MutableList<Pair<String, Int>>,
        depth: Int
    ) {
        if (node == null || depth > 40) return

        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""

        if (text.isNotEmpty()) {
            allTexts.add(text)
            if (node.isEditable) {
                editableTexts.add(text)
            } else {
                nonEditableTexts.add(Pair(text, depth))
            }
        }

        // contentDescription 也可能有信息（图片按钮的文字说明）
        // 但不作为AI回复，因为太短

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllText(child, allTexts, editableTexts, nonEditableTexts, depth + 1)
            }
        }
    }

    /**
     * 判断文本是否是 UI 元素（而非AI回复内容）。
     * 只排除明显的UI标签。
     */
    private fun isNotUIElement(text: String): Boolean {
        // 排除纯数字（可能是时间戳、计数器等UI元素）
        if (text.matches(Regex("^\\d+(\\.\\d+)?$"))) return false
        // 排除单个字符
        if (text.length <= 1) return false
        // 排除常见的UI文本
        val uiTexts = setOf("发送", "取消", "确定", "复制", "删除", "重新生成", "停止生成",
            "DeepSeek", "豆包", "小爱同学", "新建对话", "清空", "更多", "设置",
            "搜索", "发送消息", "输入消息", "请输入", "Type a message")
        if (text.trim() in uiTexts) return false
        return true
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
        val clickable = if (node.isClickable) "[CLK]" else ""

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
        Log.w(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        Log.i(TAG, "=== AICaptureService v4 DESTROYED ===")
        prevTextSet.clear()
        lastMaxText.clear()
        streamSessionIds.clear()
        firstDetectedApps.clear()
        testSentApps.clear()
        replyCapturedApps.clear()
        super.onDestroy()
    }
}
