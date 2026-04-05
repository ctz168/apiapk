package com.apiapk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.apiapk.R
import com.apiapk.model.ConversationStore
import com.apiapk.model.ConversationSummary
import com.google.gson.GsonBuilder

/**
 * 日志查看Activity - 以列表形式展示所有捕获的AI对话记录。
 * 支持按应用类型过滤，显示每个会话的摘要信息（消息数量、最后消息内容、时间戳等）。
 * 使用RecyclerView实现高效的长列表滚动展示。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var store: ConversationStore
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ConversationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "捕获日志"

        store = ConversationStore(this)
        recyclerView = findViewById(R.id.recycler_conversations)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val summaries = store.getConversationSummaries()
        adapter = ConversationAdapter(summaries)
        recyclerView.adapter = adapter

        val tvTotal = findViewById<TextView>(R.id.tv_total_count)
        tvTotal.text = "共 ${summaries.size} 条会话记录"
    }

    override fun onResume() {
        super.onResume()
        adapter.updateData(store.getConversationSummaries())
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    class ConversationAdapter(
        private var items: List<ConversationSummary>
    ) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

        private val gson = GsonBuilder().setPrettyPrinting().create()

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAppName: TextView = view.findViewById(R.id.tv_app_name)
            val tvMessageCount: TextView = view.findViewById(R.id.tv_message_count)
            val tvLastMessage: TextView = view.findViewById(R.id.tv_last_message)
            val tvTimestamp: TextView = view.findViewById(R.id.tv_timestamp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvAppName.text = item.appDisplayName
            holder.tvMessageCount.text = "${item.messageCount} 条消息"

            holder.tvLastMessage.text = item.lastMessage ?: "（无内容）"
            holder.tvLastMessage.maxLines = 3

            val timeStr = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date(item.updatedAt))
            holder.tvTimestamp.text = timeStr
        }

        override fun getItemCount(): Int = items.size

        fun updateData(newItems: List<ConversationSummary>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}
