package ai.guiji.duix.test.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TTS服务 - 使用Android原生TextToSpeech
 * 基于设备TTS引擎，免费且可离线使用
 * 输出PCM数据驱动数字人口型
 */
class AndroidTtsService(private val context: Context) {

    companion object {
        private const val TAG = "AndroidTtsService"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var callback: Callback? = null

    interface Callback {
        fun onPcmData(data: ByteArray)
        fun onComplete()
        fun onError(error: String)
    }

    fun init(onReady: (() -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINA)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Chinese language not supported, trying default")
                    tts?.setLanguage(Locale.getDefault())
                }
                isReady = true
                onReady?.invoke()
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
                callback?.onError("TTS init failed")
            }
        }
    }

    /**
     * 合成语音并通过DUIX SDK驱动数字人
     * 使用DUIX SDK的PCM推送方式
     */
    fun synthesizeAndDrive(duix: ai.guiji.duix.sdk.client.DUIX, text: String, callback: Callback) {
        if (!isReady || tts == null) {
            callback.onError("TTS not ready")
            return
        }

        this.callback = callback

        // 设置进度监听
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS start speaking")
                duix.startPush()
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS done speaking")
                duix.stopPush()
                callback.onComplete()
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error")
                duix.stopPush()
                callback.onError("TTS synthesis error")
            }
        })

        // 使用QUEUE_FLUSH确保新的语音会打断之前的
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "duix_tts_${System.currentTimeMillis()}")
        }

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "duix_tts")
        if (result == TextToSpeech.ERROR) {
            callback.onError("TTS speak failed")
        }
    }

    /**
     * 简单的语音合成（不驱动数字人，仅播放语音）
     */
    fun speak(text: String) {
        if (isReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun isReady(): Boolean = isReady

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
