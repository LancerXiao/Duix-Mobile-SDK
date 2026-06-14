package ai.guiji.duix.test.service

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Android 原生 TTS 服务
 * 使用系统 TextToSpeech 引擎合成语音
 * 作为 Edge TTS 的备选方案，无需网络连接
 *
 * 两种工作模式：
 * 1. synthesize() - 合成WAV文件并提取PCM数据，用于驱动数字人口型
 * 2. speak() - 直接播放语音，不驱动口型
 */
class AndroidTtsService(private val context: Context) {

    companion object {
        private const val TAG = "AndroidTtsService"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    interface Callback {
        fun onPcmData(data: ByteArray)
        fun onComplete()
        fun onError(error: String)
    }

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINA)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "中文语言不支持，尝试使用默认语言")
                    tts?.setLanguage(Locale.getDefault())
                }
                isInitialized = true
                Log.i(TAG, "Android TTS 初始化成功")
            } else {
                Log.e(TAG, "Android TTS 初始化失败: status=$status")
                isInitialized = false
            }
        }
    }

    fun isReady(): Boolean = isInitialized && tts != null

    /**
     * 使用 Android TTS 合成语音为WAV文件，然后提取PCM数据
     * PCM数据为16kHz单声道16bit，可直接推送给DUIX SDK
     */
    fun synthesize(text: String, callback: Callback) {
        if (!isReady()) {
            callback.onError("TTS 未初始化")
            return
        }

        val outputFile = File(context.cacheDir, "tts_output_${System.currentTimeMillis()}.wav")

        // 合成超时保护：10秒内未完成则报错
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var synthesisCompleted = false
        val timeoutRunnable = Runnable {
            if (!synthesisCompleted) {
                Log.e(TAG, "TTS 合成超时（10秒）")
                callback.onError("TTS 合成超时")
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, 10000L)

        // 设置进度监听
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "TTS 开始合成")
            }

            override fun onDone(utteranceId: String?) {
                synthesisCompleted = true
                timeoutHandler.removeCallbacks(timeoutRunnable)
                Log.i(TAG, "TTS 合成完成: ${outputFile.absolutePath}, exists=${outputFile.exists()}, size=${outputFile.length()}")
                if (outputFile.exists() && outputFile.length() > 44) {
                    // 读取WAV文件并提取PCM数据
                    Thread {
                        try {
                            val pcmData = readPcmFromWav(outputFile)
                            if (pcmData != null && pcmData.isNotEmpty()) {
                                Log.i(TAG, "提取PCM数据: ${pcmData.size} bytes")
                                callback.onPcmData(pcmData)
                            } else {
                                Log.e(TAG, "PCM数据为空或读取失败")
                                callback.onError("PCM数据为空")
                            }
                            callback.onComplete()
                        } catch (e: Exception) {
                            Log.e(TAG, "读取WAV文件失败", e)
                            callback.onError("读取WAV文件失败: ${e.message}")
                        } finally {
                            outputFile.delete()
                        }
                    }.start()
                } else {
                    Log.e(TAG, "WAV文件不存在或太小")
                    callback.onError("WAV文件生成失败")
                }
            }

            override fun onError(utteranceId: String?) {
                synthesisCompleted = true
                timeoutHandler.removeCallbacks(timeoutRunnable)
                Log.e(TAG, "TTS 合成出错")
                callback.onError("TTS 合成出错")
            }
        })

        // 合成到文件
        val utteranceId = "tts_${System.currentTimeMillis()}"
        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            result = tts?.synthesizeToFile(text, null as Bundle?, outputFile, utteranceId) ?: -1
        } else {
            @Suppress("DEPRECATION")
            result = tts?.synthesizeToFile(text, null as HashMap<String, String>?, outputFile.absolutePath) ?: -1
        }

        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "synthesizeToFile 返回错误")
            callback.onError("synthesizeToFile 失败")
        }
    }

    /**
     * 从WAV文件中读取PCM数据
     * WAV头通常是44字节，之后是PCM数据
     * 注意：Android TTS生成的WAV格式可能是各种采样率和声道数
     * 需要读取WAV头确认格式，并重采样到16kHz单声道
     */
    private fun readPcmFromWav(wavFile: File): ByteArray? {
        try {
            FileInputStream(wavFile).use { fis ->
                // 读取WAV头
                val header = ByteArray(44)
                val bytesRead = fis.read(header)
                if (bytesRead < 44) return null

                // 解析WAV头
                // RIFF header
                val riff = String(header, 0, 4)
                if (riff != "RIFF") {
                    Log.e(TAG, "不是有效的WAV文件: RIFF=$riff")
                    return null
                }

                // fmt chunk
                val audioFormat = ((header[21].toInt() and 0xFF) shl 8) or (header[20].toInt() and 0xFF)
                val numChannels = ((header[23].toInt() and 0xFF) shl 8) or (header[22].toInt() and 0xFF)
                val sampleRate = ((header[27].toInt() and 0xFF) shl 24) or
                        ((header[26].toInt() and 0xFF) shl 16) or
                        ((header[25].toInt() and 0xFF) shl 8) or
                        (header[24].toInt() and 0xFF)
                val bitsPerSample = ((header[35].toInt() and 0xFF) shl 8) or (header[34].toInt() and 0xFF)

                Log.i(TAG, "WAV格式: format=$audioFormat, channels=$numChannels, sampleRate=$sampleRate, bitsPerSample=$bitsPerSample")

                // 读取PCM数据
                val bos = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var len: Int
                while (fis.read(buffer).also { len = it } > 0) {
                    bos.write(buffer, 0, len)
                }
                var pcmData = bos.toByteArray()

                if (pcmData.isEmpty()) return null

                // 如果不是16bit，无法处理
                if (bitsPerSample != 16) {
                    Log.e(TAG, "不支持的位深度: $bitsPerSample")
                    return null
                }

                // 如果是多声道，转为单声道
                if (numChannels > 1) {
                    pcmData = convertToMono(pcmData, numChannels)
                }

                // 如果采样率不是16kHz，重采样
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

    /**
     * 直接使用 TTS 播放（不获取 PCM 数据，不驱动口型）
     */
    fun speak(text: String, callback: (() -> Unit)? = null) {
        if (!isReady()) {
            callback?.invoke()
            return
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { callback?.invoke() }
            override fun onError(utteranceId: String?) { callback?.invoke() }
        })

        val utteranceId = "tts_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
