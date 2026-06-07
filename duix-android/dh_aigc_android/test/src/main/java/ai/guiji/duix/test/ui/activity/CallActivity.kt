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
import ai.guiji.duix.test.service.EdgeTtsService
import ai.guiji.duix.test.service.HybridAsrService
import ai.guiji.duix.test.service.LlmService
import ai.guiji.duix.test.service.Mp3ToPcmConverter
import ai.guiji.duix.test.service.QwenTtsService
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
    private var currentTtsEngine = TtsEngine.QWEN_TTS
    private var edgeTtsFailCount = 0

    // 状态管理
    private var currentState = State.IDLE
    private var isDuiXReady = false
    private var isMuted = false
    // 用户主动停止 ASR 的标记位，防止停止后迟到的 ASR 回调改变状态
    private var userStoppedAsr = false

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
                // 已经在听了 -> 立即取消（用户主动停止录音）
                Log.i(TAG, "用户在录音中再次点击麦克风，立即取消")
                stopListening()
            }
            State.THINKING -> {
                // 思考中再次点击：不响应（避免打断 LLM），但提示一下
                showToast("正在思考，请稍候...")
            }
        }
    }

    private fun onMicButtonUp() {
        // 松开时如果在监听状态，停止监听
        if (currentState == State.LISTENING) {
            stopListening()
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
            showToast("需要麦克风权限才能对话")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun doStartListening() {
        if (currentState == State.LISTENING) return
        if (!::asrService.isInitialized) {
            currentState = State.IDLE
            updateStatus("语音识别未就绪")
            updateUI()
            return
        }
        // 重置用户主动停止标志，进入正常录音
        userStoppedAsr = false
        currentState = State.LISTENING
        updateStatus("聆听中...")
        updateUI()

        try {
            asrService.startListening(object : HybridAsrService.Callback {
                override fun onReady() {
                    runOnUiThread { updateStatus("请说话") }
                }

                override fun onPartialResult(text: String) {
                    // 用户主动停止后，迟到的 ASR 回调不再更新UI
                    if (userStoppedAsr) return
                    runOnUiThread { updateStatus("听到: $text") }
                }

                override fun onFinalResult(text: String) {
                    runOnUiThread {
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
                    runOnUiThread {
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
        Log.i(TAG, "用户主动停止录音，立即更新UI状态")
        // 先标记为用户主动停止，再调用 stop，避免迟到的 ASR 回调把状态改回 THINKING
        userStoppedAsr = true
        try {
            asrService.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "停止语音识别异常", e)
        }
        // 立即更新UI状态，否则按钮会一直显示红色脉冲和"松开结束"标签
        currentState = State.IDLE
        updateStatus("已停止")
        updateUI()
        cancelAutoListen()
    }

    private fun sendToLlm(text: String) {
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
            Log.e(TAG, "LLM请求异常", e)
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
     * 返回的是 PCM 24kHz mono 16bit 数据
     */
    private fun synthesizeWithQwenTts(text: String, currentDuix: DUIX) {
        Log.i(TAG, "尝试 Qwen TTS 合成: ${text.take(30)}...")
        val pushedOnce = booleanArrayOf(false)
        try {
            qwenTtsService.synthesize(text, AiConfig.TTS_DEFAULT_VOICE, object : QwenTtsService.Callback {
                override fun onAudioData(pcmData: ByteArray) {
                    Log.i(TAG, "Qwen TTS 返回PCM数据: ${pcmData.size} bytes")
                    Thread {
                        try {
                            if (!pushedOnce[0]) {
                                currentDuix.startPush()
                                pushedOnce[0] = true
                            }
                            // 写入 PCM 数据（Qwen TTS 输出 PCM 24kHz mono 16bit）
                            try {
                                currentDuix.pushPcm(pcmData)
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
                        synthesizeWithEdgeTts(text, currentDuix)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Qwen TTS 启动异常", e)
            // fallback
            runOnUiThread {
                currentTtsEngine = TtsEngine.EDGE_TTS
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

        // 麦克风按钮标签
        binding.tvMicLabel.text = when (currentState) {
            State.IDLE -> "按住说话"
            State.LISTENING -> "松开结束"
            State.THINKING -> "思考中..."
            State.SPEAKING -> "点击打断"
        }

        // 底部状态图标
        binding.stateIndicatorRow.visibility = if (isDuiXReady) View.VISIBLE else View.GONE
        when (currentState) {
            State.IDLE -> {
                binding.ivStateIcon.setImageResource(R.drawable.ic_mic)
                binding.tvStateLabel.text = "就绪"
            }
            State.LISTENING -> {
                binding.ivStateIcon.setImageResource(R.drawable.ic_mic)
                binding.tvStateLabel.text = "聆听中"
            }
            State.THINKING -> {
                binding.ivStateIcon.setImageResource(R.drawable.ic_info)
                binding.tvStateLabel.text = "思考中"
            }
            State.SPEAKING -> {
                binding.ivStateIcon.setImageResource(R.drawable.ic_play)
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
        cancelHideBubble()
        binding.aiResponseBubble.visibility = View.VISIBLE
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

        binding.aiResponseScroll.post {
            binding.aiResponseScroll.fullScroll(View.FOCUS_DOWN)
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
