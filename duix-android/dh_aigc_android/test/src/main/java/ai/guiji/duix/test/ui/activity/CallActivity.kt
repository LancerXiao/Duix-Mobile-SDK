package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.loader.ModelInfo
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityCallBinding
import ai.guiji.duix.test.service.AndroidAsrService
import ai.guiji.duix.test.service.EdgeTtsService
import ai.guiji.duix.test.service.LlmService
import ai.guiji.duix.test.service.Mp3ToPcmConverter
import android.Manifest
import android.annotation.SuppressLint
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
        private const val AUTO_LISTEN_DELAY_MS = 1500L
    }

    enum class State {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    private var modelUrl = ""
    private var debug = false

    private lateinit var binding: ActivityCallBinding
    private var duix: DUIX? = null
    private var mDUIXRender: DUIXRenderer? = null
    private var mModelInfo: ModelInfo? = null

    // AI服务
    private val llmService = LlmService()
    private lateinit var asrService: AndroidAsrService
    private val edgeTtsService = EdgeTtsService()
    private lateinit var mp3ToPcmConverter: Mp3ToPcmConverter

    // 状态管理
    private var currentState = State.IDLE
    private var isDuiXReady = false
    private var isMuted = false

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

        modelUrl = intent.getStringExtra("modelUrl") ?: ""
        debug = intent.getBooleanExtra("debug", false)

        // 初始化ASR
        asrService = AndroidAsrService(mContext)
        asrService.create()

        // 初始化MP3转PCM转换器
        mp3ToPcmConverter = Mp3ToPcmConverter(mContext)

        Glide.with(mContext).load("file:///android_asset/bg/bg1.png").into(binding.ivBg)

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
        }

        binding.btnUnmute.setOnClickListener {
            isMuted = false
            duix?.setVolume(1.0F)
            binding.btnMute.visibility = View.VISIBLE
            binding.btnUnmute.visibility = View.GONE
            performHapticFeedback()
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

        // 初始化渲染器
        mDUIXRender = DUIXRenderer(mContext, binding.glTextureView)
        binding.glTextureView.setRenderer(mDUIXRender)
        binding.glTextureView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        // 初始化DUIX
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
                        // 显示详细的错误信息帮助诊断
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
                        binding.tvStatus.text = "初始化失败: $msg"
                        Toast.makeText(mContext, "初始化失败，请查看状态栏", Toast.LENGTH_LONG).show()
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
                        // 自动回到监听状态
                        scheduleAutoListen()
                    }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                    runOnUiThread {
                        Log.e(TAG, "AUDIO_PLAY_ERROR: 数字人播放出错")
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

    private fun initOk() {
        runOnUiThread {
            enableControls(true)
            currentState = State.IDLE
            updateStatus("就绪 - 按住麦克风说话")
            updateUI()
        }
    }

    private fun onMicButtonDown() {
        performHapticFeedback()
        when (currentState) {
            State.SPEAKING -> {
                // 点击时正在说话 -> 中断
                stopSpeaking()
            }
            State.IDLE -> {
                startListening()
            }
            State.LISTENING -> {
                // 已经在听了，不做处理
            }
            State.THINKING -> {
                // 正在思考，不做处理
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
            Toast.makeText(mContext, R.string.need_permission_continue, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun doStartListening() {
        if (currentState == State.LISTENING) return
        currentState = State.LISTENING
        updateStatus("正在聆听...")
        updateUI()

        // 使用Android原生SpeechRecognizer
        asrService.startListening(object : AndroidAsrService.Callback {
            override fun onReady() {
                runOnUiThread { updateStatus("正在聆听...请说话") }
            }

            override fun onPartialResult(text: String) {
                runOnUiThread { updateStatus("听到: $text") }
            }

            override fun onFinalResult(text: String) {
                runOnUiThread {
                    if (text.isNotEmpty()) {
                        updateStatus("识别: $text")
                        sendToLlm(text)
                    } else {
                        currentState = State.IDLE
                        updateStatus("未检测到语音，请重试")
                        updateUI()
                        scheduleAutoListen()
                    }
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    currentState = State.IDLE
                    updateStatus("语音识别出错: $error")
                    updateUI()
                    // 自动重试
                    if (error.contains("No speech") || error.contains("No match")) {
                        scheduleAutoListen()
                    }
                }
            }
        })
    }

    private fun stopListening() {
        if (currentState != State.LISTENING) return
        asrService.stopListening()
        // 状态会在 onFinalResult 或 onError 中更新
    }

    private fun sendToLlm(text: String) {
        if (currentState == State.THINKING) return
        currentState = State.THINKING
        updateStatus("思考中...")
        updateUI()

        // 显示气泡和思考动画
        showAiBubble(thinking = true, text = "")

        val fullResponse = StringBuilder()

        llmService.chat(text, object : LlmService.Callback {
            override fun onToken(token: String) {
                fullResponse.append(token)
                runOnUiThread {
                    // 逐步显示文本
                    showAiBubble(thinking = false, text = fullResponse.toString())
                    updateStatus("AI回复中...")
                }
            }

            override fun onComplete(fullText: String) {
                runOnUiThread {
                    showAiBubble(thinking = false, text = fullText)
                    if (fullText.isNotEmpty()) {
                        synthesizeAndPlay(fullText)
                    } else {
                        currentState = State.IDLE
                        updateStatus("就绪 - 按住麦克风说话")
                        updateUI()
                        scheduleAutoListen()
                    }
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    currentState = State.IDLE
                    updateStatus("LLM错误: $error")
                    hideAiBubble()
                    updateUI()
                    scheduleAutoListen()
                }
            }
        })
    }

    /**
     * 使用 Edge TTS 合成语音 -> MP3 转 PCM -> 推送给 DUIX 数字人
     */
    private fun synthesizeAndPlay(text: String) {
        currentState = State.SPEAKING
        updateStatus("语音合成中...")
        updateUI()

        val currentDuix = duix ?: run {
            currentState = State.IDLE
            updateUI()
            return
        }

        // 使用 Edge TTS 合成 MP3 音频
        edgeTtsService.synthesize(text, EdgeTtsService.VOICE_XIAOXIAO, object : EdgeTtsService.Callback {
            override fun onAudioData(mp3Data: ByteArray) {
                Log.i(TAG, "Edge TTS 返回音频数据: ${mp3Data.size} bytes")
                // Edge TTS 返回完整 MP3 数据，转换为 PCM 推送给数字人
                // MP3转PCM是耗时操作，放在后台线程执行
                Thread {
                    // 开始推送会话
                    Log.i(TAG, "调用 startPush()")
                    currentDuix.startPush()
                    var totalPcmBytes = 0L
                    var pcmChunkCount = 0
                    mp3ToPcmConverter.convert(mp3Data, object : Mp3ToPcmConverter.Callback {
                        override fun onPcmData(pcmData: ByteArray) {
                            // 推送 PCM 数据给 DUIX 数字人驱动口型
                            pcmChunkCount++
                            totalPcmBytes += pcmData.size
                            currentDuix.pushPcm(pcmData)
                        }

                        override fun onComplete() {
                            // PCM 全部推送完毕，stopPush会触发BNF处理
                            // 音频播放由RenderThread自动管理：
                            //   - BNF数据就绪时自动调用audioPlayer.startPlay() → CALLBACK_EVENT_AUDIO_PLAY_START
                            //   - 播放完成时触发CALLBACK_EVENT_AUDIO_PLAY_END
                            // 所以这里不要设置IDLE状态，让AUDIO_PLAY_END回调来处理
                            Log.i(TAG, "PCM转换完成: $pcmChunkCount chunks, $totalPcmBytes bytes, 调用 stopPush()")
                            currentDuix.stopPush()
                            runOnUiThread {
                                updateStatus("数字人播放中...")
                            }
                        }

                        override fun onError(error: String) {
                            Log.e(TAG, "MP3 to PCM conversion error: $error")
                            currentDuix.stopPush()
                            runOnUiThread {
                                // TTS转换失败，但文本已显示，仍然回到IDLE
                                if (currentState == State.SPEAKING) {
                                    currentState = State.IDLE
                                }
                                updateStatus("语音转换失败，文本已显示")
                                updateUI()
                                scheduleAutoListen()
                            }
                        }
                    })
                }.start()
            }

            override fun onComplete() {
                // Edge TTS 合成完成（音频数据已在 onAudioData 中处理）
                Log.i(TAG, "Edge TTS 合成完成")
            }

            override fun onError(error: String) {
                Log.e(TAG, "Edge TTS 合成失败: $error")
                runOnUiThread {
                    // TTS失败，但文本已经显示在气泡中
                    if (currentState == State.SPEAKING) {
                        currentState = State.IDLE
                    }
                    updateStatus("语音合成失败，文本已显示")
                    updateUI()
                    scheduleAutoListen()
                }
            }
        })
    }

    private fun stopSpeaking() {
        edgeTtsService.stop()
        duix?.stopAudio()
        currentState = State.IDLE
        updateStatus("就绪 - 按住麦克风说话")
        updateUI()
        cancelAutoListen()
    }

    // --- UI 更新 ---

    @SuppressLint("SetTextI18n")
    private fun updateStatus(text: String) {
        binding.tvStatus.text = text
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

        // 麦克风按钮标签
        binding.tvMicLabel.text = when (currentState) {
            State.IDLE -> "按住说话"
            State.LISTENING -> "松开结束"
            State.THINKING -> "思考中..."
            State.SPEAKING -> "点击打断"
        }

        // 麦克风按钮背景
        binding.btnMic.background = when (currentState) {
            State.LISTENING -> {
                binding.recordingPulseOuter.visibility = View.VISIBLE
                binding.recordingPulseInner.visibility = View.VISIBLE
                binding.recordingPulseOuter.startAnimation(
                    AnimationUtils.loadAnimation(this, R.anim.pulse_recording)
                )
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
    }

    private fun showAiBubble(thinking: Boolean, text: String) {
        cancelHideBubble()
        binding.aiResponseBubble.visibility = View.VISIBLE
        binding.aiResponseBubble.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.fade_in_up)
        )

        binding.thinkingIndicator.visibility = if (thinking) View.VISIBLE else View.GONE
        binding.tvAiResponse.text = text
        binding.tvAiResponse.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE

        // 自动滚动到底部
        binding.aiResponseScroll.post {
            binding.aiResponseScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun hideAiBubble() {
        if (binding.aiResponseBubble.visibility == View.VISIBLE) {
            binding.aiResponseBubble.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.fade_out_down)
            )
        }
        // 延迟隐藏
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
    private fun performHapticFeedback() {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoListen()
        cancelHideBubble()
        asrService.destroy()
        edgeTtsService.stop()
        duix?.release()
    }
}
