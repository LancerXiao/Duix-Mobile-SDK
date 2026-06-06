package ai.guiji.duix.test.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * ASR服务 - 使用Android原生SpeechRecognizer
 * 基于Google语音识别，无需额外API Key
 */
class AndroidAsrService(private val context: android.content.Context) {

    companion object {
        private const val TAG = "AndroidAsrService"
        private const val MAX_RESTART_ATTEMPTS = 3
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isAvailable = false
    private var currentCallback: Callback? = null
    private var restartAttempts = 0

    interface Callback {
        fun onReady()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    fun create() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            isAvailable = false
            return
        }
        isAvailable = true
        createRecognizer()
    }

    private fun createRecognizer() {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            isAvailable = false
        }
    }

    fun startListening(callback: Callback) {
        currentCallback = callback

        if (!isAvailable) {
            callback.onError("此设备不支持语音识别")
            return
        }

        if (speechRecognizer == null) {
            // 尝试重新创建
            createRecognizer()
            if (speechRecognizer == null) {
                callback.onError("语音识别初始化失败，请使用文字输入")
                return
            }
        }

        isListening = true
        restartAttempts = 0

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "Ready for speech")
                callback.onReady()
            }

            override fun onBeginningOfSpeech() {
                Log.i(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.i(TAG, "End of speech")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
                    SpeechRecognizer.ERROR_NO_MATCH -> "无法识别"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                    else -> "未知错误 ($error)"
                }
                Log.e(TAG, "ASR error: $errorMsg (code=$error)")

                // 对于可恢复的错误，自动重启
                if (shouldAutoRestart(error) && restartAttempts < MAX_RESTART_ATTEMPTS) {
                    restartAttempts++
                    Log.i(TAG, "Auto-restarting listener (attempt $restartAttempts/$MAX_RESTART_ATTEMPTS)")
                    tryRestartListening(callback)
                } else {
                    callback.onError(errorMsg)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                restartAttempts = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    callback.onFinalResult(matches[0])
                } else {
                    callback.onFinalResult("")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    callback.onPartialResult(matches[0])
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
            callback.onError("启动语音识别失败: ${e.message}")
        }
    }

    private fun shouldAutoRestart(error: Int): Boolean {
        return when (error) {
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SERVER -> true
            else -> false
        }
    }

    private fun tryRestartListening(callback: Callback) {
        try {
            // 销毁旧的识别器并重新创建
            speechRecognizer?.destroy()
            createRecognizer()

            if (speechRecognizer == null) {
                callback.onError("语音识别重启失败")
                return
            }

            startListening(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart listening", e)
            callback.onError("语音识别重启失败: ${e.message}")
        }
    }

    fun stopListening() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping listening", e)
            }
            isListening = false
        }
    }

    fun isListening(): Boolean = isListening

    fun isAvailable(): Boolean = isAvailable

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying SpeechRecognizer", e)
        }
        speechRecognizer = null
        isListening = false
        currentCallback = null
    }
}
