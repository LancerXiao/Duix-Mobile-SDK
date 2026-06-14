package ai.guiji.duix.test.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Android 原生 TTS 服务
 *
 * 两种工作模式：
 * 1. synthesize() - 合成WAV文件并提取PCM数据，推送给DUIX SDK驱动口型
 * 2. speakDirect() - 直接播放语音，不驱动口型（fallback 方案）
 *
 * synthesize() 使用 synthesizeToFile，在某些设备上可能不工作，
 * 此时自动降级到 speakDirect() 模式
 */
class AndroidTtsService(private val context: Context) {

    companion object {
        private const val TAG = "AndroidTtsService"
        private const val SYNTHESIS_TIMEOUT_MS = 15000L
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var synthesizeToFileWorks = true  // 标记 synthesizeToFile 是否可用

    interface Callback {
        fun onPcmData(data: ByteArray)
        fun onComplete()
        fun onError(error: String)
    }

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 优先使用英文语音（LLM 默认输出英文）
                var result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "英文语言不支持，尝试使用默认语言")
                    result = tts?.setLanguage(Locale.getDefault())
                }
                isInitialized = true
                Log.i(TAG, "Android TTS 初始化成功, language result=$result")
            } else {
                Log.e(TAG, "Android TTS 初始化失败: status=$status")
                isInitialized = false
            }
        }
    }

    fun isReady(): Boolean = isInitialized && tts != null

    /**
     * 合成语音并返回 PCM 数据
     * 优先使用 synthesizeToFile，如果失败则降级到 speakDirect
     */
    fun synthesize(text: String, callback: Callback) {
        if (!isReady()) {
            callback.onError("TTS 未初始化")
            return
        }

        if (synthesizeToFileWorks) {
            synthesizeViaFile(text, callback)
        } else {
            // synthesizeToFile 不可用，使用直接播放模式
            speakDirect(text, callback)
        }
    }

    /**
     * 方案1：使用 synthesizeToFile 生成 WAV 文件，提取 PCM 推送给 DUIX
     * 优点：可以获取 PCM 数据驱动口型
     * 缺点：某些设备/ROM 不支持
     */
    private fun synthesizeViaFile(text: String, callback: Callback) {
        val outputFile = File(context.cacheDir, "tts_output_${System.currentTimeMillis()}.wav")

        // 超时保护
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var synthesisCompleted = false
        val timeoutRunnable = Runnable {
            if (!synthesisCompleted) {
                Log.e(TAG, "TTS 合成超时")
                synthesisCompleted = true
                // 标记 synthesizeToFile 不可用，下次用 speakDirect
                synthesizeToFileWorks = false
                callback.onError("TTS 合成超时")
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, SYNTHESIS_TIMEOUT_MS)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "TTS 开始合成 (file mode)")
            }

            override fun onDone(utteranceId: String?) {
                synthesisCompleted = true
                timeoutHandler.removeCallbacks(timeoutRunnable)
                Log.i(TAG, "TTS 合成完成: exists=${outputFile.exists()}, size=${outputFile.length()}")
                if (outputFile.exists() && outputFile.length() > 44) {
                    Thread {
                        try {
                            val pcmData = readPcmFromWav(outputFile)
                            if (pcmData != null && pcmData.isNotEmpty()) {
                                Log.i(TAG, "提取PCM数据: ${pcmData.size} bytes")
                                callback.onPcmData(pcmData)
                                callback.onComplete()
                            } else {
                                Log.e(TAG, "PCM数据为空或读取失败，降级到 speakDirect")
                                synthesizeToFileWorks = false
                                // 在主线程回调
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    speakDirect(text, callback)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "读取WAV文件失败", e)
                            synthesizeToFileWorks = false
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                speakDirect(text, callback)
                            }
                        } finally {
                            outputFile.delete()
                        }
                    }.start()
                } else {
                    Log.e(TAG, "WAV文件不存在或太小，降级到 speakDirect")
                    synthesizeToFileWorks = false
                    speakDirect(text, callback)
                }
            }

            override fun onError(utteranceId: String?) {
                synthesisCompleted = true
                timeoutHandler.removeCallbacks(timeoutRunnable)
                Log.e(TAG, "TTS 合成出错，降级到 speakDirect")
                synthesizeToFileWorks = false
                speakDirect(text, callback)
            }
        })

        val utteranceId = "tts_${System.currentTimeMillis()}"
        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            result = tts?.synthesizeToFile(text, null as Bundle?, outputFile, utteranceId) ?: -1
        } else {
            @Suppress("DEPRECATION")
            result = tts?.synthesizeToFile(text, null as HashMap<String, String>?, outputFile.absolutePath) ?: -1
        }

        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "synthesizeToFile 返回错误，降级到 speakDirect")
            synthesisCompleted = true
            timeoutHandler.removeCallbacks(timeoutRunnable)
            synthesizeToFileWorks = false
            speakDirect(text, callback)
        }
    }

    /**
     * 方案2：使用 speak() 直接播放语音
     * 优点：所有设备都支持
     * 缺点：无法获取 PCM 数据驱动口型，但至少能发声
     *
     * 使用 AudioTrack 播放静音 PCM 来驱动 DUIX 口型动画
     */
    private fun speakDirect(text: String, callback: Callback) {
        Log.i(TAG, "使用 speakDirect 模式播放")

        // 生成 1.5 秒静音 PCM 数据（16kHz 单声道 16bit）来驱动口型动画
        // 这样数字人会有口型动画，同时 Android TTS 通过扬声器播放语音
        val durationMs = estimateSpeechDuration(text)
        val numSamples = (16000 * durationMs / 1000)
        val silencePcm = ByteArray(numSamples * 2)  // 16bit = 2 bytes per sample
        // 静音数据全是 0，不需要填充

        // 先回调 PCM 数据（驱动口型）
        callback.onPcmData(silencePcm)

        // 然后用 TTS 直接播放
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "TTS speakDirect 开始播放")
            }

            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "TTS speakDirect 播放完成")
                callback.onComplete()
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS speakDirect 播放出错")
                callback.onError("TTS 播放出错")
            }
        })

        val utteranceId = "tts_speak_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * 估算语音时长（毫秒）
     * 英文约 150 words/min，中文约 300 字/min
     */
    private fun estimateSpeechDuration(text: String): Long {
        val wordCount = text.split("\\s+".toRegex()).size
        // 保守估计：每个词 400ms（包括标点停顿）
        return maxOf(2000L, wordCount * 400L)
    }

    /**
     * 从WAV文件中读取PCM数据
     * WAV头通常是44字节，之后是PCM数据
     * 需要读取WAV头确认格式，并重采样到16kHz单声道
     */
    private fun readPcmFromWav(wavFile: File): ByteArray? {
        try {
            FileInputStream(wavFile).use { fis ->
                val header = ByteArray(44)
                val bytesRead = fis.read(header)
                if (bytesRead < 44) return null

                val riff = String(header, 0, 4)
                if (riff != "RIFF") {
                    Log.e(TAG, "不是有效的WAV文件: RIFF=$riff")
                    return null
                }

                val audioFormat = ((header[21].toInt() and 0xFF) shl 8) or (header[20].toInt() and 0xFF)
                val numChannels = ((header[23].toInt() and 0xFF) shl 8) or (header[22].toInt() and 0xFF)
                val sampleRate = ((header[27].toInt() and 0xFF) shl 24) or
                        ((header[26].toInt() and 0xFF) shl 16) or
                        ((header[25].toInt() and 0xFF) shl 8) or
                        (header[24].toInt() and 0xFF)
                val bitsPerSample = ((header[35].toInt() and 0xFF) shl 8) or (header[34].toInt() and 0xFF)

                Log.i(TAG, "WAV格式: format=$audioFormat, channels=$numChannels, sampleRate=$sampleRate, bitsPerSample=$bitsPerSample")

                val bos = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var len: Int
                while (fis.read(buffer).also { len = it } > 0) {
                    bos.write(buffer, 0, len)
                }
                var pcmData = bos.toByteArray()

                if (pcmData.isEmpty()) return null

                if (bitsPerSample != 16) {
                    Log.e(TAG, "不支持的位深度: $bitsPerSample")
                    return null
                }

                if (numChannels > 1) {
                    pcmData = convertToMono(pcmData, numChannels)
                }

                if (sampleRate != 16000) {
                    pcmData = resample(pcmData, sampleRate, 16000)
                }

                return pcmData
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取WAV文件异常", e)
            return null
        }
    }

    private fun convertToMono(data: ByteArray, channels: Int): ByteArray {
        val bytesPerSample = 2
        val frameSize = bytesPerSample * channels
        val numFrames = data.size / frameSize
        val monoData = ByteArray(numFrames * bytesPerSample)

        for (i in 0 until numFrames) {
            var sum = 0L
            for (ch in 0 until channels) {
                val offset = i * frameSize + ch * bytesPerSample
                if (offset + 1 < data.size) {
                    val sample = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
                    val signedSample = if (sample > 32767) sample - 65536 else sample
                    sum += signedSample
                }
            }
            val avgSample = (sum / channels).toInt().toShort()
            monoData[i * 2] = (avgSample.toInt() and 0xFF).toByte()
            monoData[i * 2 + 1] = ((avgSample.toInt() shr 8) and 0xFF).toByte()
        }
        return monoData
    }

    private fun resample(data: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        val numInputSamples = data.size / 2
        if (numInputSamples == 0) return ByteArray(0)

        val ratio = numInputSamples.toDouble() * targetRate / sourceRate
        val numOutputSamples = ratio.toInt()
        if (numOutputSamples == 0) return ByteArray(0)

        val outputData = ByteArray(numOutputSamples * 2)
        val inputSamples = ShortArray(numInputSamples)
        for (i in 0 until numInputSamples) {
            val offset = i * 2
            if (offset + 1 < data.size) {
                val low = data[offset].toInt() and 0xFF
                val high = data[offset + 1].toInt() and 0xFF
                inputSamples[i] = ((high shl 8) or low).toShort()
            }
        }

        for (i in 0 until numOutputSamples) {
            val srcIndex = i.toDouble() * sourceRate / targetRate
            val srcIndexInt = srcIndex.toInt()
            val fraction = srcIndex - srcIndexInt

            val sample = if (srcIndexInt + 1 < numInputSamples) {
                (inputSamples[srcIndexInt] * (1.0 - fraction) + inputSamples[srcIndexInt + 1] * fraction).toInt().toShort()
            } else if (srcIndexInt < numInputSamples) {
                inputSamples[srcIndexInt]
            } else {
                0
            }

            outputData[i * 2] = (sample.toInt() and 0xFF).toByte()
            outputData[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return outputData
    }

    fun stop() {
        tts?.stop()
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
