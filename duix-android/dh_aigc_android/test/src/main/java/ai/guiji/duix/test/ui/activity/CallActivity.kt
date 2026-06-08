package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.loader.ModelInfo
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityCallBinding
import ai.guiji.duix.test.service.AndroidAsrService
import ai.guiji.duix.test.service.AndroidTtsService
import ai.guiji.duix.test.service.AiConfig
import ai.guiji.duix.test.service.AsrFallbackManager
import ai.guiji.duix.test.service.EdgeTtsService
import ai.guiji.duix.test.service.HybridAsrService
import ai.guiji.duix.test.service.LlmService
import ai.guiji.duix.test.service.Mp3ToPcmConverter
import ai.guiji.duix.test.service.PcmResampler
import ai.guiji.duix.test.service.QwenTtsService
import ai.guiji.duix.test.ui.MessageData
import ai.guiji.duix.test.ui.adapter.MessageAdapter
import ai.guiji.duix.test.util.PermissionManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.bumptech.glide.Glide

class CallActivity : BaseActivity() {

    companion object {
        const val GL_CONTEXT_VERSION = 2
        private const val AUTO_LISTEN_DELAY_MS = 1200L
        // ASR partial 文字稳定超时（毫秒）：超过这个时间 partial 不变就认为说话结束
        private const val STABLE_TEXT_TIMEOUT_MS = 1500L
        // [Bug fix] SPEAKING 状态超时保护：如果 DUIX SDK 没有回调 AUDIO_PLAY_END，
        // 15 秒后自动恢复到 IDLE，防止状态卡死
        private const val SPEAKING_TIMEOUT_MS = 8000L
        // [Bug fix] THINKING 状态超时保护：如果 LLM 请求挂起无响应，
        // 30 秒后自动恢复到 IDLE，防止状态永远卡在 THINKING
        private const val THINKING_TIMEOUT_MS = 30000L
        // 音频采样率常量
        // Qwen TTS (qwen3-tts-flash-realtime) 固定输出 24kHz PCM
        private const val QWEN_TTS_SAMPLE_RATE = 24000
        // DUIX SDK 内部使用 16kHz（见 duix-sdk/src/main/cpp/dhmfcc/mfcc.cpp 的 MFCC_RATE = 16000）
        private const val DUIX_SAMPLE_RATE = 16000
        // SharedPreferences
        private const val PREFS_NAME = "duix_prefs"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_ASR_ENGINE = "asr_engine"
        private const val KEY_MIC_INTERACTION_MODE = "mic_interaction_mode"
    }

    enum class State {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    private var modelName = ""
    private var modelUrl = ""
    private var debug = false

    private lateinit var binding: ActivityCallBinding
    private var duix: DUIX? = null
    private var mDUIXRender: DUIXRenderer? = null
    private var mModelInfo: ModelInfo? = null

    // AI服务 - 使用 lateinit 避免在 mContext 赋值前初始化导致闪退
    private val llmService = LlmService()
    private lateinit var asrService: HybridAsrService
    private val qwenTtsService = QwenTtsService()
    private val edgeTtsService = EdgeTtsService()
    private lateinit var androidTtsService: AndroidTtsService
    private lateinit var mp3ToPcmConverter: Mp3ToPcmConverter

    // TTS引擎选择
    private enum class TtsEngine { QWEN_TTS, EDGE_TTS, ANDROID_TTS }
    private val ttsEngineCycle = listOf(TtsEngine.QWEN_TTS, TtsEngine.EDGE_TTS, TtsEngine.ANDROID_TTS)
    private var currentTtsEngine = TtsEngine.EDGE_TTS
    private var edgeTtsFailCount = 0

    // ASR 引擎选择 (Phase 1.2 骨架) - 顶部可见+可点击循环切换+持久化
    // 骨架阶段：仅 UI/持久化，未接通 HybridAsrService.PREFERRED_ENGINE
    // 等 Phase 1.1 [DIAG] 实测定位根因后再接通，避免破坏当前诊断链路
    private enum class AsrEngine { DASHSCOPE, ANDROID, DISABLED }
    private val asrEngineCycle = listOf(AsrEngine.DASHSCOPE, AsrEngine.ANDROID, AsrEngine.DISABLED)
    private var currentAsrEngine = AsrEngine.DASHSCOPE

    // ASR Fallback 决策器 (Phase 1.3 骨架) - 纯逻辑决策，不接通 HybridAsrService
    // 等 Phase 1.1 [DIAG] 反馈出根因后再接通具体分支
    private val asrFallbackManager = AsrFallbackManager()
    private var lastFallbackAction: AsrFallbackManager.Action? = null

    // 麦克风交互模式 (Phase 1.4 骨架) - 默认 LONG_PRESS（豆包/小爱风格：按下开始、抬起结束）
    // 也支持 PRESS_ONCE（再按一次结束，保留以兼容老用户习惯）
    // 骨架阶段：仅加字段 + 长按分支 + 持久化
    // 切换入口将在 Phase 1.4 接通时增加（如长按 toolbar 标题 5 次进调试模式）
    private enum class MicInteractionMode { PRESS_ONCE, LONG_PRESS }
    private var micInteractionMode = MicInteractionMode.LONG_PRESS

    // [Phase 2.2] 多轮消息历史列表
    private lateinit var messageAdapter: MessageAdapter

    // 状态管理
    private var currentState = State.IDLE
    private var isDuiXReady = false
    private var isMuted = false
    // 用户主动停止 ASR 的标记位，防止停止后迟到的 ASR 回调改变状态
    private var userStoppedAsr = false
    // 累积的 ASR partial 文本（VAD 未触发时保存）
    private var lastPartialText = ""
    // 上次收到 partial 的时间（用于检测文字稳定）
    private var lastPartialTimeMs = 0L
    // 文字稳定检测：连续 1.5 秒 partial 文本未变，自动认为说话结束
    private val handlerAutoFinalize = Handler(Looper.getMainLooper())
    private val autoFinalizeRunnable = Runnable {
        if (currentState != State.LISTENING) return@Runnable
        if (userStoppedAsr) return@Runnable
        val text = lastPartialText
        if (text.isEmpty()) return@Runnable
        Log.i(TAG, "ASR partial 文字稳定 ${STABLE_TEXT_TIMEOUT_MS}ms，自动触发onFinalResult: $text")
        // 标记用户停止 + 停 ASR
        userStoppedAsr = true
        lastPartialText = ""
        try {
            asrService.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "autoFinalize: 停止ASR异常", e)
        }
        // [Bug fix] 不在这里设置 THINKING 状态和显示气泡！
        // sendToLlm → invokeLlm 会处理状态转换和 UI 更新
        // 之前先设 THINKING 再调 sendToLlm，导致 sendToLlm 检查 THINKING 时直接 return
        sendToLlm(text)
    }

    // 自动回到监听
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoListenRunnable = Runnable {
        if (currentState == State.IDLE && isDuiXReady) {
            startListening()
        }
    }

    // [Bug fix] SPEAKING 超时保护：DUIX SDK 不回调 AUDIO_PLAY_END 时自动恢复
    private val speakingTimeoutRunnable = Runnable {
        if (currentState == State.SPEAKING) {
            Log.w(TAG, "[BUG-FIX] SPEAKING 超时 ${SPEAKING_TIMEOUT_MS}ms，强制恢复 IDLE")
            currentState = State.IDLE
            updateStatus("就绪")
            updateUI()
            scheduleAutoListen()
        }
    }

    private fun scheduleSpeakingTimeout() {
        cancelSpeakingTimeout()
        mainHandler.postDelayed(speakingTimeoutRunnable, SPEAKING_TIMEOUT_MS)
    }

    private fun cancelSpeakingTimeout() {
        mainHandler.removeCallbacks(speakingTimeoutRunnable)
    }

    // [Bug fix] THINKING 超时保护：LLM 请求挂起时自动恢复
    private val thinkingTimeoutRunnable = Runnable {
        if (currentState == State.THINKING) {
            Log.w(TAG, "[BUG-FIX] THINKING 超时 ${THINKING_TIMEOUT_MS}ms，强制恢复 IDLE")
            currentState = State.IDLE
            updateStatus("请求超时")
            updateUI()
            showAiBubble(thinking = false, text = "请求超时，请重试")
            scheduleAutoListen()
        }
    }

    private fun scheduleThinkingTimeout() {
        cancelThinkingTimeout()
        mainHandler.postDelayed(thinkingTimeoutRunnable, THINKING_TIMEOUT_MS)
    }

    private fun cancelThinkingTimeout() {
        mainHandler.removeCallbacks(thinkingTimeoutRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // [Phase 6.3] 键盘 insets 适配：让 bottomPanel 在键盘弹起时上移
        // decorFitsSystemWindows=false 让 root view 接管 insets
        // 然后通过 OnApplyWindowInsetsListener 把 IME insets 应用到 bottomPanel
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImeInsetsToBottomPanel()

        modelName = intent.getStringExtra("modelName") ?: ""
        debug = intent.getBooleanExtra("debug", false)
        // 根据 modelName 构造完整的模型 URL（DUIX SDK 会从中提取 dirName）
        modelUrl = when (modelName) {
            ai.guiji.duix.test.service.AiConfig.MODEL_NAME_XIAOBEN ->
                ai.guiji.duix.test.service.AiConfig.MODEL_XIAOBEN_URL
            ai.guiji.duix.test.service.AiConfig.MODEL_NAME_AIRUIKE ->
                ai.guiji.duix.test.service.AiConfig.MODEL_AIRUIKE_URL
            else -> {
                // 兼容旧的 modelUrl 参数
                val legacy = intent.getStringExtra("modelUrl") ?: ""
                if (legacy.isNotEmpty()) legacy else modelName
            }
        }

        // 安全检查：模型未下载时直接返回
        if (modelUrl.isEmpty() || modelName.isEmpty()) {
            Log.e(TAG, "未指定模型: modelName=$modelName, modelUrl=$modelUrl")
            showLoadingError("未指定模型", "请返回主页选择数字人")
            return
        }

        // 再次确认模型已下载
        val modelManager = ai.guiji.duix.test.service.ModelManager()
        if (!modelManager.isBaseConfigReady(mContext)) {
            Log.e(TAG, "基础资源未下载")
            showLoadingError("基础资源未下载", "请返回主页下载基础资源")
            return
        }
        if (!modelManager.isModelReady(mContext, modelName)) {
            Log.e(TAG, "模型未下载: $modelName")
            showLoadingError("模型未下载", "请返回主页下载模型: $modelName")
            return
        }

        // 在 super.onCreate() 之后 mContext 已赋值，安全初始化依赖 Context 的服务
        try {
            asrService = HybridAsrService(mContext)
            asrService.create()
        } catch (e: Exception) {
            Log.e(TAG, "初始化ASR服务失败", e)
        }
        try {
            mp3ToPcmConverter = Mp3ToPcmConverter(mContext)
        } catch (e: Exception) {
            Log.e(TAG, "初始化MP3转换器失败", e)
        }
        try {
            androidTtsService = AndroidTtsService(mContext)
            androidTtsService.init()
        } catch (e: Exception) {
            Log.e(TAG, "初始化Android TTS服务失败", e)
        }

        // 初始化时显示全屏 loading
        showLoading("正在加载...")

        try {
            Glide.with(mContext).load("file:///android_asset/bg/bg1.png").into(binding.ivBg)
        } catch (e: Exception) {
            Log.e(TAG, "加载背景图失败", e)
        }

        binding.glTextureView.setEGLContextClientVersion(GL_CONTEXT_VERSION)
        binding.glTextureView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.glTextureView.isOpaque = false

        // [Phase 2.2] 初始化消息历史 RecyclerView
        messageAdapter = MessageAdapter()
        binding.messagesList.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@CallActivity).apply {
                stackFromEnd = true  // 最新的消息在底部
            }
            adapter = messageAdapter
        }
        // [Phase 5.3/5.4] 消息长按菜单 + 重新生成按钮回调
        messageAdapter.setOnActionListener { msg, action ->
            when (action) {
                MessageAdapter.Action.REGENERATE -> regenerateLastAi()
                MessageAdapter.Action.LIKE -> { /* 反馈已 Toast */ }
                MessageAdapter.Action.DISLIKE -> { /* 反馈已 Toast */ }
                MessageAdapter.Action.COPY, MessageAdapter.Action.SHARE -> { /* Adapter 内部已处理 */ }
            }
        }

        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // [Phase 7.2 P0-4] 新建对话按钮 - 清空消息历史，开始新对话
        binding.btnNewChat.setOnClickListener {
            performHapticFeedback()
            startNewChat()
        }

        // [P2-C] 悬浮窗开关
        binding.btnFloating.setOnClickListener {
            performHapticFeedback()
            toggleFloatingWindow()
        }

        // 静音切换
        binding.btnMute.setOnClickListener {
            isMuted = true
            duix?.setVolume(0.0F)
            binding.btnMute.visibility = View.GONE
            binding.btnUnmute.visibility = View.VISIBLE
            performHapticFeedback()
            showToast("已静音")
        }

        binding.btnUnmute.setOnClickListener {
            isMuted = false
            duix?.setVolume(1.0F)
            binding.btnMute.visibility = View.VISIBLE
            binding.btnUnmute.visibility = View.GONE
            performHapticFeedback()
            showToast("已取消静音")
        }

        // 设置按钮 - 点击弹出 ASR/TTS 引擎设置弹窗
        binding.btnSettings.setOnClickListener {
            showEngineSettingsDialog()
        }

        // 麦克风按钮 - 长按说话
        binding.btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> onMicButtonDown()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onMicButtonUp()
            }
            true
        }

        // 点击数字人区域中断说话（带点击位置涟漪动画）
        binding.tapOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (currentState == State.SPEAKING) {
                    stopSpeaking()
                    performHapticFeedback()
                    // [P2-A] 在手指抬起位置显示涟漪
                    showTapRipple(event.rawX.toInt(), event.rawY.toInt())
                }
            }
            false
        }

        // 文本输入发送
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etInput.text.clear()
                sendToLlm(text)
                performHapticFeedback()
            }
        }

        // [Phase 4.3 P1-1] 快捷指令 chip：5 个常用指令一键发送
        setupQuickActionChip(binding.chipGreet, "你好，介绍一下你自己")
        setupQuickActionChip(binding.chipJoke, "讲一个简短的笑话")
        setupQuickActionChip(binding.chipPoem, "背一首你最喜欢的古诗")
        setupQuickActionChip(binding.chipAdvice, "给我一些工作学习的建议")
        setupQuickActionChip(binding.chipTranslate, "翻译成英文：人工智能正在改变世界")

        // 键盘发送
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = binding.etInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    binding.etInput.text.clear()
                    sendToLlm(text)
                }
                true
            } else {
                false
            }
        }

        // 重试按钮
        binding.btnRetry.setOnClickListener {
            retryInit()
        }

        // 初始化渲染器
        try {
            mDUIXRender = DUIXRenderer(mContext, binding.glTextureView)
            binding.glTextureView.setRenderer(mDUIXRender)
            binding.glTextureView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        } catch (e: Exception) {
            Log.e(TAG, "初始化渲染器失败", e)
            showLoadingError("渲染器初始化失败", e.message ?: "未知错误")
            return
        }

        // 初始化DUIX
        try {
            initDuiX()
        } catch (e: Exception) {
            Log.e(TAG, "初始化DUIX失败", e)
            showLoadingError("初始化失败", e.message ?: "未知错误")
        }
    }

    private fun initDuiX() {
        showLoading("正在加载数字人...")
        duix = DUIX(mContext, modelUrl, mDUIXRender) { event, msg, info ->
            when (event) {
                Constant.CALLBACK_EVENT_INIT_READY -> {
                    Log.i(TAG, "DUIX 初始化成功!")
                    mModelInfo = info as ModelInfo
                    isDuiXReady = true
                    initOk()
                }
                Constant.CALLBACK_EVENT_INIT_ERROR -> {
                    runOnUiThread {
                        Log.e(TAG, "CALLBACK_EVENT_INIT_ERROR: $msg")
                        try {
                            val duixDir = mContext.getExternalFilesDir("duix")
                            val modelDir = java.io.File(duixDir, "model")
                            val dirName = if (modelUrl.startsWith("http")) {
                                modelUrl.substringAfterLast("/").removeSuffix(".zip")
                            } else modelUrl
                            val baseDir = java.io.File(modelDir, "gj_dh_res")
                            val specificModelDir = java.io.File(modelDir, dirName)
                            val tmpDir = java.io.File(modelDir, "tmp")
                            val baseTag = java.io.File(tmpDir, "gj_dh_res")
                            val modelTag = java.io.File(tmpDir, dirName)

                            val diagnosticInfo = StringBuilder().apply {
                                append("初始化失败: $msg\n")
                                append("模型URL: $modelUrl\n")
                                append("dirName: $dirName\n")
                                append("duix目录存在: ${duixDir?.exists()}\n")
                                append("model目录存在: ${modelDir.exists()}\n")
                                append("gj_dh_res目录存在: ${baseDir.exists()}\n")
                                append("gj_dh_res标记存在: ${baseTag.exists()}\n")
                                append("${dirName}目录存在: ${specificModelDir.exists()}\n")
                                append("${dirName}标记存在: ${modelTag.exists()}\n")
                                if (specificModelDir.exists()) {
                                    val files = specificModelDir.list()
                                    append("${dirName}文件数: ${files?.size ?: 0}\n")
                                    files?.take(10)?.forEach { name -> append("  $name\n") }
                                }
                            }.toString()
                            Log.e(TAG, diagnosticInfo)
                        } catch (e: Exception) {
                            Log.e(TAG, "诊断异常: ${e.message}")
                        }
                        showLoadingError("初始化失败", msg ?: "未知错误")
                    }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_START -> {
                    runOnUiThread {
                        Log.i(TAG, "AUDIO_PLAY_START: 数字人开始播放音频")
                        currentState = State.SPEAKING
                        updateUI()
                    }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_END -> {
                    runOnUiThread {
                        Log.i(TAG, "AUDIO_PLAY_END: 数字人播放完成")
                        cancelSpeakingTimeout()
                        currentState = State.IDLE
                        updateUI()
                        scheduleAutoListen()
                    }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                    runOnUiThread {
                        Log.e(TAG, "AUDIO_PLAY_ERROR: 数字人播放出错: $msg")
                        cancelSpeakingTimeout()
                        updateStatus("播放出错")
                        currentState = State.IDLE
                        updateUI()
                        scheduleAutoListen()
                    }
                }
                Constant.CALLBACK_EVENT_MOTION_START -> {}
                Constant.CALLBACK_EVENT_MOTION_END -> {}
            }
        }
        duix?.init()
    }

    private fun retryInit() {
        duix?.release()
        duix = null
        isDuiXReady = false
        initDuiX()
    }

    private fun initOk() {
        runOnUiThread {
            hideLoading()
            enableControls(true)
            currentState = State.IDLE
            updateStatus("就绪")
            updateUI()
            showToast("数字人已就绪")
            // 恢复上次保存的 TTS 引擎选择
            loadTtsEnginePreference()
            // 恢复上次保存的 ASR 引擎选择 (Phase 1.2 骨架)
            loadAsrEnginePreference()
            // 恢复上次保存的麦克风交互模式 (Phase 1.4 骨架)
            loadMicInteractionMode()
            // 清空 fallback 状态显示 (Phase 1.3 骨架)
            clearFallbackStatus()
            // [Phase 4.2 P1-2] 数字人主动开场白（800ms 延迟让 UI 先稳定）
            mainHandler.postDelayed({ playGreeting() }, 800L)
            // 初始化完成后自动开始监听，参考 Call Annie 即时响应设计
            scheduleAutoListen()
        }
    }

    /**
     * [Phase 4.2 P1-2] 数字人主动开场白
     * 不消耗 LLM token（避免冷启动时双重 LLM 调用）
     * 直接 TTS 播放预设欢迎语 + 显示 AI 气泡
     * 让用户感受到"数字人在主动打招呼"
     */
    private fun playGreeting() {
        if (currentState != State.IDLE) return
        val greeting = "你好呀，我是你的智能伙伴。有什么想聊的，或者按住下方麦克风直接说话就行～"
        // 1) 添加 AI 消息到历史（不显示 user 消息，模拟数字人主动开口）
        messageAdapter.append(MessageData(MessageData.Role.AI, greeting))
        scrollMessagesToBottom()
        // 2) 状态切换到 SPEAKING
        currentState = State.SPEAKING
        updateStatus("打招呼中")
        updateUI()
        // 3) TTS 合成 + 播放
        synthesizeAndPlay(greeting)
    }

    // --- Loading 覆盖层 ---

    private fun showLoading(status: String) {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingSpinner.visibility = View.VISIBLE
        binding.tvLoadingStatus.text = status
        binding.tvLoadingDetail.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
    }

    private fun showLoadingError(title: String, detail: String) {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingSpinner.visibility = View.GONE
        binding.tvLoadingStatus.text = title
        binding.tvLoadingDetail.text = detail
        binding.tvLoadingDetail.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        if (binding.loadingOverlay.visibility == View.VISIBLE) {
            binding.loadingOverlay.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.loadingOverlay.alpha = 1f
                }
                .start()
        }
    }

    private fun onMicButtonDown() {
        performHapticFeedback()
        // 按压视觉反馈：缩小到 0.92
        animateMicScale(0.92f, durationMs = 80L)
        // [DIAG] 麦克风按下：当前状态 + 交互模式
        Log.i(TAG, "[DIAG] onMicButtonDown: currentState=$currentState, micMode=$micInteractionMode, isDuiXReady=$isDuiXReady, userStoppedAsr=$userStoppedAsr, lastPartialText='$lastPartialText'")
        when (currentState) {
            State.SPEAKING -> {
                // 正在说话 -> 打断
                stopSpeaking()
            }
            State.IDLE -> {
                // 空闲 -> 开始录音
                startListening()
            }
            State.LISTENING -> {
                // LONG_PRESS 模式下：按下不响应（抬起时才停）
                // PRESS_ONCE 模式下：再按一次取消录音
                if (micInteractionMode == MicInteractionMode.PRESS_ONCE) {
                    Log.i(TAG, "[DIAG] PRESS_ONCE 模式：用户再按麦克风，立即取消")
                    stopListening()
                } else {
                    Log.i(TAG, "[DIAG] LONG_PRESS 模式：录音中按下不响应（等抬起）")
                }
            }
            State.THINKING -> {
                // 思考中再次点击：不响应（避免打断 LLM），但提示一下
                showToast("正在思考，请稍候...")
            }
        }
    }

    private fun onMicButtonUp() {
        // 抬起时恢复原大小
        animateMicScale(1.0f, durationMs = 120L)
        // [DIAG] 麦克风抬起：当前状态 + 交互模式
        Log.i(TAG, "[DIAG] onMicButtonUp: currentState=$currentState, micMode=$micInteractionMode")
        // 抬起时如果在监听状态：
        //   LONG_PRESS 模式：立即停止（豆包风格）
        //   PRESS_ONCE 模式：不响应（需再按一次才停）
        if (currentState == State.LISTENING) {
            if (micInteractionMode == MicInteractionMode.LONG_PRESS) {
                Log.i(TAG, "[DIAG] LONG_PRESS 模式：抬起停止录音")
                stopListening()
            } else {
                Log.i(TAG, "[DIAG] PRESS_ONCE 模式：抬起不响应（等再按一次）")
            }
        }
    }

    private fun startListening() {
        if (!isDuiXReady || currentState == State.THINKING || currentState == State.SPEAKING) return
        requestPermission(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun permissionsGet(get: Boolean, code: Int) {
        super.permissionsGet(get, code)
        if (get && code == 1) {
            doStartListening()
        } else {
            // 现有行为：弹 Toast 提示需要麦克风权限
            showErrorBanner("需要麦克风权限才能对话", 4000)
            // [DIAG] Phase 1.5 骨架：记录权限诊断信息
            PermissionManager.logDiagnose(this, Manifest.permission.RECORD_AUDIO, code)
        }
    }

    /**
     * 显示权限 rationale 解释对话框（Phase 1.5 骨架）
     * 用于"第一次拒绝后"——告诉用户为什么需要这个权限
     * 当前**未接通**：需要 Phase 1.5 接通阶段在 permissionsGet 中按需调用
     */
    @Suppress("unused")
    private fun showPermissionRationale(permission: String) {
        try {
            val message = when (permission) {
                Manifest.permission.RECORD_AUDIO -> "语音对话需要使用麦克风权限。\n\n请在接下来的对话框中允许。"
                else -> "需要权限: $permission"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("需要权限")
                .setMessage(message)
                .setPositiveButton("去开启") { _, _ ->
                    // 重新请求权限
                    requestPermission(arrayOf(permission), 1)
                }
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showPermissionRationale 异常", e)
        }
    }

    /**
     * 显示"去设置"引导对话框（Phase 1.5 骨架）
     * 用于"用户选了不再询问"——只能引导到系统设置手动开启
     * 当前**未接通**
     */
    @Suppress("unused")
    private fun showPermissionSettingsGuide(permission: String) {
        try {
            val message = "语音对话需要使用 ${permission} 权限。\n\n" +
                    "您之前选择了\"不再询问\"，请到系统设置 → 应用 → DUIX → 权限 中手动开启。"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("权限被禁用")
                .setMessage(message)
                .setPositiveButton("去设置") { _, _ ->
                    try {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        )
                        intent.data = android.net.Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "跳设置异常", e)
                        showToast("无法打开设置，请手动到系统设置中开启权限")
                    }
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showPermissionSettingsGuide 异常", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun doStartListening() {
        Log.i(TAG, "[DIAG] doStartListening: 进入, currentState=$currentState, asrServiceInitialized=${::asrService.isInitialized}")
        if (currentState == State.LISTENING) return
        if (!::asrService.isInitialized) {
            currentState = State.IDLE
            updateStatus("语音识别未就绪")
            updateUI()
            return
        }
        // [Phase 3.2] 开始录音前申请音频焦点
        requestAudioFocus()
        // 重置用户主动停止标志和累积文本，进入正常录音
        userStoppedAsr = false
        lastPartialText = ""
        handlerAutoFinalize.removeCallbacks(autoFinalizeRunnable)
        currentState = State.LISTENING
        updateStatus("聆听中...")
        updateUI()

        try {
            asrService.startListening(object : HybridAsrService.Callback {
                override fun onReady() {
                    Log.i(TAG, "[DIAG] ASR.onReady: ASR 服务就绪")
                    runOnUiThread { updateStatus("请说话") }
                }

                override fun onPartialResult(text: String) {
                    // 用户主动停止后，迟到的 ASR 回调不再更新UI
                    if (userStoppedAsr) {
                        Log.d(TAG, "[DIAG] ASR.onPartialResult 丢弃: userStoppedAsr=true, text='${text.take(30)}'")
                        return
                    }
                    // 保存最新的 partial 文本（VAD 未触发时用作"已识别"的备份）
                    if (text != lastPartialText) {
                        lastPartialText = text
                        lastPartialTimeMs = System.currentTimeMillis()
                    }
                    runOnUiThread {
                        updateStatus("听到: $text")
                        // 重置文字稳定检测定时器：1.5秒内 partial 不变就自动触发
                        handlerAutoFinalize.removeCallbacks(autoFinalizeRunnable)
                        if (text.isNotEmpty()) {
                            handlerAutoFinalize.postDelayed(autoFinalizeRunnable, STABLE_TEXT_TIMEOUT_MS)
                        }
                    }
                }

                override fun onFinalResult(text: String) {
                    Log.i(TAG, "[DIAG] ASR.onFinalResult: text='$text', userStoppedAsr=$userStoppedAsr")
                    runOnUiThread {
                        // VAD 触发了真正的 final，清理文字稳定定时器和累积 partial
                        handlerAutoFinalize.removeCallbacks(autoFinalizeRunnable)
                        lastPartialText = ""
                        // 用户主动停止后，不再进入 LLM 链路
                        if (userStoppedAsr) {
                            Log.i(TAG, "用户已主动停止，丢弃迟到的onFinalResult: $text")
                            return@runOnUiThread
                        }
                        if (text.isNotEmpty()) {
                            updateStatus("识别完成")
                            sendToLlm(text)
                        } else {
                            currentState = State.IDLE
                            updateStatus("未检测到语音")
                            updateUI()
                            scheduleAutoListen()
                        }
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "[DIAG] ASR.onError: error='$error', userStoppedAsr=$userStoppedAsr, lastPartialText='$lastPartialText'")
                    runOnUiThread {
                        // 错误时清理文字稳定定时器和累积文本
                        handlerAutoFinalize.removeCallbacks(autoFinalizeRunnable)
                        lastPartialText = ""
                        // 用户主动停止后，错误信息也不再显示
                        if (userStoppedAsr) {
                            Log.i(TAG, "用户已主动停止，丢弃迟到的onError: $error")
                            return@runOnUiThread
                        }
                        currentState = State.IDLE
                        updateStatus("识别出错: $error")
                        updateUI()
                        // [Phase 2.4] 用 banner 替代 Toast
                        showErrorBanner("语音识别出错: $error", 4000)
                        if (error.contains("No speech") || error.contains("No match")) {
                            scheduleAutoListen()
                        }
                    }
                }

                // [Phase 2.1] 接收音频能量回调，更新录音波形
                override fun onAudioLevel(level: Float) {
                    runOnUiThread {
                        updateWaveformLevel(level)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "启动语音识别异常", e)
            currentState = State.IDLE
            updateStatus("启动识别失败")
            updateUI()
            scheduleAutoListen()
        }
    }

    private fun stopListening() {
        if (currentState != State.LISTENING) return
        if (!::asrService.isInitialized) return
        Log.i(TAG, "[DIAG] stopListening: currentState=$currentState, lastPartialText='$lastPartialText'")
        // 先标记为用户主动停止，再调用 stop，避免迟到的 ASR 回调把状态改回 THINKING
        userStoppedAsr = true
        // 取消文字稳定检测定时器
        handlerAutoFinalize.removeCallbacks(autoFinalizeRunnable)
        // [Phase 3.2] 停止录音后释放音频焦点
        abandonAudioFocus()
        try {
            asrService.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "停止语音识别异常", e)
        }
        // 取出累积的 partial 文本，停止时主动发送给 LLM
        // 解决"VAD 未触发时数字人不响应"的核心 bug
        val pendingText = lastPartialText
        lastPartialText = ""
        // 立即更新UI状态，否则按钮会一直显示红色脉冲和"松开结束"标签
        currentState = State.IDLE
        updateStatus("已停止")
        updateUI()
        cancelAutoListen()

        // 如果有累积的 partial 文本，发送给 LLM
        if (pendingText.isNotEmpty()) {
            Log.i(TAG, "用户停止，发送累积的 ASR 文本: $pendingText")
            updateStatus("识别完成")
            sendToLlm(pendingText)
        } else {
            Log.i(TAG, "用户停止，无累积文本可发送")
        }
    }

    private fun sendToLlm(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "[DIAG] sendToLlm 收到空文本，跳过")
            return
        }
        Log.i(TAG, "[DIAG] sendToLlm: 发送文本到LLM, text='${text.take(50)}...'")
        // [Bug fix] SPEAKING 时也不允许新请求，避免添加 USER 消息却无 AI 回复
        if (currentState == State.THINKING || currentState == State.SPEAKING) return

        // 检查网络连接
        if (!isNetworkAvailable()) {
            showErrorBanner("网络不可用，请检查网络连接", 4000)
            updateStatus("网络不可用")
            return
        }

        // [Phase 2.2] 添加用户消息到历史
        messageAdapter.append(MessageData(MessageData.Role.USER, text))
        scrollMessagesToBottom()

        // 走 LLM 调用链（不再自己 append USER，由 invokeLlm 接管）
        invokeLlm(text)
    }

    /**
     * [Phase 5.4 P1-3] 调起 LLM 链路（不 append USER 消息）
     * sendToLlm 和 regenerateLastAi 共用此核心调用
     */
    private fun invokeLlm(text: String) {
        // 防护：SPEAKING/THINKING 时不允许新请求
        if (currentState == State.THINKING || currentState == State.SPEAKING) {
            Log.w(TAG, "[BUG-FIX] invokeLlm: 当前状态=$currentState，跳过")
            return
        }
        currentState = State.THINKING
        updateStatus("思考中")
        updateUI()
        // [Bug fix] 启动 THINKING 超时保护
        scheduleThinkingTimeout()

        showAiBubble(thinking = true, text = "")

        val fullResponse = StringBuilder()

        try {
            llmService.chat(text, object : LlmService.Callback {
                override fun onToken(token: String) {
                    fullResponse.append(token)
                    runOnUiThread {
                        showAiBubble(thinking = false, text = fullResponse.toString())
                        updateStatus("回复中")
                    }
                }

                override fun onComplete(fullText: String) {
                    Log.i(TAG, "[DIAG] LLM.onComplete: fullText长度=${fullText.length}, fullText='${fullText.take(50)}...'")
                    runOnUiThread {
                        cancelThinkingTimeout()
                        showAiBubble(thinking = false, text = fullText)
                        if (fullText.isNotEmpty()) {
                            synthesizeAndPlay(fullText)
                        } else {
                            currentState = State.IDLE
                            updateStatus("就绪")
                            updateUI()
                            scheduleAutoListen()
                        }
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "[DIAG] LLM.onError: error='$error'")
                    runOnUiThread {
                        cancelThinkingTimeout()
                        currentState = State.IDLE
                        updateStatus("请求出错: $error")
                        showAiBubble(thinking = false, text = "出错了: $error")
                        updateUI()
                        // 重要：网络错误时不再自动重新监听，否则会陷入无限循环
                        // 让用户决定是否继续（可手动点击麦克风或文本输入）
                        // 但音频问题（如 No speech）允许自动重新监听
                        val isNetworkError = error.contains("网络", ignoreCase = true) ||
                                            error.contains("HTTP", ignoreCase = true) ||
                                            error.contains("认证", ignoreCase = true) ||
                                            error.contains("API", ignoreCase = true)
                        if (!isNetworkError) {
                            scheduleAutoListen()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "[DIAG] LLM请求异常", e)
            currentState = State.IDLE
            updateStatus("请求出错")
            updateUI()
            scheduleAutoListen()
        }
    }

    /**
     * 合成语音并推送给 DUIX 数字人
     * 优先使用 Edge TTS，失败时自动切换到 Android 原生 TTS
     */
    private fun synthesizeAndPlay(text: String) {
        currentState = State.SPEAKING
        updateUI()
        // [Bug fix] 启动 SPEAKING 超时保护
        scheduleSpeakingTimeout()

        val currentDuix = duix ?: run {
            currentState = State.IDLE
            updateUI()
            return
        }

        when (currentTtsEngine) {
            TtsEngine.QWEN_TTS -> {
                updateStatus("合成语音中")
                synthesizeWithQwenTts(text, currentDuix)
            }
            TtsEngine.EDGE_TTS -> {
                updateStatus("合成语音中")
                synthesizeWithEdgeTts(text, currentDuix)
            }
            TtsEngine.ANDROID_TTS -> {
                updateStatus("合成语音中")
                synthesizeWithAndroidTts(text, currentDuix)
            }
        }
    }

    /**
     * 使用 Qwen TTS (qwen3-tts-flash-realtime) 合成语音
     * 收到的是 PCM 24kHz mono 16bit，必须重采样到 16kHz 后才能推送给 DUIX
     * （DUIX SDK 内部使用 16kHz，MFCC_RATE = 16000）
     */
    private fun synthesizeWithQwenTts(text: String, currentDuix: DUIX) {
        Log.i(TAG, "尝试 Qwen TTS 合成: ${text.take(30)}...")
        val pushedOnce = booleanArrayOf(false)
        try {
            qwenTtsService.synthesize(text, AiConfig.TTS_DEFAULT_VOICE, object : QwenTtsService.Callback {
                override fun onAudioData(pcmData: ByteArray) {
                    // Qwen TTS 输出 PCM 24kHz，DUIX 期望 16kHz，必须重采样
                    val resampledPcm = try {
                        PcmResampler.resample(pcmData, QWEN_TTS_SAMPLE_RATE, DUIX_SAMPLE_RATE)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Qwen TTS PCM 重采样失败", e)
                        pcmData // 重采样失败时仍尝试推送，让 DUIX 内部处理
                    }
                    Log.i(TAG, "Qwen TTS 返回PCM: ${pcmData.size} bytes (24kHz) -> ${resampledPcm.size} bytes (16kHz)")
                    Thread {
                        try {
                            if (!pushedOnce[0]) {
                                currentDuix.startPush()
                                pushedOnce[0] = true
                            }
                            // 写入 PCM 数据（16kHz mono 16bit，DUIX 要求）
                            try {
                                currentDuix.pushPcm(resampledPcm)
                            } catch (e: Throwable) {
                                Log.e(TAG, "pushPcm 异常", e)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Qwen TTS push 音频异常", e)
                            runOnUiThread {
                                currentState = State.IDLE
                                updateStatus("语音播放失败")
                                updateUI()
                                scheduleAutoListen()
                            }
                        }
                    }.start()
                }

                override fun onComplete() {
                    Log.i(TAG, "Qwen TTS 合成完成")
                    Thread {
                        try {
                            currentDuix.stopPush()
                        } catch (e: Throwable) {
                            Log.e(TAG, "stopPush 异常", e)
                        }
                        // [Bug fix] TTS 完成后延迟恢复 IDLE，不依赖 AUDIO_PLAY_END
                        runOnUiThread {
                            mainHandler.postDelayed({
                                if (currentState == State.SPEAKING) {
                                    cancelSpeakingTimeout()
                                    currentState = State.IDLE
                                    updateStatus("就绪")
                                    updateUI()
                                    scheduleAutoListen()
                                }
                            }, 1500L) // 给数字人 1.5s 播放完最后的音频
                        }
                    }.start()
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Qwen TTS 错误: $error, fallback 到 Edge TTS")
                    runOnUiThread {
                        cancelSpeakingTimeout() // 取消当前超时，重新开始
                        currentTtsEngine = TtsEngine.EDGE_TTS
                        showErrorBanner("Qwen TTS 失败，切换到 Edge TTS", 3000)
                        updateUI()
                        // 重新启动 SPEAKING 超时
                        scheduleSpeakingTimeout()
                        synthesizeWithEdgeTts(text, currentDuix)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Qwen TTS 启动异常", e)
            // fallback
            runOnUiThread {
                currentTtsEngine = TtsEngine.EDGE_TTS
                updateUI()
                synthesizeWithEdgeTts(text, currentDuix)
            }
        }
    }

    /**
     * 使用 Edge TTS 合成语音 -> MP3 转 PCM -> 推送给 DUIX 数字人
     */
    private fun synthesizeWithEdgeTts(text: String, currentDuix: DUIX) {
        Log.i(TAG, "尝试 Edge TTS 合成: ${text.take(30)}...")
        try {
            edgeTtsService.synthesize(text, EdgeTtsService.VOICE_XIAOXIAO, object : EdgeTtsService.Callback {
                override fun onAudioData(mp3Data: ByteArray) {
                    Log.i(TAG, "Edge TTS 返回音频数据: ${mp3Data.size} bytes")
                    Thread {
                        try {
                            Log.i(TAG, "调用 startPush()")
                            currentDuix.startPush()
                            var totalPcmBytes = 0L
                            var pcmChunkCount = 0
                            if (!::mp3ToPcmConverter.isInitialized) {
                                throw RuntimeException("MP3转换器未初始化")
                            }
                            mp3ToPcmConverter.convert(mp3Data, object : Mp3ToPcmConverter.Callback {
                                override fun onPcmData(pcmData: ByteArray) {
                                    pcmChunkCount++
                                    totalPcmBytes += pcmData.size
                                    Log.i(TAG, "pushPcm #$pcmChunkCount: ${pcmData.size} bytes (total: $totalPcmBytes)")
                                    try {
                                        currentDuix.pushPcm(pcmData)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "pushPcm异常", e)
                                    }
                                }

                                override fun onComplete() {
                                    Log.i(TAG, "PCM转换完成: $pcmChunkCount chunks, $totalPcmBytes bytes, 调用 stopPush()")
                                    try {
                                        currentDuix.stopPush()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "stopPush异常", e)
                                    }
                                    edgeTtsFailCount = 0
                                    // [Bug fix] TTS 完成后延迟恢复 IDLE，不依赖 AUDIO_PLAY_END
                                    runOnUiThread {
                                        mainHandler.postDelayed({
                                            if (currentState == State.SPEAKING) {
                                                cancelSpeakingTimeout()
                                                currentState = State.IDLE
                                                updateStatus("就绪")
                                                updateUI()
                                                scheduleAutoListen()
                                            }
                                        }, 1500L)
                                    }
                                }

                                override fun onError(error: String) {
                                    Log.e(TAG, "MP3 to PCM conversion error: $error")
                                    try {
                                        currentDuix.stopPush()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "stopPush异常", e)
                                    }
                                    runOnUiThread {
                                        Log.i(TAG, "MP3转换失败，切换到Android TTS")
                                        updateStatus("切换语音引擎")
                                        currentTtsEngine = TtsEngine.ANDROID_TTS
                                        updateUI()
                                        synthesizeWithAndroidTts(text, currentDuix)
                                    }
                                }
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "Edge TTS PCM处理异常", e)
                            runOnUiThread {
                                currentTtsEngine = TtsEngine.ANDROID_TTS
                                updateUI()
                                synthesizeWithAndroidTts(text, currentDuix)
                            }
                        }
                    }.start()
                }

                override fun onComplete() {
                    Log.i(TAG, "Edge TTS 合成完成")
                    // PCM 推送和 IDLE 恢复在内部 onComplete 中处理
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Edge TTS 合成失败: $error")
                    edgeTtsFailCount++
                    runOnUiThread {
                        cancelSpeakingTimeout() // 取消当前超时，重新开始
                        if (edgeTtsFailCount >= 2) {
                            Log.i(TAG, "Edge TTS 连续失败 $edgeTtsFailCount 次，切换到Android TTS")
                            currentTtsEngine = TtsEngine.ANDROID_TTS
                            updateUI()
                        }
                        // 重新启动 SPEAKING 超时
                        scheduleSpeakingTimeout()
                        synthesizeWithAndroidTts(text, currentDuix)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Edge TTS调用异常", e)
            currentTtsEngine = TtsEngine.ANDROID_TTS
            updateUI()
            synthesizeWithAndroidTts(text, currentDuix)
        }
    }

    /**
     * 使用 Android 原生 TTS 合成语音 -> WAV 转 PCM -> 推送给 DUIX 数字人
     */
    private fun synthesizeWithAndroidTts(text: String, currentDuix: DUIX) {
        if (!::androidTtsService.isInitialized || !androidTtsService.isReady()) {
            Log.e(TAG, "Android TTS 未初始化，无法合成语音")
            runOnUiThread {
                updateStatus("语音不可用")
                currentState = State.IDLE
                updateUI()
                scheduleAutoListen()
            }
            return
        }

        Log.i(TAG, "使用 Android TTS 合成: ${text.take(30)}...")
        try {
            androidTtsService.synthesize(text, object : AndroidTtsService.Callback {
                override fun onPcmData(pcmData: ByteArray) {
                    Log.i(TAG, "Android TTS 返回PCM数据: ${pcmData.size} bytes")
                    Thread {
                        try {
                            Log.i(TAG, "调用 startPush()")
                            currentDuix.startPush()

                            var offset = 0
                            var chunkCount = 0
                            while (offset < pcmData.size) {
                                val chunkSize = minOf(1280, pcmData.size - offset)
                                val chunk = pcmData.copyOfRange(offset, offset + chunkSize)
                                try {
                                    currentDuix.pushPcm(chunk)
                                } catch (e: Exception) {
                                    Log.e(TAG, "pushPcm异常", e)
                                    break
                                }
                                chunkCount++
                                offset += chunkSize
                            }

                            Log.i(TAG, "PCM推送完成: $chunkCount chunks, ${pcmData.size} bytes, 调用 stopPush()")
                            try {
                                currentDuix.stopPush()
                            } catch (e: Exception) {
                                Log.e(TAG, "stopPush异常", e)
                            }
                            // [Bug fix] TTS 完成后延迟恢复 IDLE，不依赖 AUDIO_PLAY_END
                            runOnUiThread {
                                mainHandler.postDelayed({
                                    if (currentState == State.SPEAKING) {
                                        cancelSpeakingTimeout()
                                        currentState = State.IDLE
                                        updateStatus("就绪")
                                        updateUI()
                                        scheduleAutoListen()
                                    }
                                }, 1500L)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Android TTS PCM推送异常", e)
                            runOnUiThread {
                                updateStatus("播放失败")
                                currentState = State.IDLE
                                updateUI()
                                scheduleAutoListen()
                            }
                        }
                    }.start()
                }

                override fun onComplete() {
                    Log.i(TAG, "Android TTS 合成完成")
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Android TTS 合成失败: $error")
                    runOnUiThread {
                        updateStatus("语音合成失败")
                        currentState = State.IDLE
                        updateUI()
                        scheduleAutoListen()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Android TTS调用异常", e)
            runOnUiThread {
                updateStatus("语音合成失败")
                currentState = State.IDLE
                updateUI()
                scheduleAutoListen()
            }
        }
    }

    private fun stopSpeaking() {
        cancelSpeakingTimeout()
        cancelThinkingTimeout()
        try {
            edgeTtsService.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止Edge TTS异常", e)
        }
        try {
            qwenTtsService.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止Qwen TTS异常", e)
        }
        try {
            if (::androidTtsService.isInitialized) androidTtsService.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止Android TTS异常", e)
        }
        try {
            duix?.stopAudio()
        } catch (e: Exception) {
            Log.e(TAG, "停止DUIX音频异常", e)
        }
        currentState = State.IDLE
        updateStatus("就绪")
        updateUI()
        cancelAutoListen()
    }

    /**
     * [P2-A] 在 (x, y) 屏幕坐标处显示 60dp 圆形涟漪并 200ms 内淡出放大
     * 把屏幕坐标转为 ivTapRipple 父容器 (tapOverlay) 内的局部坐标后
     * 用 ConstraintLayout.LayoutParams 重新定位中心
     */
    private fun showTapRipple(screenX: Int, screenY: Int) {
        try {
            val ripple = binding.ivTapRipple
            val parent = ripple.parent as? android.view.ViewGroup ?: return
            val location = IntArray(2)
            parent.getLocationOnScreen(location)
            val localX = (screenX - location[0]) - ripple.width / 2
            val localY = (screenY - location[1]) - ripple.height / 2
            val lp = ripple.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            lp.leftToLeft = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.rightToRight = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.leftMargin = localX
            lp.topMargin = localY
            lp.rightMargin = 0
            lp.bottomMargin = 0
            ripple.layoutParams = lp
            // 取消之前的动画（避免快速点击重叠）
            ripple.clearAnimation()
            ripple.alpha = 0.85f
            ripple.visibility = View.VISIBLE
            val anim = AnimationUtils.loadAnimation(this, R.anim.tap_ripple_expand)
            anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(a: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
                override fun onAnimationEnd(a: android.view.animation.Animation?) {
                    ripple.visibility = View.GONE
                    ripple.alpha = 0f
                }
            })
            ripple.startAnimation(anim)
        } catch (e: Exception) {
            Log.e(TAG, "showTapRipple 异常", e)
        }
    }

    // --- UI 更新 ---

    @SuppressLint("SetTextI18n")
    private fun updateStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun showToast(msg: String) {
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show()
    }

    // [Phase 2.4] 顶部错误条状提示（替代 Toast 显示关键错误）
    // [Phase UI-6] 升级为滑入/滑出动画
    private val errorBannerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val errorBannerHideRunnable = Runnable {
        try {
            // 滑出动画后 GONE
            binding.errorBanner.startAnimation(
                AnimationUtils.loadAnimation(this@CallActivity, R.anim.slide_out_top)
            )
            binding.errorBanner.postDelayed({
                try {
                    binding.errorBanner.visibility = View.GONE
                } catch (e: Exception) {
                    // binding 可能在 Activity 销毁后仍被调用，吞掉
                }
            }, 240L)  // 与 slide_out_top duration 一致
        } catch (e: Exception) {
            // binding 可能在 Activity 销毁后仍被调用，吞掉
        }
    }

    private fun showErrorBanner(message: String, durationMs: Long = 3000) {
        try {
            binding.errorBanner.text = message
            binding.errorBanner.visibility = View.VISIBLE
            // 滑入动画
            binding.errorBanner.startAnimation(
                AnimationUtils.loadAnimation(this@CallActivity, R.anim.slide_in_top)
            )
            errorBannerHandler.removeCallbacks(errorBannerHideRunnable)
            errorBannerHandler.postDelayed(errorBannerHideRunnable, durationMs)
            Log.i(TAG, "[DIAG] showErrorBanner: '$message'")
        } catch (e: Exception) {
            Log.e(TAG, "showErrorBanner 异常", e)
        }
    }

    // [Phase 3.2] Audio Focus 监听：录音中接电话/导航/其他应用抢焦点 → 自动 stopListening
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private val audioFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.w(TAG, "[DIAG] Audio Focus 丢失: focusChange=$focusChange, currentState=$currentState")
                if (currentState == State.LISTENING) {
                    Log.w(TAG, "[DIAG] Audio Focus 丢失导致录音冲突，自动停止录音")
                    runOnUiThread {
                        stopListening()
                        showErrorBanner("音频焦点丢失，录音已停止", 3000)
                    }
                }
            }
            else -> {
                Log.d(TAG, "[DIAG] Audio Focus 变化（无需处理）: focusChange=$focusChange")
            }
        }
    }

    private fun requestAudioFocus() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest = android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .setWillPauseWhenDucked(false)
                    .build()
                val result = audioManager.requestAudioFocus(audioFocusRequest!!)
                Log.i(TAG, "[DIAG] requestAudioFocus: result=$result")
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    audioFocusListener,
                    android.media.AudioManager.STREAM_VOICE_CALL,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                Log.i(TAG, "[DIAG] requestAudioFocus (legacy): result=$result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestAudioFocus 异常", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    val result = audioManager.abandonAudioFocusRequest(it)
                    Log.i(TAG, "[DIAG] abandonAudioFocusRequest: result=$result")
                }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.abandonAudioFocus(audioFocusListener)
                Log.i(TAG, "[DIAG] abandonAudioFocus (legacy): result=$result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "abandonAudioFocus 异常", e)
        }
    }

    private fun updateUI() {
        val micEnabled = isDuiXReady && currentState != State.THINKING
        val sendEnabled = isDuiXReady && currentState == State.IDLE

        // 麦克风按钮
        binding.btnMic.isEnabled = micEnabled
        binding.btnMic.alpha = if (micEnabled) 1.0f else 0.5f

        // 发送按钮
        binding.btnSend.isEnabled = sendEnabled
        binding.btnSend.alpha = if (sendEnabled) 1.0f else 0.5f

        // 输入框
        binding.etInput.isEnabled = sendEnabled

        // 引擎信息（设置弹窗中使用，不再在 toolbar 显示）

        // 麦克风按钮标签
        binding.tvMicLabel.text = when (currentState) {
            State.IDLE -> "按住说话"
            State.LISTENING -> "松开结束"
            State.THINKING -> "思考中..."
            State.SPEAKING -> "点击打断"
        }

        // 打断提示 (Phase 2.5) - SPEAKING 时显示在数字人上方
        binding.tvInterruptHint.visibility = if (currentState == State.SPEAKING) View.VISIBLE else View.GONE

        // 底部状态图标 (Phase 2.3 加大可视权重)
        binding.stateIndicatorRow.visibility = if (isDuiXReady) View.VISIBLE else View.GONE
        when (currentState) {
            State.IDLE -> {
                binding.ivStateIcon.setImageResource(R.drawable.ic_mic)
                binding.ivStateIcon.setColorFilter(0xFFCCCCCC.toInt())  // 灰
                binding.tvStateLabel.text = "就绪"
            }
            State.LISTENING -> {
                binding.ivStateIcon.setImageResource(R.drawable.ic_mic)
                binding.ivStateIcon.setColorFilter(0xFF4CAF50.toInt())  // 绿
                binding.tvStateLabel.text = "聆听中"
            }
            State.THINKING -> {
                binding.ivStateIcon.setImageResource(R.drawable.ic_info)
                binding.ivStateIcon.setColorFilter(0xFF2196F3.toInt())  // 蓝
                binding.tvStateLabel.text = "思考中"
            }
            State.SPEAKING -> {
                binding.ivStateIcon.setImageResource(R.drawable.ic_play)
                binding.ivStateIcon.setColorFilter(0xFFE91E63.toInt())  // 粉
                binding.tvStateLabel.text = "播放中"
            }
        }

        // 麦克风按钮背景
        try {
            binding.btnMic.background = when (currentState) {
                State.LISTENING -> {
                    binding.recordingPulseOuter.visibility = View.VISIBLE
                    binding.recordingPulseInner.visibility = View.VISIBLE
                    try {
                        binding.recordingPulseOuter.startAnimation(
                            AnimationUtils.loadAnimation(this, R.anim.pulse_recording)
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "加载录音动画失败", e)
                    }
                    getDrawable(R.drawable.bg_mic_recording)
                }
                State.SPEAKING -> {
                    binding.recordingPulseOuter.visibility = View.GONE
                    binding.recordingPulseInner.visibility = View.GONE
                    binding.recordingPulseOuter.clearAnimation()
                    getDrawable(R.drawable.bg_mic_recording)
                }
                else -> {
                    binding.recordingPulseOuter.visibility = View.GONE
                    binding.recordingPulseInner.visibility = View.GONE
                    binding.recordingPulseOuter.clearAnimation()
                    getDrawable(R.drawable.bg_mic_button)
                }
            }
            // [P2-A] 状态切换时按钮 scale 弹性反馈，让 IDLE ↔ LISTENING/SPEAKING 切换有视觉过渡
            animateStateTransition()
        } catch (e: Exception) {
            Log.e(TAG, "更新麦克风按钮背景失败", e)
        }
    }

    private var lastUiState: State? = null

    /**
     * [P2-A] 状态切换时给 btnMic 做 220ms 弹性缩放（1.0 → 1.15 → 1.0），
     * 让"按下/松口/打断/思考"这些状态变化有可感知的物理反馈。
     * 仅在状态真的发生变化时才触发动画，避免 updateUI 反复调用时闪烁。
     */
    private fun animateStateTransition() {
        if (lastUiState == currentState) return
        val prev = lastUiState
        lastUiState = currentState
        try {
            val btn = binding.btnMic
            // 第一次初始化不弹跳
            if (prev == null) return
            // THINKING / IDLE 切换不放大幅度（避免太跳）
            val scale = if (currentState == State.LISTENING || currentState == State.SPEAKING) 1.18f else 1.10f
            val anim = android.view.animation.AnimationSet(true).apply {
                interpolator = android.view.animation.OvershootInterpolator(1.8f)
            }
            val s1 = android.view.animation.ScaleAnimation(
                1.0f, scale, 1.0f, scale,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
            ).apply { duration = 120 }
            val s2 = android.view.animation.ScaleAnimation(
                scale, 1.0f, scale, 1.0f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 140
                startOffset = 120
            }
            anim.addAnimation(s1)
            anim.addAnimation(s2)
            btn.startAnimation(anim)
        } catch (e: Exception) {
            Log.e(TAG, "animateStateTransition 异常", e)
        }
    }

    private fun showAiBubble(thinking: Boolean, text: String) {
        // [Phase 2.2] 改为操作 messageAdapter
        if (thinking) {
            // 开始思考：插入一条"思考中"占位消息
            messageAdapter.append(MessageData(MessageData.Role.AI, "", isThinking = true))
        } else {
            // 更新最后一条 AI 消息的文本
            val msgs = messageAdapter.snapshot()
            if (msgs.isNotEmpty() && msgs.last().role == MessageData.Role.AI) {
                messageAdapter.updateLast(MessageData(MessageData.Role.AI, text, isThinking = false))
            } else {
                messageAdapter.append(MessageData(MessageData.Role.AI, text))
            }
        }
        scrollMessagesToBottom()
    }

    /**
     * 滚动消息列表到底部（Phase 2.2 + UI-6 微动效）
     * 延迟 80ms 确保 RecyclerView 已经完成 item 插入布局
     * 否则 smoothScrollToPosition 可能滚动到错误位置
     */
    private fun scrollMessagesToBottom() {
        binding.messagesList.postDelayed({
            try {
                val count = messageAdapter.itemCount
                if (count > 0) {
                    binding.messagesList.smoothScrollToPosition(count - 1)
                }
            } catch (e: Exception) {
                // 静默吞掉
            }
        }, 80L)
    }

    /**
     * 更新录音波形显示（Phase 2.1）
     * 根据音频能量 level (0.0~1.0) 调整外圈脉冲圆环的缩放
     * - level=0（无声）：缩放 1.0，基础大小
     * - level=1（满幅）：缩放 1.4，明显放大
     * - level=0.5（普通说话）：缩放约 1.2
     */
    private fun updateWaveformLevel(level: Float) {
        if (currentState != State.LISTENING) return
        val scale = 1.0f + level * 0.4f
        try {
            binding.recordingPulseOuter.scaleX = scale
            binding.recordingPulseOuter.scaleY = scale
            binding.recordingPulseInner.scaleX = 1.0f + level * 0.2f
            binding.recordingPulseInner.scaleY = 1.0f + level * 0.2f
        } catch (e: Exception) {
            // View 未初始化等异常，吞掉
        }
    }

    /**
     * 麦克风按钮缩放动画（按压反馈）
     * @param targetScale 目标 scaleX/Y 值（按下 0.92，抬起 1.0）
     * @param durationMs 动画时长
     */
    private fun animateMicScale(targetScale: Float, durationMs: Long) {
        try {
            binding.btnMic.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(durationMs)
                .start()
        } catch (e: Exception) {
            // 静默吞掉
        }
    }

    /**
     * [Phase 6.3] 键盘 IME insets 适配
     * 监听 WindowInsets 变化，把 IME 高度应用到 bottomPanel 的 paddingBottom
     * 同时把 statusBar 高度应用到 toolbar 的 paddingTop
     * 避免键盘弹起时输入框被遮挡、状态栏文字被 toolbar 遮挡
     */
    private fun applyImeInsetsToBottomPanel() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            val systemBarsInsets = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
            // 底部：取 IME 高度 和 导航栏高度 的较大值
            val bottomInset = maxOf(imeInsets.bottom, systemBarsInsets.bottom)
            binding.bottomPanel.setPadding(
                binding.bottomPanel.paddingLeft,
                binding.bottomPanel.paddingTop,
                binding.bottomPanel.paddingRight,
                bottomInset + dpToPx(16)  // 16dp 是 bottomPanel 原本的 paddingBottom
            )
            // 顶部：状态栏高度（toolbar）
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                systemBarsInsets.top,
                binding.toolbar.paddingRight,
                binding.toolbar.paddingBottom
            )
            insets
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    /**
     * [Phase 7.2 P0-4] 开始新对话
     * - 清空消息历史（UI + LLM）
     * - 重置状态机
     * - 显示系统消息提示
     * - 重新进入自动监听
     */
    private fun startNewChat() {
        try {
            // 1) 清空 UI 消息
            messageAdapter.clear()
            // 2) 清空 LLM 上下文
            try { llmService.clearHistory() } catch (e: Exception) { Log.e(TAG, "清空LLM历史失败", e) }
            // 3) 重置状态机
            currentState = State.IDLE
            // 4) 加一条系统消息提示
            val sysMsg = MessageData(
                role = MessageData.Role.SYSTEM,
                text = "新对话已开始",
                timestampMs = System.currentTimeMillis()
            )
            messageAdapter.append(sysMsg)
            // 5) 更新 UI
            updateStatus("新对话")
            updateUI()
            // 6) 滚动到底部
            scrollMessagesToBottom()
            // 7) 自动重新进入监听（如已就绪）
            cancelAutoListen()
            if (isDuiXReady) {
                scheduleAutoListen()
            }
            Log.i(TAG, "[DIAG] startNewChat 完成")
        } catch (e: Exception) {
            Log.e(TAG, "[DIAG] startNewChat 异常", e)
        }
    }

    /**
     * [Phase 4.3 P1-1] 快捷指令 chip 点击：直接发送预设文本到 LLM
     */
    private fun setupQuickActionChip(chip: View, prompt: String) {
        chip.setOnClickListener {
            performHapticFeedback()
            // 触觉反馈后立刻发送
            sendToLlm(prompt)
            // 自动隐藏键盘（如果有）
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
            } catch (e: Exception) { /* 静默 */ }
        }
    }

    /**
     * [P2-C] 切换悬浮窗状态
     * - 没有 SYSTEM_ALERT_WINDOW 权限时弹引导对话框跳设置
     * - 已经在运行：stopService
     * - 未运行：startService（API 26+ 需用 startForegroundService）
     */
    private fun toggleFloatingWindow() {
        try {
            if (isFloatingWindowRunning()) {
                Log.i(TAG, "[P2-C] 关闭悬浮窗")
                stopService(android.content.Intent(this, ai.guiji.duix.test.service.FloatingWindowService::class.java))
                showToast("已关闭悬浮窗")
                return
            }
            // 权限检查
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "[P2-C] 没有 SYSTEM_ALERT_WINDOW 权限，引导用户去开启")
                    showOverlayPermissionGuide()
                    return
                }
            }
            // 启动
            val intent = android.content.Intent(this, ai.guiji.duix.test.service.FloatingWindowService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i(TAG, "[P2-C] 已启动悬浮窗服务")
            showToast("悬浮窗已开启")
        } catch (e: Exception) {
            Log.e(TAG, "[P2-C] toggleFloatingWindow 异常", e)
            showToast("悬浮窗启动失败")
        }
    }

    private fun isFloatingWindowRunning(): Boolean {
        return ai.guiji.duix.test.service.FloatingWindowService::class.java.let { clazz ->
            try {
                val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                @Suppress("DEPRECATION")
                manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == clazz.name }
            } catch (e: Exception) {
                Log.e(TAG, "isFloatingWindowRunning 检查失败", e)
                false
            }
        }
    }

    /**
     * [P2-C] 引导用户到设置开启"显示在其他应用上层"权限
     */
    private fun showOverlayPermissionGuide() {
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("数字人悬浮窗需要「显示在其他应用上层」权限。\n\n请在接下来的页面中找到 DUIX 并开启。")
                .setPositiveButton("去开启") { _, _ ->
                    try {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "跳设置异常", e)
                        showToast("无法打开设置，请手动到系统设置中开启")
                    }
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "showOverlayPermissionGuide 异常", e)
        }
    }

    /**
     * [Phase 5.4 P1-3] 重新生成最后一条 AI 回复
     * - 找到最近一条 USER 消息
     * - 删掉最后一条 AI 消息（UI）
     * - 重新走 invokeLlm(userText) 触发新一轮 LLM（不重复 append USER）
     * 让"重新生成"行为符合用户对聊天类 App 的预期
     */
    private fun regenerateLastAi() {
        try {
            val snapshot = messageAdapter.snapshot()
            if (snapshot.isEmpty()) {
                showToast("没有可重新生成的内容")
                return
            }
            // 1) 找最近的 USER 文本
            val lastUserIndex = snapshot.indexOfLast { it.role == MessageData.Role.USER && it.text.isNotBlank() }
            if (lastUserIndex < 0) {
                showToast("没有用户消息可重新生成")
                return
            }
            val userText = snapshot[lastUserIndex].text
            // 2) 状态检查
            if (currentState == State.THINKING || currentState == State.SPEAKING) {
                showToast("正在处理中，请稍候")
                return
            }
            // 3) 删掉最后一条 AI 消息（重新生成）
            val lastAiIndex = snapshot.indexOfLast { it.role == MessageData.Role.AI && !it.isThinking }
            if (lastAiIndex >= 0) {
                messageAdapter.removeAt(lastAiIndex)
            }
            // 4) 网络检查
            if (!isNetworkAvailable()) {
                showErrorBanner("网络不可用，请检查网络连接", 4000)
                return
            }
            // 5) 触觉反馈
            performHapticFeedback()
            // 6) 重新走 invokeLlm（USER 消息保留在历史中，AI 消息已被删除）
            invokeLlm(userText)
            Log.i(TAG, "[DIAG] regenerateLastAi 完成: userText='${userText.take(30)}...'")
        } catch (e: Exception) {
            Log.e(TAG, "[DIAG] regenerateLastAi 异常", e)
            showToast("重新生成失败")
        }
    }

    private fun scheduleAutoListen() {
        cancelAutoListen()
        mainHandler.postDelayed(autoListenRunnable, AUTO_LISTEN_DELAY_MS)
    }

    private fun cancelAutoListen() {
        mainHandler.removeCallbacks(autoListenRunnable)
    }

    private fun enableControls(enabled: Boolean) {
        binding.btnMic.isEnabled = enabled
        binding.btnSend.isEnabled = enabled
        binding.etInput.isEnabled = enabled
        binding.btnMic.alpha = if (enabled) 1.0f else 0.5f
        binding.btnSend.alpha = if (enabled) 1.0f else 0.5f
    }

    /**
     * 显示 ASR/TTS 引擎设置底部弹窗
     */
    private fun showEngineSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_engine_settings, null)
        val dialog = android.app.Dialog(this, R.style.dialog_center)
        dialog.setContentView(dialogView)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(android.view.Gravity.BOTTOM)
        dialog.window?.setWindowAnimations(android.R.style.Animation_InputMethod)

        // ASR 引擎按钮
        val btnAsrDash = dialogView.findViewById<TextView>(R.id.btnAsrDashscope)
        val btnAsrAndroid = dialogView.findViewById<TextView>(R.id.btnAsrAndroid)
        val asrButtons = listOf(btnAsrDash, btnAsrAndroid)

        fun updateAsrSelection() {
            btnAsrDash.isActivated = (currentAsrEngine == AsrEngine.DASHSCOPE)
            btnAsrAndroid.isActivated = (currentAsrEngine == AsrEngine.ANDROID)
        }
        updateAsrSelection()

        btnAsrDash.setOnClickListener {
            currentAsrEngine = AsrEngine.DASHSCOPE
            saveAsrEnginePreference(AsrEngine.DASHSCOPE)
            updateAsrSelection()
            performHapticFeedback()
        }
        btnAsrAndroid.setOnClickListener {
            currentAsrEngine = AsrEngine.ANDROID
            saveAsrEnginePreference(AsrEngine.ANDROID)
            updateAsrSelection()
            performHapticFeedback()
        }

        // TTS 引擎按钮
        val btnTtsEdge = dialogView.findViewById<TextView>(R.id.btnTtsEdge)
        val btnTtsQwen = dialogView.findViewById<TextView>(R.id.btnTtsQwen)
        val btnTtsAndroid = dialogView.findViewById<TextView>(R.id.btnTtsAndroid)
        val ttsButtons = listOf(btnTtsEdge, btnTtsQwen, btnTtsAndroid)

        fun updateTtsSelection() {
            btnTtsEdge.isActivated = (currentTtsEngine == TtsEngine.EDGE_TTS)
            btnTtsQwen.isActivated = (currentTtsEngine == TtsEngine.QWEN_TTS)
            btnTtsAndroid.isActivated = (currentTtsEngine == TtsEngine.ANDROID_TTS)
        }
        updateTtsSelection()

        btnTtsEdge.setOnClickListener {
            currentTtsEngine = TtsEngine.EDGE_TTS
            edgeTtsFailCount = 0
            saveTtsEnginePreference(TtsEngine.EDGE_TTS)
            updateTtsSelection()
            performHapticFeedback()
        }
        btnTtsQwen.setOnClickListener {
            currentTtsEngine = TtsEngine.QWEN_TTS
            saveTtsEnginePreference(TtsEngine.QWEN_TTS)
            updateTtsSelection()
            performHapticFeedback()
        }
        btnTtsAndroid.setOnClickListener {
            currentTtsEngine = TtsEngine.ANDROID_TTS
            saveTtsEnginePreference(TtsEngine.ANDROID_TTS)
            updateTtsSelection()
            performHapticFeedback()
        }

        // 引擎信息
        val tvEngineInfo = dialogView.findViewById<TextView>(R.id.tvEngineInfo)
        tvEngineInfo.text = "当前: ASR=${getAsrEngineDisplayName(currentAsrEngine)} | TTS=${getTtsEngineDisplayName(currentTtsEngine)}"

        // 关闭按钮
        dialogView.findViewById<ImageView>(R.id.btnCloseSettings).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * 循环切换 TTS 引擎：Qwen TTS → Edge TTS → Android TTS → Qwen TTS
     * 并把选择持久化到 SharedPreferences
     */
    private fun cycleTtsEngine() {
        performHapticFeedback()
        val currentIndex = ttsEngineCycle.indexOf(currentTtsEngine)
        val nextIndex = (currentIndex + 1) % ttsEngineCycle.size
        val newEngine = ttsEngineCycle[nextIndex]
        currentTtsEngine = newEngine
        // 重置 Edge TTS 失败计数（切换后给新引擎一次机会）
        edgeTtsFailCount = 0
        saveTtsEnginePreference(newEngine)
        showToast("TTS: ${getTtsEngineDisplayName(newEngine)}")
        updateUI()
        updateStatus("TTS: ${getTtsEngineDisplayName(newEngine)}")
    }

    private fun getTtsEngineDisplayName(engine: TtsEngine): String = when (engine) {
        TtsEngine.QWEN_TTS -> "Qwen TTS"
        TtsEngine.EDGE_TTS -> "Edge TTS"
        TtsEngine.ANDROID_TTS -> "Android TTS"
    }

    private fun saveTtsEnginePreference(engine: TtsEngine) {
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_TTS_ENGINE, engine.name)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存 TTS 引擎偏好失败", e)
        }
    }

    private fun loadTtsEnginePreference() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_TTS_ENGINE, TtsEngine.EDGE_TTS.name)
            val loaded = try { TtsEngine.valueOf(name!!) } catch (e: Exception) { TtsEngine.EDGE_TTS }
            if (loaded != currentTtsEngine) {
                Log.i(TAG, "恢复 TTS 引擎偏好: $name")
                currentTtsEngine = loaded
                updateUI()
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载 TTS 引擎偏好失败", e)
        }
    }

    /**
     * 循环切换 ASR 引擎（Phase 1.2 骨架）
     * DashScope → Android → Disabled → DashScope
     * 骨架阶段：仅 UI/持久化，**不接通** HybridAsrService.PREFERRED_ENGINE
     * 等 Phase 1.1 [DIAG] 实测定位根因后再决定接通逻辑（避免破坏当前诊断链路）
     */
    private fun cycleAsrEngine() {
        performHapticFeedback()
        val currentIndex = asrEngineCycle.indexOf(currentAsrEngine)
        val nextIndex = (currentIndex + 1) % asrEngineCycle.size
        val newEngine = asrEngineCycle[nextIndex]
        currentAsrEngine = newEngine
        saveAsrEnginePreference(newEngine)
        showToast("ASR: ${getAsrEngineDisplayName(newEngine)} (骨架阶段未接通 HybridAsrService)")
        updateUI()
        updateStatus("ASR: ${getAsrEngineDisplayName(newEngine)}")
    }

    private fun getAsrEngineDisplayName(engine: AsrEngine): String = when (engine) {
        AsrEngine.DASHSCOPE -> "DashScope"
        AsrEngine.ANDROID -> "Android"
        AsrEngine.DISABLED -> "Disabled"
    }

    private fun saveAsrEnginePreference(engine: AsrEngine) {
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_ASR_ENGINE, engine.name)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存 ASR 引擎偏好失败", e)
        }
    }

    private fun loadAsrEnginePreference() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_ASR_ENGINE, AsrEngine.DASHSCOPE.name)
            val loaded = try { AsrEngine.valueOf(name!!) } catch (e: Exception) { AsrEngine.DASHSCOPE }
            if (loaded != currentAsrEngine) {
                Log.i(TAG, "恢复 ASR 引擎偏好: $name")
                currentAsrEngine = loaded
                updateUI()
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载 ASR 引擎偏好失败", e)
        }
    }

    /**
     * 清空 fallback 状态显示（Phase 1.3 骨架）
     * 在 initOk 阶段调用，数字人就绪时不显示历史 fallback 状态
     */
    private fun clearFallbackStatus() {
        lastFallbackAction = null
        binding.tvFallbackStatus.visibility = View.GONE
        binding.tvFallbackStatus.text = ""
    }

    /**
     * 更新 fallback 状态显示（Phase 1.3 骨架）
     * 当前**不接通**到 HybridAsrService.onError（避免破坏 Phase 1.1 诊断）
     * 等根因明确后再在 onError 回调里调用：
     *   val action = asrFallbackManager.decide(ctx)
     *   updateFallbackStatus(action)
     */
    private fun updateFallbackStatus(action: AsrFallbackManager.Action) {
        lastFallbackAction = action
        val msg = asrFallbackManager.getUserMessage(action)
        binding.tvFallbackStatus.text = "[ASR] $msg"
        binding.tvFallbackStatus.visibility = View.VISIBLE
        Log.i(TAG, "[DIAG] updateFallbackStatus: action=$action, msg='$msg'")
    }

    /**
     * 恢复麦克风交互模式偏好（Phase 1.4 骨架）
     * 默认 LONG_PRESS（豆包风格：按下开始、抬起结束）
     */
    private fun loadMicInteractionMode() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val name = prefs.getString(KEY_MIC_INTERACTION_MODE, MicInteractionMode.LONG_PRESS.name)
            val loaded = try { MicInteractionMode.valueOf(name!!) } catch (e: Exception) { MicInteractionMode.LONG_PRESS }
            if (loaded != micInteractionMode) {
                Log.i(TAG, "恢复麦克风交互模式: $name")
                micInteractionMode = loaded
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载麦克风交互模式偏好失败", e)
        }
    }

    /**
     * 切换麦克风交互模式（Phase 1.4 接通阶段使用）
     * 当前未接通：UI 入口未加（仅持久化逻辑就绪）
     */
    @Suppress("unused")
    private fun cycleMicInteractionMode() {
        performHapticFeedback()
        val newMode = if (micInteractionMode == MicInteractionMode.LONG_PRESS) {
            MicInteractionMode.PRESS_ONCE
        } else {
            MicInteractionMode.LONG_PRESS
        }
        micInteractionMode = newMode
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_MIC_INTERACTION_MODE, newMode.name)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存麦克风交互模式偏好失败", e)
        }
        showToast("麦克风模式: ${if (newMode == MicInteractionMode.LONG_PRESS) "长按说话" else "再按结束"}")
        Log.i(TAG, "切换麦克风交互模式: $newMode")
    }

    @Suppress("DEPRECATION")
    private fun isNetworkAvailable(): Boolean {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            // 如果检测失败，默认认为网络可用，避免阻止正常使用
            return true
        }
    }

    @Suppress("DEPRECATION")
    private fun performHapticFeedback() {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    it.vibrate(30)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "触觉反馈异常", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时，如果当前在录音状态则重新申请音频焦点
        if (currentState == State.LISTENING) {
            requestAudioFocus()
        }
    }

    override fun onPause() {
        super.onPause()
        // 离开前台时主动放弃音频焦点，避免其他应用录音冲突
        abandonAudioFocus()
        // 取消待执行的自动重连/自动监听，避免后台执行无意义的网络/录音
        cancelAutoListen()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoListen()
        cancelSpeakingTimeout()
        cancelThinkingTimeout()
        try {
            if (::asrService.isInitialized) asrService.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "销毁ASR异常", e)
        }
        try {
            edgeTtsService.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止Edge TTS异常", e)
        }
        try {
            if (::androidTtsService.isInitialized) androidTtsService.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "销毁Android TTS异常", e)
        }
        try {
            duix?.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放DUIX异常", e)
        }
    }
}
