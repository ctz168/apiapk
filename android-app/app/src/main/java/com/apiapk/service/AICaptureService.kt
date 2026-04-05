package com.apiapk.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.apiapk.model.AIConversation
import com.apiapk.model.ConversationStore

/**
 * AI捕获无障碍服务 - 核心组件，通过Android无障碍服务监控DeepSeek、豆包、小爱同学的界面变化。
 * 当检测到目标AI应用的文本变化时，自动捕获用户输入和AI响应内容。
 * 服务会智能判断文本类型（用户输入/助手回复），并存储到ConversationStore中。
 */
class AICaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "AICaptureService"
        private var conversationStore: ConversationStore? = null
        private var captureEnabled = true

        // 目标AI应用的包名
        private val TARGET_PACKAGES = setOf(
            AIConversation.AppType.DEEPSEEK.packageName,
            AIConversation.AppType.DOUBAO.packageName,
            AIConversation.AppType.XIAOAI.packageName
        )

        // 各应用的UI特征标识符（用于定位聊天区域）
        private val APP_UI_SIGNATURES = mapOf(
            AIConversation.AppType.DEEPSEEK.packageName to UISelectors(
                chatContainerIds = listOf(
                    "com.deepseek.chat:id/chat_content",
                    "com.deepseek.chat:id/message_list",
                    "com.deepseek.chat:id/recycler_view"
                ),
                userInputIds = listOf(
                    "com.deepseek.chat:id/input_box",
                    "com.deepseek.chat:id/edit_text",
                    "com.deepseek.chat:id/chat_input"
                ),
                assistantOutputIds = listOf(
                    "com.deepseek.chat:id/ai_response",
                    "com.deepseek.chat:id/message_bubble"
                ),
                sendButtonIds = listOf(
                    "com.deepseek.chat:id/send_button",
                    "com.deepseek.chat:id/btn_send"
                )
            ),
            AIConversation.AppType.DOUBAO.packageName to UISelectors(
                chatContainerIds = listOf(
                    "com.larus.nova:id/chat_content",
                    "com.larus.nova:id/message_list",
                    "com.larus.nova:id/conversation_view"
                ),
                userInputIds = listOf(
                    "com.larus.nova:id/input_box",
                    "com.larus.nova:id/edit_text_input"
                ),
                assistantOutputIds = listOf(
                    "com.larus.nova:id/ai_reply",
                    "com.larus.nova:id/answer_view"
                ),
                sendButtonIds = listOf(
                    "com.larus.nova:id/send_btn",
                    "com.larus.nova:id/btn_send"
                )
            ),
            AIConversation.AppType.XIAOAI.packageName to UISelectors(
                chatContainerIds = listOf(
                    "com.miui.voiceassist:id/chat_container",
                    "com.miui.voiceassist:id/dialog_list"
                ),
                userInputIds = listOf(
                    "com.miui.voiceassist:id/voice_input",
                    "com.miui.voiceassist:id/text_input"
                ),
                assistantOutputIds = listOf(
                    "com.miui.voiceassist:id/ai_response",
                    "com.miui.voiceassist:id/answer_text"
                ),
                sendButtonIds = listOf(
                    "com.miui.voiceassist:id/send_btn"
                )
            )
        )

        fun setConversationStore(store: ConversationStore) {
            conversationStore = store
        }

        fun setCaptureEnabled(enabled: Boolean) {
            captureEnabled = enabled
        }

        fun isServiceRunning(): Boolean {
            return conversationStore != null
        }
    }

    data class UISelectors(
        val chatContainerIds: List<String>,
        val userInputIds: List<String>,
        val assistantOutputIds: List<String>,
        val sendButtonIds: List<String>
    )

    private var lastCapturedTexts = mutableMapOf<String, String>()
    private var currentDetectedApp: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AI Capture Service connected")

        conversationStore = ConversationStore(this)

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            notificationTimeout = 200
            packageNames = TARGET_PACKAGES.toTypedArray()
        }

        serviceInfo = info
        Log.i(TAG, "Accessibility service configured for packages: $TARGET_PACKAGES")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !captureEnabled) return
        if (conversationStore == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in TARGET_PACKAGES) return

        val appType = AIConversation.AppType.fromPackage(packageName) ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentDetectedApp = packageName
                Log.d(TAG, "Window changed for app: $packageName")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                currentDetectedApp = packageName
                processContentChange(event, appType, packageName)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                processTextChange(event, appType, packageName)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                processClickEvent(event, appType, packageName)
            }
        }
    }

    private fun processContentChange(
        event: AccessibilityEvent,
        appType: AIConversation.AppType,
        packageName: String
    ) {
        val source = event.source ?: return
        val uiSelectors = APP_UI_SIGNATURES[packageName] ?: return

        try {
            val viewId = source.viewIdResourceName ?: ""

            when {
                // 检测用户输入
                uiSelectors.userInputIds.any { viewId.contains(it.substringAfter(":id/")) } -> {
                    val text = source.text?.toString()?.trim() ?: return
                    if (text.isNotEmpty() && text != lastCapturedTexts["input_$packageName"]) {
                        lastCapturedTexts["input_$packageName"] = text
                        Log.d(TAG, "User input detected in $packageName: $text")
                        conversationStore?.getOrAddMessage(appType, "user", text)
                    }
                }

                // 检测AI响应
                uiSelectors.assistantOutputIds.any { viewId.contains(it.substringAfter(":id/")) } -> {
                    val text = source.text?.toString()?.trim() ?: return
                    if (text.isNotEmpty() && text.length > 5 && text != lastCapturedTexts["output_$packageName"]) {
                        lastCapturedTexts["output_$packageName"] = text
                        Log.d(TAG, "AI response detected in $packageName: ${text.take(100)}...")
                        conversationStore?.getOrAddMessage(appType, "assistant", text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing content change: ${e.message}")
        } finally {
            source.recycle()
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

        if (isEditText && text != lastCapturedTexts["input_$packageName"]) {
            lastCapturedTexts["input_$packageName"] = text
            Log.d(TAG, "Text changed (input) in $packageName: $text")
            conversationStore?.getOrAddMessage(appType, "user", text)
        }
    }

    private fun processClickEvent(
        event: AccessibilityEvent,
        appType: AIConversation.AppType,
        packageName: String
    ) {
        val source = event.source ?: return
        val uiSelectors = APP_UI_SIGNATURES[packageName] ?: return
        val viewId = source.viewIdResourceName ?: ""

        try {
            val isSendButton = uiSelectors.sendButtonIds.any {
                viewId.contains(it.substringAfter(":id/"))
            }

            if (isSendButton) {
                val inputText = lastCapturedTexts["input_$packageName"]
                if (!inputText.isNullOrEmpty()) {
                    Log.d(TAG, "Send button clicked, confirming input: $inputText")
                    conversationStore?.getOrAddMessage(appType, "user", inputText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing click event: ${e.message}")
        } finally {
            source.recycle()
        }
    }

    /**
     * 深度遍历窗口节点树，捕获所有可见文本内容。
     * 用于在精确匹配失败时，通过广度优先搜索获取聊天区域的全部文本。
     */
    private fun traverseNodeTree(node: AccessibilityNodeInfo?, depth: Int = 0): List<String> {
        if (node == null || depth > 15) return emptyList()
        val texts = mutableListOf<String>()

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            texts.add(it)
        }

        for (i in 0 until node.childCount) {
            texts.addAll(traverseNodeTree(node.getChild(i), depth + 1))
        }
        return texts
    }

    override fun onInterrupt() {
        Log.w(TAG, "AI Capture Service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "AI Capture Service destroyed")
        super.onDestroy()
    }
}
