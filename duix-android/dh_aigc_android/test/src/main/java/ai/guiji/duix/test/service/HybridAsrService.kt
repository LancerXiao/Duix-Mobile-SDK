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
                mainHandler.post { currentCallback?.onReady() }
            }

            override fun onPartialResult(text: String) {
                mainHandler.post { currentCallback?.onPartialResult(text) }
            }

            override fun onFinalResult(text: String) {
                isListening.set(false)
                val cb = currentCallback
                mainHandler.post { cb?.onFinalResult(text) }
            }

            override fun onError(error: String) {
                Log.e(TAG, "DashScope ASR 错误: $error")
                isListening.set(false)
                // 停止录音
                try {
                    audioRecord?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "停止AudioRecord异常", e)
                }
                // 错误信息透传给上层，便于用户诊断
                // 只有Android ASR可用且DashScope完全无法连接时才fallback
                if (error.contains("HTTP 401") || error.contains("HTTP 403") || error.contains("InvalidApiKey")) {
                    // API key 问题，不要再尝试Android ASR（小米设备会提示"不支持"）
                    val cb = currentCallback
                    mainHandler.post { cb?.onError("语音服务认证失败，请联系管理员检查API Key") }
                } else if (androidAsr != null && android.speech.SpeechRecognizer.isRecognitionAvailable(context) && currentCallback != null) {
                    // 其他网络错误，尝试 fallback 到 Android
                    Log.i(TAG, "fallback到Android ASR")
                    val cb = currentCallback
                    if (cb != null) startAndroidListening(cb)
                } else {
                    val cb = currentCallback
                    mainHandler.post { cb?.onError("语音识别失败: $error，请使用文字输入") }
                }
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
                mainHandler.post { currentCallback?.onReady() }
            }

            override fun onPartialResult(text: String) {
                mainHandler.post { currentCallback?.onPartialResult(text) }
            }

            override fun onFinalResult(text: String) {
                isListening.set(false)
                val cb = currentCallback
                mainHandler.post { cb?.onFinalResult(text) }
            }

            override fun onError(error: String) {
                isListening.set(false)
                val cb = currentCallback
                mainHandler.post { cb?.onError(error) }
            }
        })
    }

    /**
     * 开始录音（PCM 16kHz mono）
     * bufferSize 取 minBufferSize 的 4 倍，避免 AudioRecord 频繁欠载
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

                // 录音缓冲区取 minBufferSize 的 4 倍，避免 read() 返回负数或过小的包
                val bufferSize = minBufferSize * 4

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL,
                    AUDIO_FORMAT,
                    bufferSize
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
                Log.i(TAG, "开始录音, minBufferSize=$minBufferSize, allocBufferSize=$bufferSize")

                while (isListening.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val data = buffer.copyOf(read)
                        onAudioData(data)
                    } else if (read < 0) {
                        // AudioRecord 错误，重置
                        Log.w(TAG, "AudioRecord.read 错误: $read")
                        try {
                            audioRecord?.stop()
                            audioRecord?.release()
                        } catch (e: Exception) {
                            Log.w(TAG, "重置AudioRecord异常", e)
                        }
                        audioRecord = null
                        if (isListening.get()) {
                            // 重新初始化录音
                            try {
                                audioRecord = AudioRecord(
                                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                    SAMPLE_RATE,
                                    CHANNEL,
                                    AUDIO_FORMAT,
                                    bufferSize
                                )
                                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                                    audioRecord?.startRecording()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "重新初始化AudioRecord失败", e)
                            }
                        }
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
        // 先置标志位让录音循环退出
        isListening.set(false)
        // 先清空回调，防止 WebSocket 关闭时迟到的 onFinalResult/onError 把状态改回去
        // CallActivity 自己负责 UI 状态更新
        currentCallback = null
        try {
            // 完整关闭：发 finish-task 并关闭 WebSocket
            dashscopeAsr?.close()
        } catch (e: Exception) {
            Log.e(TAG, "停止DashScope ASR异常", e)
        }
        dashscopeAsr = null
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
