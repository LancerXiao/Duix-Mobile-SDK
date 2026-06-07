package ai.guiji.duix.test.ui

/**
 * 对话消息数据（Phase 2.2）
 *
 * @param role 消息角色：USER / AI / SYSTEM
 * @param text 消息内容
 * @param timestampMs 时间戳（毫秒）
 * @param isThinking 是否为思考中（AI 回复生成中）
 */
data class MessageData(
    val role: Role,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isThinking: Boolean = false
) {
    enum class Role { USER, AI, SYSTEM }
}
