package com.apiapk.model

import com.google.gson.annotations.SerializedName
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AI会话数据模型 - 用于存储从各个AI应用捕获的对话数据。
 * 每个会话代表一次完整的AI交互，包含用户输入和AI响应。
 * 支持DeepSeek、豆包（Doubao）和小爱（Xiaoai）三个AI模型的数据捕获。
 */
data class AIConversation(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("app")
    val app: AppType,

    @SerializedName("messages")
    val messages: MutableList<AIMessage> = mutableListOf(),

    @SerializedName("startedAt")
    val startedAt: Long = System.currentTimeMillis(),

    @SerializedName("updatedAt")
    var updatedAt: Long = System.currentTimeMillis(),

    @SerializedName("tags")
    val tags: MutableList<String> = mutableListOf()
) {
    enum class AppType(val packageName: String, val displayName: String, val identifier: String) {
        DEEPSEEK("com.deepseek.chat", "DeepSeek", "deepseek"),
        DOUBAO("com.larus.nova", "豆包", "doubao"),
        XIAOAI("com.miui.voiceassist", "小爱同学", "xiaoai"),
        CUSTOM("com.custom.app", "Custom", "custom");

        companion object {
            private val packageMap = ConcurrentHashMap<String, AppType>()
            fun fromPackage(pkg: String): AppType? {
                return packageMap.getOrPut(pkg) {
                    entries.find { it.packageName == pkg }
                }
            }
        }
    }

    fun addUserMessage(content: String) {
        messages.add(AIMessage(
            role = "user",
            content = content,
            timestamp = System.currentTimeMillis()
        ))
        updatedAt = System.currentTimeMillis()
    }

    fun addAssistantMessage(content: String) {
        messages.add(AIMessage(
            role = "assistant",
            content = content,
            timestamp = System.currentTimeMillis()
        ))
        updatedAt = System.currentTimeMillis()
    }

    fun toSummary(): ConversationSummary {
        return ConversationSummary(
            id = id,
            app = app.identifier,
            appDisplayName = app.displayName,
            messageCount = messages.size,
            startedAt = startedAt,
            updatedAt = updatedAt,
            lastMessage = messages.lastOrNull()?.content?.take(200),
            tags = tags
        )
    }
}

data class AIMessage(
    @SerializedName("role")
    val role: String,

    @SerializedName("content")
    val content: String,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("metadata")
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 流式增量片段 - AI模型逐token输出时，每次文本变化产生一个delta事件。
 * 用于SSE（Server-Sent Events）推送给客户端，实现与原大模型一致的流式体验。
 * 客户端收到的每个delta都是增量文本（不是全文），需要自行拼接。
 *
 * 例如DeepSeek输出"你好，我是"时可能产生3个delta：
 *   delta1: "你好"
 *   delta2: "，"
 *   delta3: "我是"
 * 最后收到一个finish_reason="stop"的delta表示生成完毕。
 */
data class StreamDelta(
    @SerializedName("id")
    val id: String,

    @SerializedName("app")
    val app: String,

    @SerializedName("role")
    val role: String,

    @SerializedName("delta")
    val delta: String,

    @SerializedName("accumulated")
    val accumulated: String,

    @SerializedName("finishReason")
    val finishReason: String? = null,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val FINISH_STOP = "stop"
        const val FINISH_LENGTH = "length"
        const val FINISH_ERROR = "error"

        /** 创建一个文本增量 */
        fun textDelta(id: String, app: String, role: String, delta: String, accumulated: String): StreamDelta {
            return StreamDelta(id = id, app = app, role = role, delta = delta, accumulated = accumulated)
        }

        /** 创建一个结束标记 */
        fun finish(id: String, app: String, role: String, accumulated: String, reason: String = FINISH_STOP): StreamDelta {
            return StreamDelta(id = id, app = app, role = role, delta = "", accumulated = accumulated, finishReason = reason)
        }
    }
}

data class ConversationSummary(
    @SerializedName("id")
    val id: String,

    @SerializedName("app")
    val app: String,

    @SerializedName("appDisplayName")
    val appDisplayName: String,

    @SerializedName("messageCount")
    val messageCount: Int,

    @SerializedName("startedAt")
    val startedAt: Long,

    @SerializedName("updatedAt")
    val updatedAt: Long,

    @SerializedName("lastMessage")
    val lastMessage: String?,

    @SerializedName("tags")
    val tags: List<String>
)

data class ApiConfig(
    @SerializedName("serverHost")
    val serverHost: String = "0.0.0.0",

    @SerializedName("serverPort")
    val serverPort: Int = 8765,

    @SerializedName("apiKey")
    val apiKey: String = "",

    @SerializedName("enableCors")
    val enableCors: Boolean = true,

    @SerializedName("maxConversations")
    val maxConversations: Int = 1000,

    @SerializedName("captureEnabled")
    val captureEnabled: Boolean = true,

    @SerializedName("autoStart")
    val autoStart: Boolean = false,

    @SerializedName("logLevel")
    val logLevel: String = "INFO",

    @SerializedName("streamDeltaInterval")
    val streamDeltaInterval: Long = 150,

    @SerializedName("streamIdleTimeout")
    val streamIdleTimeout: Long = 30000,

    @SerializedName("streamEnabled")
    val streamEnabled: Boolean = true
)

data class ApiResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String,

    @SerializedName("data")
    val data: Any? = null,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
