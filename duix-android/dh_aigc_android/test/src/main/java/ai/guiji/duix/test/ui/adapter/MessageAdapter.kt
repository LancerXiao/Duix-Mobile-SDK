package ai.guiji.duix.test.ui.adapter

import ai.guiji.duix.test.R
import ai.guiji.duix.test.ui.MessageData
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 对话消息 RecyclerView 适配器（Phase 2.2）
 *
 * 简化设计：每条消息一个 item，通过 role 决定布局
 * - USER: 右对齐气泡
 * - AI: 左对齐气泡
 * - SYSTEM: 居中小字
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<MessageData>()

    fun submit(newMessages: List<MessageData>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun append(message: MessageData) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLast(message: MessageData) {
        if (messages.isNotEmpty()) {
            messages[messages.size - 1] = message
            notifyItemChanged(messages.size - 1)
        }
    }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }

    fun snapshot(): List<MessageData> = messages.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutId = when (viewType) {
            TYPE_USER -> R.layout.item_message_user
            TYPE_AI -> R.layout.item_message_ai
            else -> R.layout.item_message_system
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].role) {
            MessageData.Role.USER -> TYPE_USER
            MessageData.Role.AI -> TYPE_AI
            MessageData.Role.SYSTEM -> TYPE_SYSTEM
        }
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tvMessageText)
        private val tvThinking: TextView? = itemView.findViewById(R.id.tvThinking)

        fun bind(message: MessageData) {
            tvText.text = message.text
            if (tvThinking != null) {
                tvThinking.visibility = if (message.isThinking) View.VISIBLE else View.GONE
                tvText.visibility = if (message.isThinking) View.GONE else View.VISIBLE
            }
        }
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
        private const val TYPE_SYSTEM = 2
    }
}
