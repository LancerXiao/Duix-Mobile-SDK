package ai.guiji.duix.test.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
    }

    interface Callback {
        fun onPcmData(data: ByteArray)
        fun onComplete()
        fun onError(error: String)
    }

    /**
     * 将 MP3 字节数据转换为 PCM
     */
    fun convert(mp3Data: ByteArray, callback: Callback) {
        try {
            // 1. 保存 MP3 到临时文件
            val tempMp3File = File(context.cacheDir, "tts_temp_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempMp3File).use { it.write(mp3Data) }

            // 2. 使用 MediaExtractor + MediaCodec 解码
            val extractor = MediaExtractor()
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
                tempMp3File.delete()
                callback.onError("No audio track found")
                return
            }

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: run {
                tempMp3File.delete()
                callback.onError("No MIME type")
                return
            }

            // 配置输出格式为目标 PCM
            val outputFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_RAW,
                TARGET_SAMPLE_RATE,
                TARGET_CHANNELS
            )
            outputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, MediaFormat.ENCODING_PCM_16BIT)

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // 输入数据到解码器
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
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
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
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
                            callback.onPcmData(data)
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            decoder.stop()
            decoder.release()
            extractor.release()
            tempMp3File.delete()

            callback.onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "MP3 to PCM conversion error", e)
            callback.onError(e.message ?: "Conversion failed")
        }
    }
}
