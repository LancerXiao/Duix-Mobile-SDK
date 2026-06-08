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
 * 5. TTS 引擎可用性：TTS 引擎初始化失败时告警
 * 6. DUIX SDK 状态：SDK 未就绪时告警
 * 7. 自动修复：超时后强制恢复 IDLE + scheduleAutoListen
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
    }

    data class HealthAlert(
        val type: AlertType,
        val state: PipelineSelfTest.CallState,
        val durationMs: Long,
        val message: String
    )

    enum class AlertType {
        STATE_STUCK,                  // 状态卡死
        STATE_ILLEGAL_TRANSITION,     // 非法状态转换
        TTS_PIPELINE_BROKEN,          // TTS 管线断裂
        NETWORK_UNAVAILABLE,          // 网络不可用
        DUIX_SDK_NOT_READY,           // DUIX SDK 未就绪
        TTS_ENGINE_NOT_READY          // TTS 引擎不可用
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var lastState: PipelineSelfTest.CallState = PipelineSelfTest.CallState.IDLE
    private var consecutiveStuckCount = 0
    // 环境检查去重：避免重复告警
    private var lastNetworkAlertTime = 0L
    private var lastDuiXAlertTime = 0L
    private var lastTtsAlertTime = 0L
    private val ENV_ALERT_COOLDOWN_MS = 30_000L  // 同类环境告警冷却 30 秒

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
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        Log.i(TAG, "管线健康监控已启动")
    }

    /** 停止健康监控 */
    fun stop() {
        isMonitoring = false
        handler.removeCallbacks(checkRunnable)
        Log.i(TAG, "管线健康监控已停止")
    }

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
                message = "非法状态转换: $oldState → $newState"
            )
            Log.w(TAG, alert.message)
            host.onHealthAlert(alert)
        }

        // 状态恢复后重置卡死计数
        if (newState == PipelineSelfTest.CallState.IDLE) {
            consecutiveStuckCount = 0
        }
    }

    private fun performHealthCheck() {
        val currentState = host.getCallState()
        val durationMs = host.getStateDurationMs()
        val now = System.currentTimeMillis()

        // 1. 状态卡死检测
        when (currentState) {
            PipelineSelfTest.CallState.THINKING -> {
                if (durationMs > MAX_THINKING_DURATION_MS) {
                    consecutiveStuckCount++
                    val alert = HealthAlert(
                        type = AlertType.STATE_STUCK,
                        state = currentState,
                        durationMs = durationMs,
                        message = "THINKING 状态卡死 ${durationMs}ms (>${MAX_THINKING_DURATION_MS}ms)，强制恢复"
                    )
                    Log.w(TAG, alert.message)
                    host.onHealthAlert(alert)
                    host.forceRecoverToIdle("THINKING 超时 ${durationMs}ms")
                }
            }
            PipelineSelfTest.CallState.SPEAKING -> {
                if (durationMs > MAX_SPEAKING_DURATION_MS) {
                    consecutiveStuckCount++
                    val alert = HealthAlert(
                        type = AlertType.STATE_STUCK,
                        state = currentState,
                        durationMs = durationMs,
                        message = "SPEAKING 状态卡死 ${durationMs}ms (>${MAX_SPEAKING_DURATION_MS}ms)，强制恢复"
                    )
                    Log.w(TAG, alert.message)
                    host.onHealthAlert(alert)
                    host.forceRecoverToIdle("SPEAKING 超时 ${durationMs}ms")
                }
            }
            PipelineSelfTest.CallState.LISTENING -> {
                if (durationMs > MAX_LISTENING_DURATION_MS) {
                    consecutiveStuckCount++
                    val alert = HealthAlert(
                        type = AlertType.STATE_STUCK,
                        state = currentState,
                        durationMs = durationMs,
                        message = "LISTENING 状态卡死 ${durationMs}ms (>${MAX_LISTENING_DURATION_MS}ms)，强制恢复"
                    )
                    Log.w(TAG, alert.message)
                    host.onHealthAlert(alert)
                    host.forceRecoverToIdle("LISTENING 超时 ${durationMs}ms")
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
                message = "管线连续卡死 $consecutiveStuckCount 次，可能存在系统性问题"
            )
            Log.e(TAG, alert.message)
            host.onHealthAlert(alert)
        }

        // 3. 网络状态检查（仅在 THINKING/SPEAKING 时检查，IDLE 时网络断开不影响）
        if (currentState == PipelineSelfTest.CallState.THINKING || currentState == PipelineSelfTest.CallState.SPEAKING) {
            if (!host.isNetworkAvailable() && now - lastNetworkAlertTime > ENV_ALERT_COOLDOWN_MS) {
                lastNetworkAlertTime = now
                val alert = HealthAlert(
                    type = AlertType.NETWORK_UNAVAILABLE,
                    state = currentState,
                    durationMs = durationMs,
                    message = "网络不可用，当前状态=$currentState 可能无法完成"
                )
                Log.w(TAG, alert.message)
                host.onHealthAlert(alert)
            }
        }

        // 4. DUIX SDK 状态检查
        if (currentState != PipelineSelfTest.CallState.IDLE && !host.isDuiXSdkReady() && now - lastDuiXAlertTime > ENV_ALERT_COOLDOWN_MS) {
            lastDuiXAlertTime = now
            val alert = HealthAlert(
                type = AlertType.DUIX_SDK_NOT_READY,
                state = currentState,
                durationMs = durationMs,
                message = "DUIX SDK 未就绪，当前状态=$currentState 可能无法正常工作"
            )
            Log.w(TAG, alert.message)
            host.onHealthAlert(alert)
        }

        // 5. TTS 引擎可用性检查（仅在 SPEAKING 时检查）
        if (currentState == PipelineSelfTest.CallState.SPEAKING && !host.isCurrentTtsEngineReady() && now - lastTtsAlertTime > ENV_ALERT_COOLDOWN_MS) {
            lastTtsAlertTime = now
            val alert = HealthAlert(
                type = AlertType.TTS_ENGINE_NOT_READY,
                state = currentState,
                durationMs = durationMs,
                message = "当前 TTS 引擎不可用，SPEAKING 状态可能无法完成"
            )
            Log.w(TAG, alert.message)
            host.onHealthAlert(alert)
        }
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
        // 任何 → IDLE 都是合法的（超时恢复）
        if (to == PipelineSelfTest.CallState.IDLE) return false

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
