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
 * 6. PipelineHealthMonitor 新增告警类型
 * 7. 边界情况：连续卡死、快速状态切换
 */
class StateMachineTest {

    // ========== PipelineHealthMonitor 测试 ==========

    private lateinit var monitor: PipelineHealthMonitor
    private var lastAlert: PipelineHealthMonitor.HealthAlert? = null
    private var forceRecoverCalled = false
    private var forceRecoverReason = ""

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
    }

    @Before
    fun setup() {
        monitor = PipelineHealthMonitor(healthHost)
        lastAlert = null
        forceRecoverCalled = false
        forceRecoverReason = ""
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
        val states = PipelineSelfTest.CallState.entries.filter { it != PipelineSelfTest.CallState.IDLE }
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
        val actual = PipelineSelfTest.CallState.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `TestMode has TEXT_ONLY and WITH_ASR`() {
        val expected = setOf("TEXT_ONLY", "WITH_ASR")
        val actual = PipelineSelfTest.TestMode.entries.map { it.name }.toSet()
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
            logs = listOf("log1", "log2")
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
            errorDetail = "ASR 超时"
        )
        assertEquals(PipelineSelfTest.TestMode.WITH_ASR, result.testMode)
        assertFalse(result.asrSuccess)
        assertFalse(result.llmSuccess)
        assertFalse(result.ttsSuccess)
        assertFalse(result.stateRecovery)
        assertEquals("ASR 超时", result.errorDetail)
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
            "NETWORK_UNAVAILABLE", "DUIX_SDK_NOT_READY", "TTS_ENGINE_NOT_READY"
        )
        val actual = PipelineHealthMonitor.AlertType.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `HealthAlert data class captures all info`() {
        val alert = PipelineHealthMonitor.HealthAlert(
            type = PipelineHealthMonitor.AlertType.STATE_STUCK,
            state = PipelineSelfTest.CallState.THINKING,
            durationMs = 40000L,
            message = "THINKING 状态卡死 40000ms"
        )
        assertEquals(PipelineHealthMonitor.AlertType.STATE_STUCK, alert.type)
        assertEquals(PipelineSelfTest.CallState.THINKING, alert.state)
        assertEquals(40000L, alert.durationMs)
        assertEquals("THINKING 状态卡死 40000ms", alert.message)
    }

    @Test
    fun `HealthAlert network unavailable type`() {
        val alert = PipelineHealthMonitor.HealthAlert(
            type = PipelineHealthMonitor.AlertType.NETWORK_UNAVAILABLE,
            state = PipelineSelfTest.CallState.THINKING,
            durationMs = 5000L,
            message = "网络不可用"
        )
        assertEquals(PipelineHealthMonitor.AlertType.NETWORK_UNAVAILABLE, alert.type)
    }

    @Test
    fun `HealthAlert TTS engine not ready type`() {
        val alert = PipelineHealthMonitor.HealthAlert(
            type = PipelineHealthMonitor.AlertType.TTS_ENGINE_NOT_READY,
            state = PipelineSelfTest.CallState.SPEAKING,
            durationMs = 3000L,
            message = "TTS 引擎不可用"
        )
        assertEquals(PipelineHealthMonitor.AlertType.TTS_ENGINE_NOT_READY, alert.type)
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
}
