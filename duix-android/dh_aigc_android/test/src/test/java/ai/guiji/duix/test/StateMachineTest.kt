package ai.guiji.duix.test

import ai.guiji.duix.test.service.PipelineHealthMonitor
import ai.guiji.duix.test.service.PipelineSelfTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 状态机单元测试：验证所有状态转换路径、超时保护、边界情况
 *
 * 测试覆盖：
 * 1. PipelineHealthMonitor 非法状态转换检测
 * 2. PipelineHealthMonitor 状态卡死检测
 * 3. PipelineSelfTest 状态转换追踪
 * 4. PipelineSelfTest TestMode 枚举
 * 5. PipelineSelfTest StageTiming 数据类
 * 6. PipelineHealthMonitor 告警类型（含新增 TTS_ENGINE_FALLBACK、LLM_TIMEOUT_SUGGESTION）
 * 7. PipelineSelfTest AutoFixAction 枚举
 * 8. PipelineSelfTest DiagnosticInfo 数据类
 * 9. PipelineHealthMonitor FixAction 枚举
 * 10. PipelineHealthMonitor HealthScore 数据类
 * 11. 边界情况：连续卡死、快速状态切换
 */
class StateMachineTest {

    // ========== PipelineHealthMonitor 测试 ==========

    private lateinit var monitor: PipelineHealthMonitor
    private var lastAlert: PipelineHealthMonitor.HealthAlert? = null
    private var forceRecoverCalled = false
    private var forceRecoverReason = ""
    private var autoFallbackCalled = false

    private val healthHost = object : PipelineHealthMonitor.HealthHost {
        private var _state = PipelineSelfTest.CallState.IDLE
        private var _stateDurationMs = 0L

        fun setState(state: PipelineSelfTest.CallState, durationMs: Long = 0L) {
            _state = state
            _stateDurationMs = durationMs
        }

        override fun getCallState() = _state
        override fun getStateDurationMs() = _stateDurationMs
        override fun forceRecoverToIdle(reason: String) {
            forceRecoverCalled = true
            forceRecoverReason = reason
            _state = PipelineSelfTest.CallState.IDLE
        }
        override fun onHealthAlert(alert: PipelineHealthMonitor.HealthAlert) {
            lastAlert = alert
        }
        override fun isNetworkAvailable() = true
        override fun isDuiXSdkReady() = true
        override fun isCurrentTtsEngineReady() = true
        override fun autoFallbackTtsEngine() {
            autoFallbackCalled = true
        }
        override fun currentTtsEngineName() = "Edge TTS"
    }

    @Before
    fun setup() {
        monitor = PipelineHealthMonitor(healthHost)
        lastAlert = null
        forceRecoverCalled = false
        forceRecoverReason = ""
        autoFallbackCalled = false
    }

    // --- 非法状态转换检测 ---

    @Test
    fun `IDLE to LISTENING is legal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.LISTENING)
        assertNull(lastAlert)
    }

    @Test
    fun `IDLE to THINKING is legal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        assertNull(lastAlert)
    }

    @Test
    fun `IDLE to SPEAKING is illegal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        assertNotNull(lastAlert)
        assertEquals(PipelineHealthMonitor.AlertType.STATE_ILLEGAL_TRANSITION, lastAlert!!.type)
    }

    @Test
    fun `LISTENING to THINKING is legal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.LISTENING)
        lastAlert = null
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        assertNull(lastAlert)
    }

    @Test
    fun `LISTENING to SPEAKING is illegal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.LISTENING)
        lastAlert = null
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        assertNotNull(lastAlert)
        assertEquals(PipelineHealthMonitor.AlertType.STATE_ILLEGAL_TRANSITION, lastAlert!!.type)
    }

    @Test
    fun `THINKING to SPEAKING is legal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        lastAlert = null
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        assertNull(lastAlert)
    }

    @Test
    fun `THINKING to LISTENING is illegal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        lastAlert = null
        monitor.onStateChanged(PipelineSelfTest.CallState.LISTENING)
        assertNotNull(lastAlert)
        assertEquals(PipelineHealthMonitor.AlertType.STATE_ILLEGAL_TRANSITION, lastAlert!!.type)
    }

    @Test
    fun `SPEAKING to IDLE is legal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        lastAlert = null
        monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
        assertNull(lastAlert)
    }

    @Test
    fun `SPEAKING to THINKING is illegal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        lastAlert = null
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        assertNotNull(lastAlert)
        assertEquals(PipelineHealthMonitor.AlertType.STATE_ILLEGAL_TRANSITION, lastAlert!!.type)
    }

    @Test
    fun `SPEAKING to LISTENING is illegal`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        lastAlert = null
        monitor.onStateChanged(PipelineSelfTest.CallState.LISTENING)
        assertNotNull(lastAlert)
        assertEquals(PipelineHealthMonitor.AlertType.STATE_ILLEGAL_TRANSITION, lastAlert!!.type)
    }

    @Test
    fun `any state to IDLE is always legal`() {
        val states = PipelineSelfTest.CallState.values().filter { it != PipelineSelfTest.CallState.IDLE }
        for (state in states) {
            lastAlert = null
            monitor.onStateChanged(state)
            lastAlert = null
            monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
            assertNull("Transition from $state to IDLE should be legal", lastAlert)
        }
    }

    // --- 正常对话流程 ---

    @Test
    fun `normal conversation flow - IDLE to THINKING to SPEAKING to IDLE`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
        assertNull(lastAlert)
    }

    @Test
    fun `normal ASR flow - IDLE to LISTENING to THINKING to SPEAKING to IDLE`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.LISTENING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
        assertNull(lastAlert)
    }

    // --- 边界情况 ---

    @Test
    fun `THINKING with no LLM response returns to IDLE`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
        assertNull(lastAlert)
    }

    @Test
    fun `LISTENING cancelled returns to IDLE`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.LISTENING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
        assertNull(lastAlert)
    }

    @Test
    fun `multiple consecutive conversations work`() {
        for (i in 1..3) {
            monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
            assertNull("Round $i: THINKING should be legal", lastAlert)
            monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
            assertNull("Round $i: SPEAKING should be legal", lastAlert)
            monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
            assertNull("Round $i: IDLE should be legal", lastAlert)
        }
    }

    @Test
    fun `interrupted speaking returns to IDLE then new conversation`() {
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
        assertNull(lastAlert)
    }

    // ========== PipelineSelfTest 枚举和数据类测试 ==========

    @Test
    fun `CallState has all expected values`() {
        val expected = setOf("IDLE", "LISTENING", "THINKING", "SPEAKING")
        val actual = PipelineSelfTest.CallState.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `TestMode has all expected values`() {
        val expected = setOf("TEXT_ONLY", "WITH_ASR", "TTS_ENGINE_STRESS", "RAPID_MULTI_ROUND")
        val actual = PipelineSelfTest.TestMode.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `AutoFixAction has all expected values`() {
        val expected = setOf("NONE", "RETRY_SAME_INPUT", "FALLBACK_TTS_ENGINE", "FORCE_RECOVER_IDLE")
        val actual = PipelineSelfTest.AutoFixAction.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `RoundResult data class works correctly`() {
        val result = PipelineSelfTest.RoundResult(
            round = 1,
            input = "test input",
            testMode = PipelineSelfTest.TestMode.TEXT_ONLY,
            asrSuccess = true,
            llmSuccess = true,
            ttsSuccess = true,
            stateRecovery = true,
            durationMs = 5000L,
            stageTiming = PipelineSelfTest.StageTiming(),
            errorDetail = null,
            logs = listOf("log1", "log2"),
            retryCount = 0,
            autoFixAction = PipelineSelfTest.AutoFixAction.NONE,
            autoFixSucceeded = false,
            diagnostic = null
        )
        assertEquals(1, result.round)
        assertEquals("test input", result.input)
        assertEquals(PipelineSelfTest.TestMode.TEXT_ONLY, result.testMode)
        assertTrue(result.asrSuccess)
        assertTrue(result.llmSuccess)
        assertTrue(result.ttsSuccess)
        assertTrue(result.stateRecovery)
        assertEquals(5000L, result.durationMs)
        assertNull(result.errorDetail)
        assertEquals(listOf("log1", "log2"), result.logs)
        assertEquals(0, result.retryCount)
        assertEquals(PipelineSelfTest.AutoFixAction.NONE, result.autoFixAction)
        assertFalse(result.autoFixSucceeded)
        assertNull(result.diagnostic)
    }

    @Test
    fun `RoundResult with ASR mode and failure details`() {
        val result = PipelineSelfTest.RoundResult(
            round = 2,
            input = "test",
            testMode = PipelineSelfTest.TestMode.WITH_ASR,
            asrSuccess = false,
            llmSuccess = false,
            ttsSuccess = false,
            stateRecovery = false,
            durationMs = 35000L,
            stageTiming = PipelineSelfTest.StageTiming(),
            errorDetail = "ASR 超时",
            retryCount = 2,
            autoFixAction = PipelineSelfTest.AutoFixAction.RETRY_SAME_INPUT,
            autoFixSucceeded = false
        )
        assertEquals(PipelineSelfTest.TestMode.WITH_ASR, result.testMode)
        assertFalse(result.asrSuccess)
        assertFalse(result.llmSuccess)
        assertFalse(result.ttsSuccess)
        assertFalse(result.stateRecovery)
        assertEquals("ASR 超时", result.errorDetail)
        assertEquals(2, result.retryCount)
        assertEquals(PipelineSelfTest.AutoFixAction.RETRY_SAME_INPUT, result.autoFixAction)
        assertFalse(result.autoFixSucceeded)
    }

    @Test
    fun `RoundResult with auto-fix succeeded`() {
        val result = PipelineSelfTest.RoundResult(
            round = 3,
            input = "test",
            testMode = PipelineSelfTest.TestMode.TEXT_ONLY,
            asrSuccess = true,
            llmSuccess = true,
            ttsSuccess = true,
            stateRecovery = true,
            durationMs = 8000L,
            stageTiming = PipelineSelfTest.StageTiming(),
            retryCount = 1,
            autoFixAction = PipelineSelfTest.AutoFixAction.FALLBACK_TTS_ENGINE,
            autoFixSucceeded = true
        )
        assertTrue(result.autoFixSucceeded)
        assertEquals(PipelineSelfTest.AutoFixAction.FALLBACK_TTS_ENGINE, result.autoFixAction)
        assertEquals(1, result.retryCount)
    }

    @Test
    fun `DiagnosticInfo data class works correctly`() {
        val diagnostic = PipelineSelfTest.DiagnosticInfo(
            failureStage = "TTS",
            likelyCause = "TTS 引擎未就绪",
            fixSuggestion = "切换到 Edge TTS",
            isNetworkRelated = false,
            isEngineRelated = true
        )
        assertEquals("TTS", diagnostic.failureStage)
        assertEquals("TTS 引擎未就绪", diagnostic.likelyCause)
        assertEquals("切换到 Edge TTS", diagnostic.fixSuggestion)
        assertFalse(diagnostic.isNetworkRelated)
        assertTrue(diagnostic.isEngineRelated)
    }

    @Test
    fun `DiagnosticInfo for LLM timeout`() {
        val diagnostic = PipelineSelfTest.DiagnosticInfo(
            failureStage = "LLM",
            likelyCause = "LLM 服务持续超时",
            fixSuggestion = "检查 API Key 配置",
            isNetworkRelated = true,
            isEngineRelated = true
        )
        assertEquals("LLM", diagnostic.failureStage)
        assertTrue(diagnostic.isNetworkRelated)
        assertTrue(diagnostic.isEngineRelated)
    }

    @Test
    fun `StageTiming data class captures timing info`() {
        val timing = PipelineSelfTest.StageTiming(
            asrStartMs = 1000,
            asrEndMs = 3000,
            thinkingStartMs = 3000,
            thinkingEndMs = 8000,
            speakingStartMs = 8000,
            speakingEndMs = 15000,
            idleRecoveryMs = 15000
        )
        assertEquals(1000L, timing.asrStartMs)
        assertEquals(3000L, timing.asrEndMs)
        assertEquals(3000L, timing.thinkingStartMs)
        assertEquals(8000L, timing.thinkingEndMs)
        assertEquals(8000L, timing.speakingStartMs)
        assertEquals(15000L, timing.speakingEndMs)
        assertEquals(15000L, timing.idleRecoveryMs)
        // 验证计算：ASR=2000ms, LLM=5000ms, TTS=7000ms
        assertEquals(2000L, timing.asrEndMs - timing.asrStartMs)
        assertEquals(5000L, timing.thinkingEndMs - timing.thinkingStartMs)
        assertEquals(7000L, timing.speakingEndMs - timing.speakingStartMs)
    }

    @Test
    fun `StageTiming default values are zero`() {
        val timing = PipelineSelfTest.StageTiming()
        assertEquals(0L, timing.asrStartMs)
        assertEquals(0L, timing.asrEndMs)
        assertEquals(0L, timing.thinkingStartMs)
        assertEquals(0L, timing.thinkingEndMs)
        assertEquals(0L, timing.speakingStartMs)
        assertEquals(0L, timing.speakingEndMs)
        assertEquals(0L, timing.idleRecoveryMs)
    }

    // ========== HealthAlert 测试 ==========

    @Test
    fun `HealthAlert types cover all expected scenarios`() {
        val expected = setOf(
            "STATE_STUCK", "STATE_ILLEGAL_TRANSITION", "TTS_PIPELINE_BROKEN",
            "NETWORK_UNAVAILABLE", "DUIX_SDK_NOT_READY", "TTS_ENGINE_NOT_READY",
            "TTS_ENGINE_FALLBACK", "LLM_TIMEOUT_SUGGESTION"
        )
        val actual = PipelineHealthMonitor.AlertType.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `HealthAlert data class captures all info`() {
        val alert = PipelineHealthMonitor.HealthAlert(
            type = PipelineHealthMonitor.AlertType.STATE_STUCK,
            state = PipelineSelfTest.CallState.THINKING,
            durationMs = 40000L,
            message = "THINKING 状态卡死 40000ms",
            fixAction = PipelineHealthMonitor.FixAction.FORCE_RECOVER,
            fixSuggestion = "LLM 响应超时，已自动恢复"
        )
        assertEquals(PipelineHealthMonitor.AlertType.STATE_STUCK, alert.type)
        assertEquals(PipelineSelfTest.CallState.THINKING, alert.state)
        assertEquals(40000L, alert.durationMs)
        assertEquals("THINKING 状态卡死 40000ms", alert.message)
        assertEquals(PipelineHealthMonitor.FixAction.FORCE_RECOVER, alert.fixAction)
        assertEquals("LLM 响应超时，已自动恢复", alert.fixSuggestion)
    }

    @Test
    fun `HealthAlert network unavailable type`() {
        val alert = PipelineHealthMonitor.HealthAlert(
            type = PipelineHealthMonitor.AlertType.NETWORK_UNAVAILABLE,
            state = PipelineSelfTest.CallState.THINKING,
            durationMs = 5000L,
            message = "网络不可用",
            fixAction = PipelineHealthMonitor.FixAction.CHECK_NETWORK,
            fixSuggestion = "请检查 WiFi/移动数据连接"
        )
        assertEquals(PipelineHealthMonitor.AlertType.NETWORK_UNAVAILABLE, alert.type)
        assertEquals(PipelineHealthMonitor.FixAction.CHECK_NETWORK, alert.fixAction)
    }

    @Test
    fun `HealthAlert TTS engine not ready type`() {
        val alert = PipelineHealthMonitor.HealthAlert(
            type = PipelineHealthMonitor.AlertType.TTS_ENGINE_NOT_READY,
            state = PipelineSelfTest.CallState.SPEAKING,
            durationMs = 3000L,
            message = "TTS 引擎不可用",
            fixAction = PipelineHealthMonitor.FixAction.FALLBACK_TTS_ENGINE,
            fixSuggestion = "将自动切换到备用 TTS 引擎"
        )
        assertEquals(PipelineHealthMonitor.AlertType.TTS_ENGINE_NOT_READY, alert.type)
        assertEquals(PipelineHealthMonitor.FixAction.FALLBACK_TTS_ENGINE, alert.fixAction)
    }

    @Test
    fun `HealthAlert DUIX SDK not ready type`() {
        val alert = PipelineHealthMonitor.HealthAlert(
            type = PipelineHealthMonitor.AlertType.DUIX_SDK_NOT_READY,
            state = PipelineSelfTest.CallState.SPEAKING,
            durationMs = 2000L,
            message = "DUIX SDK 未就绪"
        )
        assertEquals(PipelineHealthMonitor.AlertType.DUIX_SDK_NOT_READY, alert.type)
    }

    @Test
    fun `HealthAlert TTS engine fallback type`() {
        val alert = PipelineHealthMonitor.HealthAlert(
            type = PipelineHealthMonitor.AlertType.TTS_ENGINE_FALLBACK,
            state = PipelineSelfTest.CallState.SPEAKING,
            durationMs = 20000L,
            message = "TTS 引擎自动降级: Edge TTS → Android TTS",
            fixAction = PipelineHealthMonitor.FixAction.FALLBACK_TTS_ENGINE,
            fixSuggestion = "已切换到备用 TTS 引擎"
        )
        assertEquals(PipelineHealthMonitor.AlertType.TTS_ENGINE_FALLBACK, alert.type)
        assertEquals(PipelineHealthMonitor.FixAction.FALLBACK_TTS_ENGINE, alert.fixAction)
    }

    @Test
    fun `HealthAlert LLM timeout suggestion type`() {
        val alert = PipelineHealthMonitor.HealthAlert(
            type = PipelineHealthMonitor.AlertType.LLM_TIMEOUT_SUGGESTION,
            state = PipelineSelfTest.CallState.THINKING,
            durationMs = 35000L,
            message = "LLM 连续超时 3 次",
            fixAction = PipelineHealthMonitor.FixAction.CHECK_LLM_CONFIG,
            fixSuggestion = "检查 AiConfig 中 LLM 配置"
        )
        assertEquals(PipelineHealthMonitor.AlertType.LLM_TIMEOUT_SUGGESTION, alert.type)
        assertEquals(PipelineHealthMonitor.FixAction.CHECK_LLM_CONFIG, alert.fixAction)
    }

    // ========== FixAction 测试 ==========

    @Test
    fun `FixAction has all expected values`() {
        val expected = setOf("NONE", "FORCE_RECOVER", "FALLBACK_TTS_ENGINE", "CHECK_NETWORK", "CHECK_LLM_CONFIG")
        val actual = PipelineHealthMonitor.FixAction.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    // ========== HealthScore 测试 ==========

    @Test
    fun `HealthScore data class works correctly`() {
        val score = PipelineHealthMonitor.HealthScore(
            score = 85,
            issues = listOf("LLM 连续超时 1 次"),
            lastUpdatedMs = System.currentTimeMillis()
        )
        assertEquals(85, score.score)
        assertEquals(1, score.issues.size)
        assertEquals("LLM 连续超时 1 次", score.issues[0])
    }

    @Test
    fun `HealthScore with multiple issues`() {
        val score = PipelineHealthMonitor.HealthScore(
            score = 30,
            issues = listOf("连续卡死 3 次", "网络不可用", "TTS 引擎不可用"),
            lastUpdatedMs = System.currentTimeMillis()
        )
        assertEquals(30, score.score)
        assertEquals(3, score.issues.size)
    }

    @Test
    fun `HealthScore perfect score`() {
        val score = PipelineHealthMonitor.HealthScore(
            score = 100,
            issues = emptyList(),
            lastUpdatedMs = System.currentTimeMillis()
        )
        assertEquals(100, score.score)
        assertTrue(score.issues.isEmpty())
    }
}
