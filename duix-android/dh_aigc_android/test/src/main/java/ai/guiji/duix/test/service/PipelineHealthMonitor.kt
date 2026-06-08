package ai.guiji.duix.test.service

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 运行时管线健康监控：在每个阶段加断言检查，异常时自动修复或上报
 *
 * 监控内容：
 * 1. 状态机卡死检测：THINKING/SPEAKING 超时未恢复
 * 2. 状态机异常转换：非法状态跳转（如 SPEAKING → LISTENING）
 * 3. TTS 管线断裂：SPEAKING 后长时间无音频播放
 * 4. 网络状态检查：网络不可用时告警
 * 5. TTS 引擎可用性：TTS 引擎初始化失败时自动降级
 * 6. DUIX SDK 状态：SDK 未就绪时告警
 * 7. 自动修复：超时后强制恢复 IDLE + scheduleAutoListen
 *
 * 增强功能：
 * - TTS 引擎自动降级：SPEAKING 卡死时自动切换 TTS 引擎
 * - LLM 重试建议：THINKING 超时时建议检查 LLM 配置
 * - 健康评分：综合各指标给出管线健康评分
 * - 修复动作追踪：记录自动修复动作及其效果
 *
 * 使用方式：
 *   PipelineHealthMonitor(host).start()
 *   在 CallActivity.onCreate 中启动，onDestroy 中停止
 */
class PipelineHealthMonitor(private val host: HealthHost) {

    companion object {
        private const val TAG = "PipelineHealthMonitor"
        // 健康检查间隔
        private const val CHECK_INTERVAL_MS = 5_000L
        // 各状态最大持续时间（超过视为卡死）
        private const val MAX_THINKING_DURATION_MS = 35_000L
        private const val MAX_SPEAKING_DURATION_MS = 20_000L
        private const val MAX_LISTENING_DURATION_MS = 30_000L
        // TTS 降级冷却时间：避免频繁切换
        private const val TTS_FALLBACK_COOLDOWN_MS = 60_000L
    }

    interface HealthHost {
        fun getCallState(): PipelineSelfTest.CallState
        fun getStateDurationMs(): Long
        fun forceRecoverToIdle(reason: String)
        fun onHealthAlert(alert: HealthAlert)
        /** 检查网络是否可用 */
        fun isNetworkAvailable(): Boolean
        /** 检查 DUIX SDK 是否就绪 */
        fun isDuiXSdkReady(): Boolean
        /** 检查当前 TTS 引擎是否可用 */
        fun isCurrentTtsEngineReady(): Boolean
        /** TTS 引擎自动降级：切换到下一个可用的 TTS 引擎 */
        fun autoFallbackTtsEngine()
        /** 当前 TTS 引擎名 */
        fun currentTtsEngineName(): String
    }

    data class HealthAlert(
        val type: AlertType,
        val state: PipelineSelfTest.CallState,
        val durationMs: Long,
        val message: String,
        val fixAction: FixAction = FixAction.NONE,
        val fixSuggestion: String = ""
    )

    enum class AlertType {
        STATE_STUCK,                  // 状态卡死
        STATE_ILLEGAL_TRANSITION,     // 非法状态转换
        TTS_PIPELINE_BROKEN,          // TTS 管线断裂
        NETWORK_UNAVAILABLE,          // 网络不可用
        DUIX_SDK_NOT_READY,           // DUIX SDK 未就绪
        TTS_ENGINE_NOT_READY,         // TTS 引擎不可用
        TTS_ENGINE_FALLBACK,          // TTS 引擎自动降级
        LLM_TIMEOUT_SUGGESTION        // LLM 超时修复建议
    }

    /** 自动修复动作 */
    enum class FixAction {
        NONE,                       // 无需修复
        FORCE_RECOVER,              // 强制恢复 IDLE
        FALLBACK_TTS_ENGINE,        // 切换 TTS 引擎
        CHECK_NETWORK,              // 检查网络
        CHECK_LLM_CONFIG            // 检查 LLM 配置
    }

    /** 管线健康评分 */
    data class HealthScore(
        val score: Int,             // 0-100，100 为完全健康
        val issues: List<String>,   // 当前问题列表
        val lastUpdatedMs: Long     // 上次更新时间
    )

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var lastState: PipelineSelfTest.CallState = PipelineSelfTest.CallState.IDLE
    private var consecutiveStuckCount = 0
    // 环境检查去重：避免重复告警
    private var lastNetworkAlertTime = 0L
    private var lastDuiXAlertTime = 0L
    private var lastTtsAlertTime = 0L
    private var lastTtsFallbackTime = 0L
    private var lastLlmSuggestionTime = 0L
    private val ENV_ALERT_COOLDOWN_MS = 30_000L  // 同类环境告警冷却 30 秒

    // 健康评分追踪
    private var totalAlerts = 0
    private var totalAutoFixes = 0
    private var successfulAutoFixes = 0
    private var lastHealthScore = HealthScore(100, emptyList(), System.currentTimeMillis())

    // 连续 LLM 超时追踪
    private var consecutiveLlmTimeouts = 0

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            performHealthCheck()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /** 启动健康监控 */
    fun start() {
        if (isMonitoring) return
        isMonitoring = true
        lastState = host.getCallState()
        consecutiveStuckCount = 0
        totalAlerts = 0
        totalAutoFixes = 0
        successfulAutoFixes = 0
        consecutiveLlmTimeouts = 0
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        Log.i(TAG, "管线健康监控已启动")
    }

    /** 停止健康监控 */
    fun stop() {
        isMonitoring = false
        handler.removeCallbacks(checkRunnable)
        Log.i(TAG, "管线健康监控已停止")
    }

    /** 获取当前健康评分 */
    fun getHealthScore(): HealthScore = lastHealthScore

    /** 通知状态变更（由 CallActivity.setState 调用） */
    fun onStateChanged(newState: PipelineSelfTest.CallState) {
        if (!isMonitoring) return

        val oldState = lastState
        lastState = newState

        // 检查非法状态转换
        if (isIllegalTransition(oldState, newState)) {
            val alert = HealthAlert(
                type = AlertType.STATE_ILLEGAL_TRANSITION,
                state = newState,
                durationMs = 0,
                message = "非法状态转换: $oldState → $newState",
                fixAction = FixAction.FORCE_RECOVER,
                fixSuggestion = "状态机异常，建议强制恢复 IDLE"
            )
            Log.w(TAG, alert.message)
            host.onHealthAlert(alert)
            totalAlerts++
        }

        // 状态恢复后重置卡死计数
        if (newState == PipelineSelfTest.CallState.IDLE) {
            consecutiveStuckCount = 0
        }

        // 追踪 LLM 超时
        if (oldState == PipelineSelfTest.CallState.THINKING && newState == PipelineSelfTest.CallState.IDLE) {
            // THINKING 直接回到 IDLE（非 SPEAKING），说明 LLM 可能失败
            consecutiveLlmTimeouts++
        } else if (newState == PipelineSelfTest.CallState.SPEAKING) {
            consecutiveLlmTimeouts = 0
        }

        // 更新健康评分
        updateHealthScore()
    }

    private fun performHealthCheck() {
        val currentState = host.getCallState()
        val durationMs = host.getStateDurationMs()
        val now = System.currentTimeMillis()

        // 1. 状态卡死检测 + 自动修复
        when (currentState) {
            PipelineSelfTest.CallState.THINKING -> {
                if (durationMs > MAX_THINKING_DURATION_MS) {
                    consecutiveStuckCount++
                    consecutiveLlmTimeouts++
                    val alert = HealthAlert(
                        type = AlertType.STATE_STUCK,
                        state = currentState,
                        durationMs = durationMs,
                        message = "THINKING 状态卡死 ${durationMs}ms (>${MAX_THINKING_DURATION_MS}ms)，强制恢复",
                        fixAction = FixAction.FORCE_RECOVER,
                        fixSuggestion = if (consecutiveLlmTimeouts >= 3) "LLM 连续超时 $consecutiveLlmTimeouts 次，请检查 API Key 和网络"
                            else "LLM 响应超时，已自动恢复"
                    )
                    Log.w(TAG, alert.message)
                    host.onHealthAlert(alert)
                    host.forceRecoverToIdle("THINKING 超时 ${durationMs}ms")
                    totalAlerts++
                    totalAutoFixes++

                    // 连续 LLM 超时告警
                    if (consecutiveLlmTimeouts >= 3 && now - lastLlmSuggestionTime > ENV_ALERT_COOLDOWN_MS) {
                        lastLlmSuggestionTime = now
                        val llmAlert = HealthAlert(
                            type = AlertType.LLM_TIMEOUT_SUGGESTION,
                            state = currentState,
                            durationMs = durationMs,
                            message = "LLM 连续超时 $consecutiveLlmTimeouts 次，可能是 API Key 无效或服务宕机",
                            fixAction = FixAction.CHECK_LLM_CONFIG,
                            fixSuggestion = "检查 AiConfig 中 LLM_BASE_URL、LLM_MODEL、AGNES_AI_API_KEY 是否正确配置"
                        )
                        Log.e(TAG, llmAlert.message)
                        host.onHealthAlert(llmAlert)
                    }
                }
            }
            PipelineSelfTest.CallState.SPEAKING -> {
                if (durationMs > MAX_SPEAKING_DURATION_MS) {
                    consecutiveStuckCount++
                    val shouldFallbackTts = now - lastTtsFallbackTime > TTS_FALLBACK_COOLDOWN_MS
                    val alert = HealthAlert(
                        type = AlertType.STATE_STUCK,
                        state = currentState,
                        durationMs = durationMs,
                        message = "SPEAKING 状态卡死 ${durationMs}ms (>${MAX_SPEAKING_DURATION_MS}ms)${if (shouldFallbackTts) "，尝试切换 TTS 引擎" else "，强制恢复"}",
                        fixAction = if (shouldFallbackTts) FixAction.FALLBACK_TTS_ENGINE else FixAction.FORCE_RECOVER,
                        fixSuggestion = if (shouldFallbackTts) "TTS 引擎可能异常，自动切换到备用引擎"
                            else "TTS 降级冷却中，直接强制恢复"
                    )
                    Log.w(TAG, alert.message)
                    host.onHealthAlert(alert)
                    totalAlerts++

                    // 先尝试 TTS 引擎降级，如果冷却中则直接恢复
                    if (shouldFallbackTts) {
                        lastTtsFallbackTime = now
                        val oldEngine = host.currentTtsEngineName()
                        host.autoFallbackTtsEngine()
                        val newEngine = host.currentTtsEngineName()
                        Log.i(TAG, "[AUTO-FIX] TTS 引擎降级: $oldEngine → $newEngine")
                        totalAutoFixes++

                        // 降级后仍然强制恢复当前卡死状态
                        host.forceRecoverToIdle("SPEAKING 超时 ${durationMs}ms，已切换 TTS 引擎")

                        val fallbackAlert = HealthAlert(
                            type = AlertType.TTS_ENGINE_FALLBACK,
                            state = currentState,
                            durationMs = durationMs,
                            message = "TTS 引擎自动降级: $oldEngine → $newEngine",
                            fixAction = FixAction.FALLBACK_TTS_ENGINE,
                            fixSuggestion = "已切换到备用 TTS 引擎，下次对话将使用新引擎"
                        )
                        host.onHealthAlert(fallbackAlert)
                    } else {
                        host.forceRecoverToIdle("SPEAKING 超时 ${durationMs}ms")
                        totalAutoFixes++
                    }
                }
            }
            PipelineSelfTest.CallState.LISTENING -> {
                if (durationMs > MAX_LISTENING_DURATION_MS) {
                    consecutiveStuckCount++
                    val alert = HealthAlert(
                        type = AlertType.STATE_STUCK,
                        state = currentState,
                        durationMs = durationMs,
                        message = "LISTENING 状态卡死 ${durationMs}ms (>${MAX_LISTENING_DURATION_MS}ms)，强制恢复",
                        fixAction = FixAction.FORCE_RECOVER,
                        fixSuggestion = "ASR 录音超时，已自动恢复"
                    )
                    Log.w(TAG, alert.message)
                    host.onHealthAlert(alert)
                    host.forceRecoverToIdle("LISTENING 超时 ${durationMs}ms")
                    totalAlerts++
                    totalAutoFixes++
                }
            }
            PipelineSelfTest.CallState.IDLE -> {
                consecutiveStuckCount = 0
            }
        }

        // 2. 连续卡死告警
        if (consecutiveStuckCount >= 3) {
            val alert = HealthAlert(
                type = AlertType.TTS_PIPELINE_BROKEN,
                state = currentState,
                durationMs = durationMs,
                message = "管线连续卡死 $consecutiveStuckCount 次，可能存在系统性问题",
                fixAction = FixAction.CHECK_NETWORK,
                fixSuggestion = "请检查网络连接、LLM API Key 配置、TTS 引擎可用性"
            )
            Log.e(TAG, alert.message)
            host.onHealthAlert(alert)
            totalAlerts++
        }

        // 3. 网络状态检查（仅在 THINKING/SPEAKING 时检查，IDLE 时网络断开不影响）
        if (currentState == PipelineSelfTest.CallState.THINKING || currentState == PipelineSelfTest.CallState.SPEAKING) {
            if (!host.isNetworkAvailable() && now - lastNetworkAlertTime > ENV_ALERT_COOLDOWN_MS) {
                lastNetworkAlertTime = now
                val alert = HealthAlert(
                    type = AlertType.NETWORK_UNAVAILABLE,
                    state = currentState,
                    durationMs = durationMs,
                    message = "网络不可用，当前状态=$currentState 可能无法完成",
                    fixAction = FixAction.CHECK_NETWORK,
                    fixSuggestion = "请检查 WiFi/移动数据连接"
                )
                Log.w(TAG, alert.message)
                host.onHealthAlert(alert)
                totalAlerts++
            }
        }

        // 4. DUIX SDK 状态检查
        if (currentState != PipelineSelfTest.CallState.IDLE && !host.isDuiXSdkReady() && now - lastDuiXAlertTime > ENV_ALERT_COOLDOWN_MS) {
            lastDuiXAlertTime = now
            val alert = HealthAlert(
                type = AlertType.DUIX_SDK_NOT_READY,
                state = currentState,
                durationMs = durationMs,
                message = "DUIX SDK 未就绪，当前状态=$currentState 可能无法正常工作",
                fixAction = FixAction.FORCE_RECOVER,
                fixSuggestion = "数字人 SDK 未加载完成，建议等待或重启应用"
            )
            Log.w(TAG, alert.message)
            host.onHealthAlert(alert)
            totalAlerts++
        }

        // 5. TTS 引擎可用性检查（仅在 SPEAKING 时检查）
        if (currentState == PipelineSelfTest.CallState.SPEAKING && !host.isCurrentTtsEngineReady() && now - lastTtsAlertTime > ENV_ALERT_COOLDOWN_MS) {
            lastTtsAlertTime = now
            val shouldFallback = now - lastTtsFallbackTime > TTS_FALLBACK_COOLDOWN_MS
            val alert = HealthAlert(
                type = AlertType.TTS_ENGINE_NOT_READY,
                state = currentState,
                durationMs = durationMs,
                message = "当前 TTS 引擎不可用，SPEAKING 状态可能无法完成",
                fixAction = if (shouldFallback) FixAction.FALLBACK_TTS_ENGINE else FixAction.NONE,
                fixSuggestion = if (shouldFallback) "将自动切换到备用 TTS 引擎" else "TTS 降级冷却中，请手动切换引擎"
            )
            Log.w(TAG, alert.message)
            host.onHealthAlert(alert)
            totalAlerts++

            // 自动降级 TTS 引擎
            if (shouldFallback) {
                lastTtsFallbackTime = now
                val oldEngine = host.currentTtsEngineName()
                host.autoFallbackTtsEngine()
                val newEngine = host.currentTtsEngineName()
                Log.i(TAG, "[AUTO-FIX] TTS 引擎不可用，自动降级: $oldEngine → $newEngine")
                totalAutoFixes++
            }
        }

        // 更新健康评分
        updateHealthScore()
    }

    /** 更新管线健康评分 */
    private fun updateHealthScore() {
        val issues = mutableListOf<String>()
        var score = 100

        // 扣分项
        if (consecutiveStuckCount > 0) {
            score -= consecutiveStuckCount * 15
            issues.add("连续卡死 $consecutiveStuckCount 次")
        }
        if (consecutiveLlmTimeouts > 0) {
            score -= consecutiveLlmTimeouts * 10
            issues.add("LLM 连续超时 $consecutiveLlmTimeouts 次")
        }
        if (!host.isNetworkAvailable()) {
            score -= 20
            issues.add("网络不可用")
        }
        if (!host.isDuiXSdkReady()) {
            score -= 15
            issues.add("DUIX SDK 未就绪")
        }
        if (!host.isCurrentTtsEngineReady()) {
            score -= 10
            issues.add("TTS 引擎不可用")
        }
        if (totalAlerts > 5) {
            score -= 10
            issues.add("告警过多 ($totalAlerts 次)")
        }

        score = score.coerceIn(0, 100)
        lastHealthScore = HealthScore(score, issues, System.currentTimeMillis())
    }

    private fun isIllegalTransition(
        from: PipelineSelfTest.CallState,
        to: PipelineSelfTest.CallState
    ): Boolean {
        // 合法转换：
        // IDLE → LISTENING, IDLE → THINKING
        // LISTENING → THINKING, LISTENING → IDLE
        // THINKING → SPEAKING, THINKING → IDLE
        // SPEAKING → IDLE
        // 任何 → IDLE 都是合法的（超时恢复/取消）
        if (to == PipelineSelfTest.CallState.IDLE) return false
        if (from == to) return false

        return when (from) {
            PipelineSelfTest.CallState.IDLE -> {
                to != PipelineSelfTest.CallState.LISTENING && to != PipelineSelfTest.CallState.THINKING
            }
            PipelineSelfTest.CallState.LISTENING -> {
                to != PipelineSelfTest.CallState.THINKING
            }
            PipelineSelfTest.CallState.THINKING -> {
                to != PipelineSelfTest.CallState.SPEAKING
            }
            PipelineSelfTest.CallState.SPEAKING -> {
                true
            }
        }
    }
}
