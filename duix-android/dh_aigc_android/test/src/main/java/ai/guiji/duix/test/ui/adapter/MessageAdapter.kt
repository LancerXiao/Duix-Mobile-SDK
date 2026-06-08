package ai.guiji.duix.test.ui.adapter

import ai.guiji.duix.test.R
import ai.guiji.duix.test.ui.MessageData
import ai.guiji.duix.test.util.MarkdownRenderer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.Animation
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 对话消息 RecyclerView 适配器（Phase 2.2 + UI 现代化 + Phase 5.3 长按菜单）
 *
 * 简化设计：每条消息一个 item，通过 role 决定布局
 * - USER: 右对齐气泡
 * - AI: 左对齐气泡（思考中时显示豆包/Coze 风格三圆点跳动）
 * - SYSTEM: 居中小字
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<MessageData>()
    private var actionListener: ((MessageData, Action) -> Unit)? = null

    enum class Action { COPY, REGENERATE, LIKE, DISLIKE, SHARE }

    fun setOnActionListener(listener: ((MessageData, Action) -> Unit)?) {
        this.actionListener = listener
    }

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

    /** [Phase 5.4 P1-3] 移除指定位置的消息（重新生成时删除最后一条 AI 消息） */
    fun removeAt(position: Int) {
        if (position in messages.indices) {
            messages.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun snapshot(): List<MessageData> = messages.toList()

    fun getMessage(position: Int): MessageData = messages[position]

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
        // [Phase 5.4 P1-3] 仅最后一条 AI 消息显示重新生成按钮行
        val lastAiIndex = messages.indexOfLast { it.role == MessageData.Role.AI && !it.isThinking }
        val isLastAi = (position == lastAiIndex)
        holder.bind(messages[position], isLastAi)
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

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tvMessageText)
        private val tvThinking: View? = itemView.findViewById(R.id.tvThinking)
        private val dot1: View? = itemView.findViewById(R.id.dot1)
        private val dot2: View? = itemView.findViewById(R.id.dot2)
        private val dot3: View? = itemView.findViewById(R.id.dot3)
        private val llAiActions: View? = itemView.findViewById(R.id.llAiActions)
        private val btnRegenerate: View? = itemView.findViewById(R.id.btnRegenerate)

        fun bind(message: MessageData, isLastAi: Boolean = false) {
            // [Phase 5.1 P0-2] AI 消息用 Markdown 渲染
            if (message.role == MessageData.Role.AI) {
                MarkdownRenderer.renderInto(tvText, message.text)
            } else {
                tvText.text = message.text
            }
            if (tvThinking != null) {
                val isThinking = message.isThinking
                tvThinking.visibility = if (isThinking) View.VISIBLE else View.GONE
                tvText.visibility = if (isThinking) View.GONE else View.VISIBLE
                if (isThinking) {
                    val ctx = itemView.context
                    val bounce = AnimationUtils.loadAnimation(ctx, R.anim.thinking_dot_bounce)
                    dot1?.startAnimation(applyOffset(bounce, 0L))
                    dot2?.startAnimation(applyOffset(bounce, 200L))
                    dot3?.startAnimation(applyOffset(bounce, 400L))
                } else {
                    dot1?.clearAnimation()
                    dot2?.clearAnimation()
                    dot3?.clearAnimation()
                }
            }
            // [Phase 5.4 P1-3] 重新生成按钮行：仅最后一条 AI 消息（且非思考中）显示
            val showRegen = isLastAi && message.role == MessageData.Role.AI && !message.isThinking
            llAiActions?.visibility = if (showRegen) View.VISIBLE else View.GONE
            if (showRegen) {
                btnRegenerate?.setOnClickListener {
                    val pos = adapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                    actionListener?.invoke(messages[pos], Action.REGENERATE)
                }
            } else {
                btnRegenerate?.setOnClickListener(null)
            }
            // [Phase 5.3 P0-3] 长按消息弹出 PopupMenu 操作菜单
            itemView.setOnLongClickListener { anchor ->
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val msg = messages[pos]
                val popup = androidx.appcompat.widget.PopupMenu(anchor.context, anchor)
                popup.menuInflater.inflate(R.menu.menu_message_actions, popup.menu)
                // 重新生成 / 点赞 / 点踩 只对 AI 消息显示
                if (msg.role != MessageData.Role.AI) {
                    popup.menu.findItem(R.id.action_regenerate)?.isVisible = false
                    popup.menu.findItem(R.id.action_like)?.isVisible = false
                    popup.menu.findItem(R.id.action_dislike)?.isVisible = false
                }
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_copy -> {
                            val cm = anchor.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("DUIX message", msg.text))
                            android.widget.Toast.makeText(
                                anchor.context, "已复制", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            true
                        }
                        R.id.action_regenerate -> {
                            actionListener?.invoke(msg, Action.REGENERATE)
                            true
                        }
                        R.id.action_like -> {
                            actionListener?.invoke(msg, Action.LIKE)
                            android.widget.Toast.makeText(
                                anchor.context, "👍 感谢反馈", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            true
                        }
                        R.id.action_dislike -> {
                            actionListener?.invoke(msg, Action.DISLIKE)
                            android.widget.Toast.makeText(
                                anchor.context, "👎 感谢反馈", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            true
                        }
                        R.id.action_share -> {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, msg.text)
                            }
                            anchor.context.startActivity(
                                android.content.Intent.createChooser(intent, "分享消息")
                            )
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
                true
            }
        }

        private fun applyOffset(src: Animation, offsetMs: Long): Animation {
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
