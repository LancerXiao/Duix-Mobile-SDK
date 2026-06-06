package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.loader.ModelInfo
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityCallBinding
import ai.guiji.duix.test.service.AsrService
import ai.guiji.duix.test.service.AudioRecorder
import ai.guiji.duix.test.service.LlmService
import ai.guiji.duix.test.service.TtsService
import android.Manifest
import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.bumptech.glide.Glide

class CallActivity : BaseActivity() {

    companion object {
        const val GL_CONTEXT_VERSION = 2
    }

    private var modelUrl = ""
    private var debug = false

    private lateinit var binding: ActivityCallBinding
    private var duix: DUIX? = null
    private var mDUIXRender: DUIXRenderer? = null
    private var mModelInfo: ModelInfo? = null

    // AI服务
    private val llmService = LlmService()
    private val asrService = AsrService()
    private val ttsService = TtsService()
    private val audioRecorder = AudioRecorder()

    // 状态管理
    private var isDuiXReady = false
    private var isRecording = false
    private var isSpeaking = false
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelUrl = intent.getStringExtra("modelUrl") ?: ""
        debug = intent.getBooleanExtra("debug", false)

        Glide.with(mContext).load("file:///android_asset/bg/bg1.png").into(binding.ivBg)

        binding.glTextureView.setEGLContextClientVersion(GL_CONTEXT_VERSION)
        binding.glTextureView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.glTextureView.isOpaque = false

        // 静音开关
        binding.switchMute.setOnCheckedChangeListener { _, isChecked ->
            duix?.setVolume(if (isChecked) 0.0F else 1.0F)
        }

        // 长按说话按钮
        binding.btnTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startListening()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopListening()
            }
            true
        }

        // 文本输入发送
        binding.btnSend.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etInput.text.clear()
                sendToLlm(text)
            }
        }

        // 停止播放
        binding.btnStopPlay.setOnClickListener {
            stopSpeaking()
        }

        // 随机动作
        binding.btnRandomMotion.setOnClickListener {
            duix?.startRandomMotion(true)
        }

        // 初始化渲染器
        mDUIXRender = DUIXRenderer(mContext, binding.glTextureView)
        binding.glTextureView.setRenderer(mDUIXRender)
        binding.glTextureView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        // 初始化DUIX
        duix = DUIX(mContext, modelUrl, mDUIXRender) { event, msg, info ->
            when (event) {
                Constant.CALLBACK_EVENT_INIT_READY -> {
                    mModelInfo = info as ModelInfo
                    isDuiXReady = true
                    initOk()
                }
                Constant.CALLBACK_EVENT_INIT_ERROR -> {
                    runOnUiThread {
                        Log.e(TAG, "CALLBACK_EVENT_INIT_ERROR: $msg")
                        Toast.makeText(mContext, "Init error: $msg", Toast.LENGTH_SHORT).show()
                    }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_START -> {
                    isSpeaking = true
                    updateUI()
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_END -> {
                    isSpeaking = false
                    updateUI()
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                    isSpeaking = false
                    updateUI()
                }
                Constant.CALLBACK_EVENT_MOTION_START -> {}
                Constant.CALLBACK_EVENT_MOTION_END -> {}
            }
        }
        duix?.init()
    }

    private fun initOk() {
        runOnUiThread {
            binding.btnTalk.isEnabled = true
            binding.btnSend.isEnabled = true
            binding.etInput.isEnabled = true
            binding.switchMute.isEnabled = true
            binding.btnStopPlay.isEnabled = true
            updateStatus("Ready - Press and hold to talk")
            updateUI()

            mModelInfo?.let { modelInfo ->
                if (modelInfo.motionRegions.isNotEmpty()) {
                    binding.btnRandomMotion.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startListening() {
        if (!isDuiXReady || isProcessing || isSpeaking) return

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
        if (isRecording) return
        isRecording = true
        updateStatus("Listening...")
        updateUI()

        // 停止当前播放
        stopSpeaking()

        // 启动ASR
        asrService.start(object : AsrService.Callback {
            override fun onReady() {
                runOnUiThread { updateStatus("Listening... (ASR ready)") }
            }

            override fun onPartialResult(text: String) {
                runOnUiThread { updateStatus("Hearing: $text") }
            }

            override fun onFinalResult(text: String) {
                runOnUiThread {
                    if (text.isNotEmpty()) {
                        updateStatus("Recognized: $text")
                        sendToLlm(text)
                    }
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    updateStatus("ASR Error: $error")
                    isRecording = false
                    updateUI()
                }
            }

            override fun onClosed() {
                isRecording = false
                updateUI()
            }
        })

        // 启动录音，将PCM数据发送给ASR
        audioRecorder.start(object : AudioRecorder.Callback {
            override fun onPcmData(data: ByteArray) {
                asrService.sendAudio(data)
            }

            override fun onError(error: String) {
                runOnUiThread {
                    updateStatus("Record Error: $error")
                    stopListening()
                }
            }
        })
    }

    private fun stopListening() {
        if (!isRecording) return
        isRecording = false
        audioRecorder.stop()
        asrService.stop()
        updateUI()
    }

    private fun sendToLlm(text: String) {
        if (isProcessing) return
        isProcessing = true
        updateStatus("Thinking...")
        updateUI()

        val fullResponse = StringBuilder()

        llmService.chat(text, object : LlmService.Callback {
            override fun onToken(token: String) {
                fullResponse.append(token)
                runOnUiThread {
                    updateStatus("AI: ${fullResponse}")
                }
            }

            override fun onComplete(fullText: String) {
                runOnUiThread {
                    updateStatus("AI: $fullText")
                    isProcessing = false
                    if (fullText.isNotEmpty()) {
                        synthesizeAndPlay(fullText)
                    } else {
                        updateUI()
                    }
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    updateStatus("LLM Error: $error")
                    isProcessing = false
                    updateUI()
                }
            }
        })
    }

    private fun synthesizeAndPlay(text: String) {
        updateStatus("Synthesizing speech...")
        isSpeaking = true
        updateUI()

        ttsService.synthesize(text, object : TtsService.Callback {
            override fun onPcmData(data: ByteArray) {
                // 将TTS生成的PCM数据推送给数字人
                duix?.pushPcm(data)
            }

            override fun onComplete() {
                duix?.stopPush()
                isSpeaking = false
                runOnUiThread {
                    updateStatus("Ready - Press and hold to talk")
                    updateUI()
                }
            }

            override fun onError(error: String) {
                duix?.stopPush()
                isSpeaking = false
                runOnUiThread {
                    updateStatus("TTS Error: $error")
                    updateUI()
                }
            }
        })

        // 开始推送音频
        duix?.startPush()
    }

    private fun stopSpeaking() {
        duix?.stopAudio()
        isSpeaking = false
        updateUI()
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun updateUI() {
        val state = when {
            isRecording -> "recording"
            isProcessing -> "processing"
            isSpeaking -> "speaking"
            else -> "idle"
        }

        binding.btnTalk.isEnabled = isDuiXReady && !isProcessing && !isSpeaking
        binding.btnTalk.text = when {
            isRecording -> "Listening..."
            isProcessing -> "Thinking..."
            isSpeaking -> "Speaking..."
            else -> "Hold to Talk"
        }

        // 更新录音指示器
        binding.ivRecordingIndicator.visibility = if (isRecording) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.stop()
        asrService.close()
        duix?.release()
    }
}
