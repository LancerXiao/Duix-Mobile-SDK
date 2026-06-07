package ai.guiji.duix.test.ui.adapter

import ai.guiji.duix.test.R
import ai.guiji.duix.test.ui.MessageData
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.Animation
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 对话消息 RecyclerView 适配器（Phase 2.2 + UI 现代化）
 *
 * 简化设计：每条消息一个 item，通过 role 决定布局
 * - USER: 右对齐气泡
 * - AI: 左对齐气泡（思考中时显示豆包/Coze 风格三圆点跳动）
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
        // 消息入场动画（豆包/Coze 风格：透明度 + 上滑）
        holder.itemView.startAnimation(
            AnimationUtils.loadAnimation(holder.itemView.context, R.anim.fade_in_up)
        )
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
        private val tvThinking: View? = itemView.findViewById(R.id.tvThinking)
        private val dot1: View? = itemView.findViewById(R.id.dot1)
        private val dot2: View? = itemView.findViewById(R.id.dot2)
        private val dot3: View? = itemView.findViewById(R.id.dot3)

        fun bind(message: MessageData) {
            tvText.text = message.text
            if (tvThinking != null) {
                val isThinking = message.isThinking
                tvThinking.visibility = if (isThinking) View.VISIBLE else View.GONE
                tvText.visibility = if (isThinking) View.GONE else View.VISIBLE
                if (isThinking) {
                    // 启动三圆点错相位动画（豆包/Coze 风格）
                    val ctx = itemView.context
                    val bounce = AnimationUtils.loadAnimation(ctx, R.anim.thinking_dot_bounce)
                    dot1?.startAnimation(applyOffset(bounce, 0L))
                    dot2?.startAnimation(applyOffset(bounce, 200L))
                    dot3?.startAnimation(applyOffset(bounce, 400L))
                } else {
                    // 停止动画防止内存/性能泄漏
                    dot1?.clearAnimation()
                    dot2?.clearAnimation()
                    dot3?.clearAnimation()
                }
            }
        }

        private fun applyOffset(src: Animation, offsetMs: Long): Animation {
            // 通过重设 startTime 实现相位偏移（不能直接修改 Animation.startOffset）
            src.setStartTime(android.os.SystemClock.uptimeMillis() + offsetMs)
            return src
        }
    }

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
        private const val TYPE_SYSTEM = 2
    }
}
