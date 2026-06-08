package ai.guiji.duix.test.service

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 管线端到端自测：模拟用户行为，自动跑通 ASR→LLM→TTS→数字人 全链路
 *
 * 测试流程：
 * 1. 模拟用户输入文本（跳过 ASR，直接调 sendToLlm）或模拟 ASR 语音输入
 * 2. 验证 LLM 返回非空文本（THINKING → 有回复）
 * 3. 验证 TTS 合成成功（SPEAKING 状态被触发）
 * 4. 验证状态机正确回到 IDLE
 * 5. 多轮对话循环测试，验证"数字人只说一次话"bug 已修复
 *
 * 使用方式：
 *   PipelineSelfTest(host).start()
 *   host 需实现 TestHost 接口
 */
class PipelineSelfTest(private val host: TestHost) {

    companion object {
        private const val TAG = "PipelineSelfTest"
        // 每个阶段的最大等待时间
        private const val LLM_RESPONSE_TIMEOUT_MS = 35_000L  // LLM 响应超时（含 THINKING 超时 30s + 余量）
        private const val TTS_COMPLETE_TIMEOUT_MS = 15_000L  // TTS 合成+播放超时
        private const val ASR_LISTEN_TIMEOUT_MS = 10_000L    // ASR 录音超时
        private const val ROUND_DELAY_MS = 2_000L            // 每轮之间的间隔
        // 默认测试轮数
        private const val DEFAULT_ROUNDS = 3
        // 测试用的输入文本
        private val TEST_INPUTS = listOf(
            "你好，请用一句话介绍你自己",
            "今天天气怎么样？",
            "给我讲一个简短的笑话",
            "1加1等于几？",
            "你喜欢什么颜色？"
        )
    }

    /** 测试模式：纯文本输入 or 模拟 ASR 语音输入 or TTS 引擎压力测试 */
    enum class TestMode { TEXT_ONLY, WITH_ASR, TTS_ENGINE_STRESS, RAPID_MULTI_ROUND }

    enum class CallState { IDLE, LISTENING, THINKING, SPEAKING }

    data class StageTiming(
        val asrStartMs: Long = 0,
        val asrEndMs: Long = 0,
        val thinkingStartMs: Long = 0,
        val thinkingEndMs: Long = 0,
        val speakingStartMs: Long = 0,
        val speakingEndMs: Long = 0,
        val idleRecoveryMs: Long = 0
    )

    data class RoundResult(
        val round: Int,
        val input: String,
        val testMode: TestMode,
        val asrSuccess: Boolean,
        val llmSuccess: Boolean,
        val ttsSuccess: Boolean,
        val stateRecovery: Boolean,
        val durationMs: Long,
        val stageTiming: StageTiming,
        val errorDetail: String? = null,
        val logs: List<String> = emptyList()
    )

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var currentRound = 0
    private var totalRounds = DEFAULT_ROUNDS
    private var testMode = TestMode.TEXT_ONLY
    private val results = mutableListOf<RoundResult>()
    private val roundLogs = mutableListOf<String>()

    // 每轮的状态追踪
    private var roundStartTime = 0L
    private var sawListening = false
    private var sawThinking = false
    private var sawSpeaking = false
    private var sawIdleRecovery = false
    private var llmReturnedText = false
    private var asrSucceeded = false
    private var roundError: String? = null
    private var stageTiming = StageTiming()

    // 状态监听器
    private var stateListener: ((CallState) -> Unit)? = null

    /** 开始自测 */
    fun start(rounds: Int = DEFAULT_ROUNDS, mode: TestMode = TestMode.TEXT_ONLY) {
        if (isRunning) {
            Log.w(TAG, "自测已在运行中，跳过")
            return
        }
        if (!host.isDuiXReady()) {
            Log.e(TAG, "DUIX 未就绪，无法开始自测")
            host.onTestLog("[ERROR] DUIX 未就绪，无法开始自测")
            return
        }

        isRunning = true
        currentRound = 0
        totalRounds = rounds
        testMode = mode
        results.clear()

        val modeName = if (mode == TestMode.WITH_ASR) "ASR+LLM+TTS" else "LLM+TTS"
        Log.i(TAG, "=== 管线端到端自测开始 === 共 $totalRounds 轮, 模式=$modeName, TTS=${host.currentTtsEngineName()}")
        host.onTestLog("自测开始: $totalRounds 轮, $modeName, TTS=${host.currentTtsEngineName()}")

        // 注册状态监听
        stateListener = { state ->
            onStateChanged(state)
        }
        host.addStateListener(stateListener!!)

        // 延迟启动第一轮
        handler.postDelayed({ runNextRound() }, 1000L)
    }

    /** 停止自测 */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        stateListener?.let { host.removeStateListener(it) }
        stateListener = null
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "自测已停止")
    }

    private fun logRound(message: String) {
        Log.i(TAG, message)
        roundLogs.add(message)
        host.onTestLog(message)
    }

    private fun runNextRound() {
        if (!isRunning || currentRound >= totalRounds) {
            finishTest()
            return
        }

        currentRound++
        val input = TEST_INPUTS[(currentRound - 1) % TEST_INPUTS.size]

        // 重置本轮追踪状态
        roundStartTime = System.currentTimeMillis()
        sawListening = false
        sawThinking = false
        sawSpeaking = false
        sawIdleRecovery = false
        llmReturnedText = false
        asrSucceeded = false
        roundError = null
        roundLogs.clear()
        stageTiming = StageTiming()

        logRound("--- 第 $currentRound/$totalRounds 轮 --- 输入: $input")

        // 检查当前状态必须是 IDLE
        if (host.getCallState() != CallState.IDLE) {
            logRound("[WARN] 状态=${host.getCallState()}，等待 IDLE...")
            handler.postDelayed({
                if (host.getCallState() == CallState.IDLE) {
                    startRoundInput(input)
                } else {
                    recordRoundResult(input, asrSuccess = false, llmSuccess = false, ttsSuccess = false,
                        stateRecovery = false, "状态卡在 ${host.getCallState()}，无法开始")
                    handler.postDelayed({ runNextRound() }, ROUND_DELAY_MS)
                }
            }, 5000L)
            return
        }

        startRoundInput(input)
    }

    private fun startRoundInput(input: String) {
        when (testMode) {
            TestMode.TEXT_ONLY -> {
                // 直接文本输入（跳过 ASR）
                host.simulateUserInput(input)
                // 设置 LLM 响应超时
                handler.postDelayed({
                    if (!llmReturnedText && isRunning) {
                        roundError = "LLM 响应超时 ${LLM_RESPONSE_TIMEOUT_MS}ms"
                        logRound("[TIMEOUT] LLM 响应超时")
                    }
                }, LLM_RESPONSE_TIMEOUT_MS)
            }
            TestMode.WITH_ASR -> {
                // 模拟 ASR 语音输入：先启动录音，等待一段时间后注入识别结果
                stageTiming = stageTiming.copy(asrStartMs = System.currentTimeMillis())
                host.simulateAsrInput(input)
                // ASR 超时检测
                handler.postDelayed({
                    if (!sawThinking && !llmReturnedText && isRunning) {
                        roundError = "ASR 超时 ${ASR_LISTEN_TIMEOUT_MS}ms，未触发 THINKING"
                        logRound("[TIMEOUT] ASR 超时")
                    }
                }, ASR_LISTEN_TIMEOUT_MS)
                // LLM 响应超时（从 ASR 开始计算）
                handler.postDelayed({
                    if (!llmReturnedText && isRunning) {
                        roundError = "LLM 响应超时 ${LLM_RESPONSE_TIMEOUT_MS}ms"
                        logRound("[TIMEOUT] LLM 响应超时")
                    }
                }, ASR_LISTEN_TIMEOUT_MS + LLM_RESPONSE_TIMEOUT_MS)
            }
            TestMode.TTS_ENGINE_STRESS -> {
                // TTS 引擎压力测试：每轮切换 TTS 引擎，验证 fallback 机制
                host.switchTtsEngineForTest(currentRound)
                host.simulateUserInput(input)
                handler.postDelayed({
                    if (!llmReturnedText && isRunning) {
                        roundError = "LLM 响应超时 ${LLM_RESPONSE_TIMEOUT_MS}ms"
                        logRound("[TIMEOUT] LLM 响应超时")
                    }
                }, LLM_RESPONSE_TIMEOUT_MS)
            }
            TestMode.RAPID_MULTI_ROUND -> {
                // 快速多轮测试：减少间隔时间，验证状态机不会卡死
                host.simulateUserInput(input)
                handler.postDelayed({
                    if (!llmReturnedText && isRunning) {
                        roundError = "LLM 响应超时 ${LLM_RESPONSE_TIMEOUT_MS}ms"
                        logRound("[TIMEOUT] LLM 响应超时")
                    }
                }, LLM_RESPONSE_TIMEOUT_MS)
            }
        }
    }

    private fun onStateChanged(state: CallState) {
        if (!isRunning) return

        when (state) {
            CallState.LISTENING -> {
                sawListening = true
                logRound("第 $currentRound 轮: 进入 LISTENING")
            }
            CallState.THINKING -> {
                sawThinking = true
                val now = System.currentTimeMillis()
                stageTiming = stageTiming.copy(
                    thinkingStartMs = now,
                    asrEndMs = if (stageTiming.asrStartMs > 0) now else 0
                )
                if (sawListening) asrSucceeded = true
                logRound("第 $currentRound 轮: 进入 THINKING")
            }
            CallState.SPEAKING -> {
                sawSpeaking = true
                llmReturnedText = true
                val now = System.currentTimeMillis()
                stageTiming = stageTiming.copy(
                    speakingStartMs = now,
                    thinkingEndMs = if (stageTiming.thinkingStartMs > 0) now else 0
                )
                logRound("第 $currentRound 轮: 进入 SPEAKING")
                // 设置 TTS 完成超时
                handler.postDelayed({
                    if (host.getCallState() == CallState.SPEAKING && isRunning && !sawIdleRecovery) {
                        roundError = "TTS 完成超时 ${TTS_COMPLETE_TIMEOUT_MS}ms"
                        logRound("[TIMEOUT] TTS 完成超时")
                    }
                }, TTS_COMPLETE_TIMEOUT_MS)
            }
            CallState.IDLE -> {
                if (sawThinking || sawSpeaking) {
                    sawIdleRecovery = true
                    if (sawSpeaking) llmReturnedText = true
                    val now = System.currentTimeMillis()
                    stageTiming = stageTiming.copy(
                        idleRecoveryMs = now,
                        speakingEndMs = if (stageTiming.speakingStartMs > 0) now else 0
                    )
                    val duration = now - roundStartTime
                    logRound("第 $currentRound 轮: 回到 IDLE，耗时 ${duration}ms")

                    recordRoundResult(
                        input = TEST_INPUTS[(currentRound - 1) % TEST_INPUTS.size],
                        asrSuccess = if (testMode == TestMode.WITH_ASR) asrSucceeded else true,
                        llmSuccess = llmReturnedText,
                        ttsSuccess = sawSpeaking,
                        stateRecovery = true,
                        errorDetail = roundError
                    )

                    if (isRunning) {
                        val delay = if (testMode == TestMode.RAPID_MULTI_ROUND) 500L else ROUND_DELAY_MS
                        handler.postDelayed({ runNextRound() }, delay)
                    }
                }
            }
        }
    }

    private fun recordRoundResult(
        input: String,
        asrSuccess: Boolean,
        llmSuccess: Boolean,
        ttsSuccess: Boolean,
        stateRecovery: Boolean,
        errorDetail: String?
    ) {
        val duration = System.currentTimeMillis() - roundStartTime
        val result = RoundResult(
            round = currentRound,
            input = input,
            testMode = testMode,
            asrSuccess = asrSuccess,
            llmSuccess = llmSuccess,
            ttsSuccess = ttsSuccess,
            stateRecovery = stateRecovery,
            durationMs = duration,
            stageTiming = stageTiming,
            errorDetail = errorDetail,
            logs = roundLogs.toList()
        )
        results.add(result)

        val allPass = asrSuccess && llmSuccess && ttsSuccess && stateRecovery
        val status = if (allPass) "PASS" else "FAIL"
        val details = buildString {
            append("ASR=$asrSuccess LLM=$llmSuccess TTS=$ttsSuccess Recovery=$stateRecovery")
            errorDetail?.let { append(" | $it") }
        }
        logRound("第 $currentRound 轮结果: $status | ${duration}ms | $details")
    }

    private fun finishTest() {
        isRunning = false
        stateListener?.let { host.removeStateListener(it) }
        stateListener = null

        val totalResults = results.size
        val passed = results.count { it.asrSuccess && it.llmSuccess && it.ttsSuccess && it.stateRecovery }
        val failed = totalResults - passed
        val avgDuration = if (totalResults > 0) results.map { it.durationMs }.average().toLong() else 0L

        // 计算各阶段平均耗时
        val avgThinkingMs = results.filter { it.stageTiming.thinkingStartMs > 0 && it.stageTiming.thinkingEndMs > 0 }
            .map { it.stageTiming.thinkingEndMs - it.stageTiming.thinkingStartMs }.average().toLong()
        val avgSpeakingMs = results.filter { it.stageTiming.speakingStartMs > 0 && it.stageTiming.speakingEndMs > 0 }
            .map { it.stageTiming.speakingEndMs - it.stageTiming.speakingStartMs }.average().toLong()
        val avgAsrMs = results.filter { it.stageTiming.asrStartMs > 0 && it.stageTiming.asrEndMs > 0 }
            .map { it.stageTiming.asrEndMs - it.stageTiming.asrStartMs }.average().toLong()

        val summary = buildString {
            append("=== 自测完成 ===\n")
            append("总轮数: $totalResults, 通过: $passed, 失败: $failed\n")
            append("测试模式: ${testMode.name}\n")
            append("TTS引擎: ${host.currentTtsEngineName()}\n")
            append("平均总耗时: ${avgDuration}ms\n")
            if (avgAsrMs > 0) append("平均ASR耗时: ${avgAsrMs}ms\n")
            if (avgThinkingMs > 0) append("平均LLM耗时: ${avgThinkingMs}ms\n")
            if (avgSpeakingMs > 0) append("平均TTS耗时: ${avgSpeakingMs}ms\n")
            if (failed > 0) {
                append("\n失败详情:\n")
                results.filter { !it.asrSuccess || !it.llmSuccess || !it.ttsSuccess || !it.stateRecovery }.forEach { r ->
                    append("  第${r.round}轮: ASR=${r.asrSuccess} LLM=${r.llmSuccess} TTS=${r.ttsSuccess} Recovery=${r.stateRecovery}")
                    r.errorDetail?.let { append(" - $it") }
                    append("\n")
                }
            }
            append("\n各轮详细:\n")
            results.forEach { r ->
                val icon = if (r.asrSuccess && r.llmSuccess && r.ttsSuccess && r.stateRecovery) "✓" else "✗"
                append("  $icon 第${r.round}轮: ${r.durationMs}ms")
                if (r.stageTiming.asrStartMs > 0 && r.stageTiming.asrEndMs > 0)
                    append(" ASR=${r.stageTiming.asrEndMs - r.stageTiming.asrStartMs}ms")
                if (r.stageTiming.thinkingStartMs > 0 && r.stageTiming.thinkingEndMs > 0)
                    append(" LLM=${r.stageTiming.thinkingEndMs - r.stageTiming.thinkingStartMs}ms")
                if (r.stageTiming.speakingStartMs > 0 && r.stageTiming.speakingEndMs > 0)
                    append(" TTS=${r.stageTiming.speakingEndMs - r.stageTiming.speakingStartMs}ms")
                append("\n")
            }
        }

        Log.i(TAG, summary)
        host.onTestComplete(results, summary)
    }
}

/**
 * CallActivity 需实现的接口，供 PipelineSelfTest 调用
 * 注意：使用 getCallState() 方法而非 currentState 属性，避免与 Activity 的 State 枚举冲突
 */
interface TestHost {
    /** 获取当前通话状态 */
    fun getCallState(): PipelineSelfTest.CallState
    /** 模拟用户文本输入：直接调用 sendToLlm */
    fun simulateUserInput(text: String)
    /** 模拟 ASR 语音输入：启动录音并注入识别结果 */
    fun simulateAsrInput(text: String)
    /** TTS 引擎压力测试：根据轮次切换 TTS 引擎 */
    fun switchTtsEngineForTest(round: Int)
    /** 是否就绪（DUIX 已初始化完成） */
    fun isDuiXReady(): Boolean
    /** 当前 TTS 引擎名 */
    fun currentTtsEngineName(): String
    /** 添加状态变更监听器 */
    fun addStateListener(listener: (PipelineSelfTest.CallState) -> Unit)
    /** 移除状态变更监听器 */
    fun removeStateListener(listener: (PipelineSelfTest.CallState) -> Unit)
    /** 自测日志回调 */
    fun onTestLog(message: String)
    /** 自测完成回调 */
    fun onTestComplete(results: List<PipelineSelfTest.RoundResult>, summary: String)
}
