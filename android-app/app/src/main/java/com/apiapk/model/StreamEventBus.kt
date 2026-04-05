package com.apiapk.model

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式事件总线 - 核心中的核心，作为AICaptureService和ApiServerService之间的桥梁。
 *
 * 工作原理：
 *   1. AICaptureService 检测到AI应用的文本变化时，计算出增量(delta)并推送到此总线
 *   2. ApiServerService 的SSE端点从总线拉取事件，实时推送给客户端
 *   3. 同时支持多个SSE客户端订阅，每个客户端都能收到完整的流式事件
 *
 * 流式输出格式（兼容OpenAI SSE格式）：
 *   data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"你好","accumulated":"你好"}
 *   data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"，","accumulated":"你好，"}
 *   data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"我是","accumulated":"你好，我是"}
 *   data: {"id":"xxx","app":"deepseek","role":"assistant","delta":"","accumulated":"你好，我是DeepSeek","finishReason":"stop"}
 */
object StreamEventBus {

    private const val TAG = "StreamEventBus"
    private const val MAX_BUFFER_SIZE = 200

    // 最近事件缓冲区，新订阅的客户端可以快速获取最近的事件
    private val recentEvents = ConcurrentLinkedQueue<StreamDelta>()

    // 所有活跃的SSE订阅者
    private val subscribers = CopyOnWriteArrayList<StreamSubscriber>()

    // 后台清理标记
    private val cleanupRunning = AtomicBoolean(false)

    /**
     * 流式订阅者接口 - SSE连接持有此引用来接收实时事件。
     * ApiServerService在处理SSE请求时创建一个实现，连接关闭时自动注销。
     */
    interface StreamSubscriber {
        /** 收到新的流式增量事件 */
        fun onDelta(delta: StreamDelta)
        /** 当前订阅者是否仍然活跃 */
        fun isActive(): Boolean
    }

    /**
     * 推送一个文本增量到事件总线。
     * 由AICaptureService在检测到AI应用输出变化时调用。
     * 事件会立即分发给所有活跃的SSE订阅者。
     */
    fun pushDelta(delta: StreamDelta) {
        // 加入最近事件缓冲
        recentEvents.add(delta)
        while (recentEvents.size > MAX_BUFFER_SIZE) {
            recentEvents.poll()
        }

        // 分发给所有活跃订阅者
        val activeSubscribers = subscribers.filter { it.isActive() }
        subscribers.removeAll(subscribers.filter { !it.isActive() })

        activeSubscribers.forEach { subscriber ->
            try {
                subscriber.onDelta(delta)
            } catch (e: Exception) {
                Log.e(TAG, "Error delivering delta to subscriber: ${e.message}")
            }
        }

        Log.d(TAG, "Delta pushed: app=${delta.app}, delta='${delta.take(30)}', subscribers=${activeSubscribers.size}")
    }

    /**
     * 注册一个SSE订阅者。
     * 注册后，该订阅者会收到所有后续的流式增量事件。
     * 可选地传入lastSeenTimestamp来从特定时间点开始接收。
     *
     * @param subscriber SSE连接的订阅者回调
     * @param filterApp 可选的应用过滤（如"deepseek"），null表示接收所有应用的事件
     * @return 已注册的订阅者数量
     */
    fun subscribe(subscriber: StreamSubscriber, filterApp: String? = null): Int {
        subscribers.add(subscriber)
        cleanupIfNeeded()
        Log.i(TAG, "New subscriber added. Total: ${subscribers.size}, filter: $filterApp")
        return subscribers.size
    }

    /**
     * 注销一个SSE订阅者（通常在SSE连接关闭时调用）。
     */
    fun unsubscribe(subscriber: StreamSubscriber) {
        subscribers.remove(subscriber)
        Log.i(TAG, "Subscriber removed. Total: ${subscribers.size}")
    }

    /**
     * 获取最近的事件缓冲区，用于新SSE连接时快速同步历史事件。
     *
     * @param filterApp 可选的应用过滤
     * @param sinceTimestamp 可选的时间戳过滤
     * @return 最近的事件列表
     */
    fun getRecentEvents(filterApp: String? = null, sinceTimestamp: Long? = null): List<StreamDelta> {
        var events = recentEvents.toList()

        if (filterApp != null) {
            events = events.filter { it.app == filterApp }
        }
        if (sinceTimestamp != null) {
            events = events.filter { it.timestamp >= sinceTimestamp }
        }

        return events
    }

    /** 获取当前活跃订阅者数量 */
    fun getSubscriberCount(): Int = subscribers.count { it.isActive() }

    /** 清理非活跃订阅者 */
    private fun cleanupIfNeeded() {
        if (cleanupRunning.compareAndSet(false, true)) {
            Thread {
                try {
                    subscribers.removeAll { !it.isActive() }
                } finally {
                    cleanupRunning.set(false)
                }
            }.start()
        }
    }

    /** 清空所有事件缓冲和订阅者 */
    fun clear() {
        recentEvents.clear()
        subscribers.clear()
    }
}

/**
 * 扩展函数：截取delta预览文本（用于日志）
 */
private fun StreamDelta.take(n: Int): String {
    val preview = if (delta.isEmpty()) "[finish:${finishReason}]" else delta
    return if (preview.length > n) preview.substring(0, n) + "..." else preview
}
