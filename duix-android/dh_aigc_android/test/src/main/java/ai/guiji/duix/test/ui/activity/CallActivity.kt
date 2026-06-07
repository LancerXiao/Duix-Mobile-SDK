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
import android.widget.Toast
import com.bumptech.glide.Glide

class CallActivity : BaseActivity() {

    companion object {
        const val GL_CONTEXT_VERSION = 2
        private const val AUTO_LISTEN_DELAY_MS = 1200L
        // ASR partial 文字稳定超时（毫秒）：超过这个时间 partial 不变就认为说话结束
        private const val STABLE_TEXT_TIMEOUT_MS = 1500L
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
    private var currentTtsEngine = TtsEngine.QWEN_TTS
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
        // 模拟 VAD 触发的 onFinalResult：标记用户已停止 + 停 ASR + 状态切到 THINKING
        userStoppedAsr = true
        lastPartialText = ""
        try {
            asrService.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "autoFinalize: 停止ASR异常", e)
        }
        currentState = State.THINKING
        updateStatus("识别完成")
        updateUI()
        showAiBubble(thinking = true, text = "正在思考...")
        sendToLlm(text)
    }

    // 自动回到监听
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoListenRunnable = Runnable {
        if (currentState == State.IDLE && isDuiXReady) {
            startListening()
        }
    }

    // 隐藏气泡
    private val hideBubbleRunnable = Runnable {
        binding.aiResponseBubble.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
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

        // TTS 引擎选择 - 点击循环切换 Qwen TTS → Edge TTS → Android TTS → Qwen TTS
        binding.tvTtsEngine.setOnClickListener {
            cycleTtsEngine()
        }

        // ASR 引擎选择 (Phase 1.2 骨架) - 点击循环切换 DashScope → Android → Disabled
        binding.tvAsrEngine.setOnClickListener {
            cycleAsrEngine()
        }

        // 麦克风按钮 - 长按说话
        binding.btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> onMicButtonDown()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onMicButtonUp()
            }
            true
        }

        // 点击数字人区域中断说话
        binding.tapOverlay.setOnClickListener {
            if (currentState == State.SPEAKING) {
                stopSpeaking()
                performHapticFeedback()
            }
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
                        currentState = State.IDLE
                        updateUI()
                        scheduleAutoListen()
                    }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                    runOnUiThread {
                        Log.e(TAG, "AUDIO_PLAY_ERROR: 数字人播放出错: $msg")
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
            // 初始化完成后自动开始监听，参考 Call Annie 即时响应设计
            scheduleAutoListen()
        }
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
            showToast("需要麦克风权限才能对话")
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
                        showToast("语音识别出错: $error")
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

    // 用户主动停止 ASR 的标记位，防止停止后迟到的 ASR 回调改变状态
    private var userStoppedAsr = false

    private fun stopListening() {
        if (currentState != State.LISTENING) return
        if (!::asrService.isInitialized) return
        Log.i(TAG, "[DIAG] stopListening: currentState=$currentState, lastPartialText='$lastPartialText'")
        // 先标记为用户主动停止，再调用 stop，避免迟到的 ASR 回调把状态改回 THINKING
        userStoppedAsr = true
        // 取消文字稳定检测定时器
        handlerAutoFinalize.removeCallbacks(autoFinalizeRunnable)
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
        if (currentState == State.THINKING) return

        // 检查网络连接
        if (!isNetworkAvailable()) {
            showToast("网络不可用，请检查网络连接")
            updateStatus("网络不可用")
            return
        }

        currentState = State.THINKING
        updateStatus("思考中")
        updateUI()

        // [Phase 2.2] 添加用户消息到历史
        messageAdapter.append(MessageData(MessageData.Role.USER, text))
        scrollMessagesToBottom()

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
            hideAiBubble()
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
                    }.start()
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Qwen TTS 错误: $error, fallback 到 Edge TTS")
                    runOnUiThread {
                        // fallback 到 Edge TTS
                        currentTtsEngine = TtsEngine.EDGE_TTS
                        showToast("Qwen TTS失败，使用Edge TTS: $error")
                        updateUI()
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
                                    runOnUiThread {
                                        updateStatus("播放中")
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
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Edge TTS 合成失败: $error")
                    edgeTtsFailCount++
                    if (edgeTtsFailCount >= 2) {
                        Log.i(TAG, "Edge TTS 连续失败 $edgeTtsFailCount 次，切换到Android TTS")
                        runOnUiThread {
                            updateStatus("切换语音引擎")
                            currentTtsEngine = TtsEngine.ANDROID_TTS
                            updateUI()
                            synthesizeWithAndroidTts(text, currentDuix)
                        }
                    } else {
                        runOnUiThread {
                            updateStatus("切换语音引擎")
                            synthesizeWithAndroidTts(text, currentDuix)
                        }
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
                            runOnUiThread {
                                updateStatus("播放中")
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

    // --- UI 更新 ---

    @SuppressLint("SetTextI18n")
    private fun updateStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun showToast(msg: String) {
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show()
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

        // TTS引擎指示器
        binding.tvTtsEngine.text = when (currentTtsEngine) {
            TtsEngine.QWEN_TTS -> "Qwen TTS"
            TtsEngine.EDGE_TTS -> "Edge TTS"
            TtsEngine.ANDROID_TTS -> "Android TTS"
        }

        // ASR引擎指示器 (Phase 1.2 骨架)
        binding.tvAsrEngine.text = when (currentAsrEngine) {
            AsrEngine.DASHSCOPE -> "Dash"
            AsrEngine.ANDROID -> "Android"
            AsrEngine.DISABLED -> "Off"
        }

        // 麦克风按钮标签
        binding.tvMicLabel.text = when (currentState) {
            State.IDLE -> "按住说话"
            State.LISTENING -> "松开结束"
            State.THINKING -> "思考中..."
            State.SPEAKING -> "点击打断"
        }

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
        } catch (e: Exception) {
            Log.e(TAG, "更新麦克风按钮背景失败", e)
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

        // 兼容旧路径：保留原 aiResponseBubble 的可见性设置（虽然已 GONE）
        cancelHideBubble()
        try {
            binding.aiResponseBubble.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.fade_in_up)
            )
        } catch (e: Exception) {
            Log.e(TAG, "加载气泡动画失败", e)
        }
        binding.thinkingIndicator.visibility = if (thinking) View.VISIBLE else View.GONE
        binding.tvAiResponse.text = text
        binding.tvAiResponse.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 滚动消息列表到底部（Phase 2.2）
     */
    private fun scrollMessagesToBottom() {
        binding.messagesList.post {
            val count = messageAdapter.itemCount
            if (count > 0) {
                binding.messagesList.smoothScrollToPosition(count - 1)
            }
        }
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

    private fun hideAiBubble() {
        if (binding.aiResponseBubble.visibility == View.VISIBLE) {
            try {
                binding.aiResponseBubble.startAnimation(
                    AnimationUtils.loadAnimation(this, R.anim.fade_out_down)
                )
            } catch (e: Exception) {
                Log.e(TAG, "加载隐藏动画失败", e)
            }
        }
        mainHandler.postDelayed(hideBubbleRunnable, 500)
    }

    private fun cancelHideBubble() {
        mainHandler.removeCallbacks(hideBubbleRunnable)
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
            val name = prefs.getString(KEY_TTS_ENGINE, TtsEngine.QWEN_TTS.name)
            val loaded = try { TtsEngine.valueOf(name!!) } catch (e: Exception) { TtsEngine.QWEN_TTS }
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

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoListen()
        cancelHideBubble()
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
