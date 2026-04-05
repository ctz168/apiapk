package com.apiapk.model

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 会话存储管理器 - 负责持久化存储所有捕获的AI对话数据。
 * 使用SharedPreferences作为底层存储，配合内存缓存提供快速访问。
 * 支持按应用类型过滤、分页查询和容量管理，防止存储溢出。
 */
class ConversationStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("apiapk_conversations", Context.MODE_PRIVATE)
    private val configPrefs: SharedPreferences =
        context.getSharedPreferences("apiapk_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val conversations = mutableListOf<AIConversation>()
    private val listeners = mutableListOf<ConversationListener>()

    interface ConversationListener {
        fun onNewConversation(conversation: AIConversation)
        fun onMessageAdded(conversation: AIConversation, message: AIMessage)
        fun onConversationUpdated(conversation: AIConversation)
    }

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val json = prefs.getString("conversations", null) ?: return
        try {
            val type = object : TypeToken<List<AIConversation>>() {}.type
            val loaded: List<AIConversation> = gson.fromJson(json, type)
            conversations.clear()
            conversations.addAll(loaded)
        } catch (e: Exception) {
            conversations.clear()
        }
    }

    private fun saveToDisk() {
        val json = gson.toJson(conversations)
        prefs.edit().putString("conversations", json).apply()
    }

    fun addListener(listener: ConversationListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ConversationListener) {
        listeners.remove(listener)
    }

    fun createConversation(app: AIConversation.AppType): AIConversation {
        val conversation = AIConversation(app = app)
        conversations.add(0, conversation)
        trimIfNeeded()
        saveToDisk()
        listeners.forEach { it.onNewConversation(conversation) }
        return conversation
    }

    fun getConversation(id: String): AIConversation? {
        return conversations.find { it.id == id }
    }

    fun getLatestConversation(app: AIConversation.AppType): AIConversation? {
        return conversations.find { it.app == app }
    }

    fun addMessage(conversationId: String, role: String, content: String): AIConversation? {
        val conversation = getConversation(conversationId) ?: return null
        if (role == "user") {
            conversation.addUserMessage(content)
        } else {
            conversation.addAssistantMessage(content)
        }
        saveToDisk()
        val message = conversation.messages.last()
        listeners.forEach { it.onMessageAdded(conversation, message) }
        return conversation
    }

    fun getOrAddMessage(app: AIConversation.AppType, role: String, content: String): AIConversation {
        var conv = getLatestConversation(app)
        if (conv == null) {
            conv = createConversation(app)
        }
        if (role == "user") {
            conv.addUserMessage(content)
        } else {
            conv.addAssistantMessage(content)
        }
        saveToDisk()
        val message = conv.messages.last()
        listeners.forEach { it.onMessageAdded(conv, message) }
        return conv
    }

    fun getAllConversations(): List<AIConversation> {
        return conversations.toList()
    }

    fun getConversationsByApp(app: AIConversation.AppType): List<AIConversation> {
        return conversations.filter { it.app == app }
    }

    fun getConversationSummaries(): List<ConversationSummary> {
        return conversations.map { it.toSummary() }
    }

    fun getConversationSummariesByApp(appIdentifier: String): List<ConversationSummary> {
        return conversations
            .filter { it.app.identifier == appIdentifier }
            .map { it.toSummary() }
    }

    fun deleteConversation(id: String): Boolean {
        val removed = conversations.removeIf { it.id == id }
        if (removed) saveToDisk()
        return removed
    }

    fun clearConversations(app: AIConversation.AppType? = null): Int {
        val count = if (app != null) {
            conversations.removeAll { it.app == app }
        } else {
            val size = conversations.size
            conversations.clear()
            size
        }
        saveToDisk()
        return count
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "totalConversations" to conversations.size,
            "totalMessages" to conversations.sumOf { it.messages.size },
            "byApp" to AIConversation.AppType.entries.associate { app ->
                app.identifier to mapOf(
                    "conversations" to conversations.count { it.app == app },
                    "messages" to conversations.filter { it.app == app }.sumOf { it.messages.size }
                )
            }
        )
    }

    private fun trimIfNeeded() {
        val config = loadConfig()
        while (conversations.size > config.maxConversations) {
            conversations.removeAt(conversations.lastIndex)
        }
    }

    fun saveConfig(config: ApiConfig) {
        val json = gson.toJson(config)
        configPrefs.edit().putString("config", json).apply()
    }

    fun loadConfig(): ApiConfig {
        val json = configPrefs.getString("config", null) ?: return ApiConfig()
        return try {
            gson.fromJson(json, ApiConfig::class.java)
        } catch (e: Exception) {
            ApiConfig()
        }
    }

    fun getConversationCount(): Int = conversations.size
}
