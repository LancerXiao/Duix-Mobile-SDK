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
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    interface Callback {
        fun onReady()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    fun create() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available on this device")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    }

    fun startListening(callback: Callback) {
        if (speechRecognizer == null) {
            callback.onError("SpeechRecognizer not initialized")
            return
        }

        isListening = true

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                callback.onReady()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    else -> "Unknown error ($error)"
                }
                Log.e(TAG, "ASR error: $errorMsg")
                callback.onError(errorMsg)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
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

        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
        }
    }

    fun isListening(): Boolean = isListening

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
    }
}
