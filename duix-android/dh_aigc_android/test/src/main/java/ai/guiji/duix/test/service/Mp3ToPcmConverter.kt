package ai.guiji.duix.test.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * MP3 转 PCM 工具类
 * 将 MP3 音频文件解码为 16kHz 单通道 16bit PCM 数据
 * 用于将 Edge TTS 的 MP3 输出转换为 DUIX SDK 所需的 PCM 格式
 */
class Mp3ToPcmConverter(private val context: Context) {

    companion object {
        private const val TAG = "Mp3ToPcmConverter"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TARGET_CHANNELS = 1
        private const val DEQUEUE_TIMEOUT_US = 10000L
    }

    interface Callback {
        fun onPcmData(data: ByteArray)
        fun onComplete()
        fun onError(error: String)
    }

    /**
     * 将 MP3 字节数组转换为 16kHz 单声道 16bit PCM
     */
    fun convert(mp3Data: ByteArray, callback: Callback) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var tempMp3File: File? = null

        try {
            // 1. 保存 MP3 到临时文件
            tempMp3File = File(context.cacheDir, "tts_temp_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempMp3File).use { it.write(mp3Data) }

            // 2. 使用 MediaExtractor + MediaCodec 解码
            extractor = MediaExtractor()
            extractor.setDataSource(tempMp3File.absolutePath)

            // 找到音频轨道
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex == -1) {
                callback.onError("No audio track found")
                return
            }

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: run {
                callback.onError("No MIME type")
                return
            }

            // 获取输入音频的采样率和通道数
            val inputSampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
            } else {
                try { inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { 44100 }
            }
            val inputChannels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
            } else {
                try { inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { 1 }
            }
            Log.i(TAG, "Input audio: ${inputSampleRate}Hz, ${inputChannels}ch")

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // 输入数据到解码器
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputBufferIndex, 0, sampleSize,
                                extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                // 从解码器获取输出
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (bufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(data)

                            // 重采样到 16kHz 单声道 16bit PCM
                            val resampledData = resamplePcm(data, inputSampleRate, inputChannels)
                            if (resampledData != null && resampledData.isNotEmpty()) {
                                callback.onPcmData(resampledData)
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            callback.onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "MP3 to PCM conversion error", e)
            callback.onError(e.message ?: "Conversion failed")
        } finally {
            // 确保资源释放
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            try { tempMp3File?.delete() } catch (_: Exception) {}
        }
    }

    /**
     * 将 PCM 数据重采样到 16kHz 单声道 16bit
     */
    private fun resamplePcm(data: ByteArray, sourceSampleRate: Int, sourceChannels: Int): ByteArray? {
        if (data.size < 2) return null

        if (sourceSampleRate == TARGET_SAMPLE_RATE && sourceChannels == TARGET_CHANNELS) {
            return data
        }

        val monoData = if (sourceChannels > 1) {
            convertToMono(data, sourceChannels)
        } else {
            data
        }

        val resampledData = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
            resample(monoData, sourceSampleRate, TARGET_SAMPLE_RATE)
        } else {
            monoData
        }

        return resampledData
    }

    /**
     * 多声道转单声道
     */
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

    /**
     * 线性插值重采样
     */
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
}
