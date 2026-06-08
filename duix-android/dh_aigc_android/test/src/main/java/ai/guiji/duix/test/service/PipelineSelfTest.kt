package ai.guiji.duix.test.service

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 管线端到端自测：模拟用户行为，自动跑通 LLM→TTS→数字人 全链路
 *
 * 测试流程：
 * 1. 模拟用户输入文本（跳过 ASR，直接调 sendToLlm）
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

    enum class CallState { IDLE, LISTENING, THINKING, SPEAKING }

    data class RoundResult(
        val round: Int,
        val input: String,
        val llmSuccess: Boolean,
        val ttsSuccess: Boolean,
        val stateRecovery: Boolean,
        val durationMs: Long,
        val errorDetail: String? = null
    )

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var currentRound = 0
    private var totalRounds = DEFAULT_ROUNDS
    private val results = mutableListOf<RoundResult>()

    // 每轮的状态追踪
    private var roundStartTime = 0L
    private var sawThinking = false
    private var sawSpeaking = false
    private var sawIdleRecovery = false
    private var llmReturnedText = false
    private var roundError: String? = null

    // 状态监听器
    private var stateListener: ((CallState) -> Unit)? = null

    /** 开始自测 */
    fun start(rounds: Int = DEFAULT_ROUNDS) {
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
        results.clear()

        Log.i(TAG, "=== 管线端到端自测开始 === 共 $totalRounds 轮, TTS=${host.currentTtsEngineName()}")
        host.onTestLog("自测开始: $totalRounds 轮, TTS=${host.currentTtsEngineName()}")

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

    private fun runNextRound() {
        if (!isRunning || currentRound >= totalRounds) {
            finishTest()
            return
        }

        currentRound++
        val input = TEST_INPUTS[(currentRound - 1) % TEST_INPUTS.size]

        // 重置本轮追踪状态
        roundStartTime = System.currentTimeMillis()
        sawThinking = false
        sawSpeaking = false
        sawIdleRecovery = false
        llmReturnedText = false
        roundError = null

        Log.i(TAG, "--- 第 $currentRound/$totalRounds 轮 --- 输入: $input")
        host.onTestLog("第 $currentRound 轮: \"${input.take(20)}\"")

        // 检查当前状态必须是 IDLE
        if (host.getCallState() != CallState.IDLE) {
            Log.w(TAG, "第 $currentRound 轮: 当前状态=${host.getCallState()}，非 IDLE，等待...")
            host.onTestLog("[WARN] 状态=${host.getCallState()}，等待 IDLE...")
            // 等待 5 秒再试
            handler.postDelayed({
                if (host.getCallState() == CallState.IDLE) {
                    startRoundInput(input)
                } else {
                    // 强制记录失败
                    recordRoundResult(input, llmSuccess = false, ttsSuccess = false,
                        stateRecovery = false, "状态卡在 ${host.getCallState()}，无法开始")
                    handler.postDelayed({ runNextRound() }, ROUND_DELAY_MS)
                }
            }, 5000L)
            return
        }

        startRoundInput(input)
    }

    private fun startRoundInput(input: String) {
        // 模拟用户输入
        host.simulateUserInput(input)

        // 设置 LLM 响应超时
        handler.postDelayed({
            if (!llmReturnedText && isRunning) {
                Log.e(TAG, "第 $currentRound 轮: LLM 响应超时")
                roundError = "LLM 响应超时 ${LLM_RESPONSE_TIMEOUT_MS}ms"
            }
        }, LLM_RESPONSE_TIMEOUT_MS)
    }

    private fun onStateChanged(state: CallState) {
        if (!isRunning) return

        when (state) {
            CallState.THINKING -> {
                sawThinking = true
                Log.i(TAG, "第 $currentRound 轮: 进入 THINKING")
            }
            CallState.SPEAKING -> {
                sawSpeaking = true
                llmReturnedText = true  // 能进入 SPEAKING 说明 LLM 有返回
                Log.i(TAG, "第 $currentRound 轮: 进入 SPEAKING")
                // 设置 TTS 完成超时
                handler.postDelayed({
                    if (host.getCallState() == CallState.SPEAKING && isRunning && !sawIdleRecovery) {
                        Log.e(TAG, "第 $currentRound 轮: TTS 完成超时")
                        roundError = "TTS 完成超时 ${TTS_COMPLETE_TIMEOUT_MS}ms"
                    }
                }, TTS_COMPLETE_TIMEOUT_MS)
            }
            CallState.IDLE -> {
                if (sawThinking || sawSpeaking) {
                    // 从 THINKING/SPEAKING 回到 IDLE = 一轮完成
                    sawIdleRecovery = true
                    if (sawSpeaking) llmReturnedText = true
                    val duration = System.currentTimeMillis() - roundStartTime
                    Log.i(TAG, "第 $currentRound 轮: 回到 IDLE，耗时 ${duration}ms")

                    // 记录结果
                    recordRoundResult(
                        input = TEST_INPUTS[(currentRound - 1) % TEST_INPUTS.size],
                        llmSuccess = llmReturnedText,
                        ttsSuccess = sawSpeaking,
                        stateRecovery = true,
                        errorDetail = roundError
                    )

                    // 启动下一轮
                    if (isRunning) {
                        handler.postDelayed({ runNextRound() }, ROUND_DELAY_MS)
                    }
                }
            }
            CallState.LISTENING -> {
                // 自测模式不会进入 LISTENING（跳过 ASR）
                Log.d(TAG, "第 $currentRound 轮: 意外进入 LISTENING")
            }
        }
    }

    private fun recordRoundResult(
        input: String,
        llmSuccess: Boolean,
        ttsSuccess: Boolean,
        stateRecovery: Boolean,
        errorDetail: String?
    ) {
        val duration = System.currentTimeMillis() - roundStartTime
        val result = RoundResult(
            round = currentRound,
            input = input,
            llmSuccess = llmSuccess,
            ttsSuccess = ttsSuccess,
            stateRecovery = stateRecovery,
            durationMs = duration,
            errorDetail = errorDetail
        )
        results.add(result)

        val status = if (llmSuccess && ttsSuccess && stateRecovery) "PASS" else "FAIL"
        Log.i(TAG, "第 $currentRound 轮结果: $status | LLM=$llmSuccess TTS=$ttsSuccess Recovery=$stateRecovery | ${duration}ms${errorDetail?.let { " | $it" } ?: ""}")
        host.onTestLog("第 $currentRound 轮: $status (${duration}ms)${errorDetail?.let { " - $it" } ?: ""}")
    }

    private fun finishTest() {
        isRunning = false
        stateListener?.let { host.removeStateListener(it) }
        stateListener = null

        val totalResults = results.size
        val passed = results.count { it.llmSuccess && it.ttsSuccess && it.stateRecovery }
        val failed = totalResults - passed
        val avgDuration = if (totalResults > 0) results.map { it.durationMs }.average().toLong() else 0L

        val summary = buildString {
            append("=== 自测完成 ===\n")
            append("总轮数: $totalResults, 通过: $passed, 失败: $failed\n")
            append("平均耗时: ${avgDuration}ms\n")
            append("TTS引擎: ${host.currentTtsEngineName()}\n")
            if (failed > 0) {
                append("\n失败详情:\n")
                results.filter { !it.llmSuccess || !it.ttsSuccess || !it.stateRecovery }.forEach { r ->
                    append("  第${r.round}轮: LLM=${r.llmSuccess} TTS=${r.ttsSuccess} Recovery=${r.stateRecovery}")
                    r.errorDetail?.let { append(" - $it") }
                    append("\n")
                }
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
    /** 模拟用户输入：直接调用 sendToLlm */
    fun simulateUserInput(text: String)
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
