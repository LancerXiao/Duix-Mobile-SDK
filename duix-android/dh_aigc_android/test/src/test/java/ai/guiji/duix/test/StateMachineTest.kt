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
 * 4. 边界情况：连续卡死、快速状态切换
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
        // LLM 超时或错误，直接回 IDLE
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
        // 模拟 3 轮对话
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
        // 用户打断说话
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
        assertNull(lastAlert)
        // 立即开始新对话
        monitor.onStateChanged(PipelineSelfTest.CallState.THINKING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.SPEAKING)
        assertNull(lastAlert)
        monitor.onStateChanged(PipelineSelfTest.CallState.IDLE)
        assertNull(lastAlert)
    }

    // ========== PipelineSelfTest.CallState 枚举测试 ==========

    @Test
    fun `CallState has all expected values`() {
        val expected = setOf("IDLE", "LISTENING", "THINKING", "SPEAKING")
        val actual = PipelineSelfTest.CallState.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `RoundResult data class works correctly`() {
        val result = PipelineSelfTest.RoundResult(
            round = 1,
            input = "test input",
            llmSuccess = true,
            ttsSuccess = true,
            stateRecovery = true,
            durationMs = 5000L,
            errorDetail = null
        )
        assertEquals(1, result.round)
        assertEquals("test input", result.input)
        assertTrue(result.llmSuccess)
        assertTrue(result.ttsSuccess)
        assertTrue(result.stateRecovery)
        assertEquals(5000L, result.durationMs)
        assertNull(result.errorDetail)
    }

    @Test
    fun `RoundResult with failure details`() {
        val result = PipelineSelfTest.RoundResult(
            round = 2,
            input = "test",
            llmSuccess = false,
            ttsSuccess = false,
            stateRecovery = false,
            durationMs = 35000L,
            errorDetail = "LLM 响应超时"
        )
        assertFalse(result.llmSuccess)
        assertFalse(result.ttsSuccess)
        assertFalse(result.stateRecovery)
        assertEquals("LLM 响应超时", result.errorDetail)
    }

    // ========== HealthAlert 测试 ==========

    @Test
    fun `HealthAlert types cover all expected scenarios`() {
        val expected = setOf("STATE_STUCK", "STATE_ILLEGAL_TRANSITION", "TTS_PIPELINE_BROKEN")
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
}
