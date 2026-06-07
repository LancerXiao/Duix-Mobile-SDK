package ai.guiji.duix.test.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 混合 ASR 服务 - 优先使用百炼平台 DashScope WebSocket ASR
 * 如果 DashScope 不可用，自动 fallback 到 Android 原生 SpeechRecognizer
 *
 * 录音参数：PCM 16kHz mono 16bit（与 fun-asr-realtime 匹配）
 */
class HybridAsrService(private val context: Context) {

    companion object {
        private const val TAG = "HybridAsrService"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val PREFERRED_ENGINE = "dashscope"  // dashscope | android
    }

    interface Callback {
        fun onReady()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    private var dashscopeAsr: AsrService? = null
    private var androidAsr: AndroidAsrService? = null
    private var audioRecord: AudioRecord? = null
    private val isListening = AtomicBoolean(false)
    private val isDashscopeAvailable = AtomicBoolean(false)
    private var currentCallback: Callback? = null
    private val recordExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var useDashscope: Boolean = true

    fun create() {
        // 尝试创建 AndroidAsr 作为 fallback
        try {
            if (android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
                androidAsr = AndroidAsrService(context)
                androidAsr?.create()
                Log.i(TAG, "AndroidAsr 初始化成功（作为fallback）")
            } else {
                Log.w(TAG, "AndroidAsr 在此设备上不可用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AndroidAsr 初始化失败", e)
        }

        // DashScope ASR 不需要预创建（每次start时创建WebSocket）
        isDashscopeAvailable.set(true)
        Log.i(TAG, "HybridAsrService 初始化完成，使用引擎: $PREFERRED_ENGINE")
    }

    fun startListening(callback: Callback) {
        if (isListening.get()) {
            Log.w(TAG, "已经在监听中")
            return
        }
        currentCallback = callback
        useDashscope = PREFERRED_ENGINE == "dashscope"

        if (useDashscope) {
            startDashscopeListening(callback)
        } else {
            startAndroidListening(callback)
        }
    }

    /**
     * 启动 DashScope ASR：录音 + WebSocket 实时识别
     */
    private fun startDashscopeListening(callback: Callback) {
        isListening.set(true)
        Log.i(TAG, "使用 DashScope ASR (fun-asr-realtime)")

        dashscopeAsr = AsrService()
        dashscopeAsr?.start(object : AsrService.Callback {
            override fun onReady() {
                mainHandler.post { callback.onReady() }
            }

            override fun onPartialResult(text: String) {
                mainHandler.post { callback.onPartialResult(text) }
            }

            override fun onFinalResult(text: String) {
                isListening.set(false)
                mainHandler.post { callback.onFinalResult(text) }
            }

            override fun onError(error: String) {
                Log.e(TAG, "DashScope ASR 错误: $error，自动fallback到Android ASR")
                isListening.set(false)
                // fallback 到 Android ASR
                startAndroidListening(callback)
            }

            override fun onClosed() {
                Log.i(TAG, "DashScope ASR 已关闭")
            }
        })

        // 开始录音
        startRecording { pcmData ->
            dashscopeAsr?.sendAudio(pcmData)
        }
    }

    /**
     * 启动 Android ASR
     */
    private fun startAndroidListening(callback: Callback) {
        if (androidAsr == null) {
            callback.onError("此设备不支持语音识别，请使用文字输入")
            return
        }
        isListening.set(true)
        Log.i(TAG, "使用 Android 原生 ASR")

        androidAsr?.startListening(object : AndroidAsrService.Callback {
            override fun onReady() {
                mainHandler.post { callback.onReady() }
            }

            override fun onPartialResult(text: String) {
                mainHandler.post { callback.onPartialResult(text) }
            }

            override fun onFinalResult(text: String) {
                isListening.set(false)
                mainHandler.post { callback.onFinalResult(text) }
            }

            override fun onError(error: String) {
                isListening.set(false)
                mainHandler.post { callback.onError(error) }
            }
        })
    }

    /**
     * 开始录音（PCM 16kHz mono）
     */
    private fun startRecording(onAudioData: (ByteArray) -> Unit) {
        recordExecutor.submit {
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, AUDIO_FORMAT)
                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord minBufferSize 错误: $minBufferSize")
                    mainHandler.post { currentCallback?.onError("录音初始化失败") }
                    return@submit
                }

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    SAMPLE_RATE,
                    CHANNEL,
                    AUDIO_FORMAT,
                    minBufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord 初始化失败")
                    audioRecord?.release()
                    audioRecord = null
                    mainHandler.post { currentCallback?.onError("录音器初始化失败") }
                    return@submit
                }

                audioRecord?.startRecording()
                val buffer = ByteArray(minBufferSize)
                Log.i(TAG, "开始录音, bufferSize=$minBufferSize")

                while (isListening.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val data = buffer.copyOf(read)
                        onAudioData(data)
                    }
                }

                Log.i(TAG, "录音结束")
            } catch (e: Exception) {
                Log.e(TAG, "录音异常", e)
                mainHandler.post { currentCallback?.onError("录音异常: ${e.message}") }
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "关闭AudioRecord异常", e)
                }
                audioRecord = null
            }
        }
    }

    fun stopListening() {
        isListening.set(false)
        try {
            dashscopeAsr?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止DashScope ASR异常", e)
        }
        try {
            androidAsr?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "停止Android ASR异常", e)
        }
    }

    fun isListening(): Boolean = isListening.get()

    fun destroy() {
        stopListening()
        try {
            dashscopeAsr?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止DashScope ASR异常", e)
        }
        try {
            androidAsr?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "销毁Android ASR异常", e)
        }
        dashscopeAsr = null
        androidAsr = null
        recordExecutor.shutdown()
    }
}
