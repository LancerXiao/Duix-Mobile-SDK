package ai.guiji.duix.test.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 实时录音管理类
 * 输出格式：16kHz, 单通道, 16bit PCM
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL, AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var executor: ExecutorService? = null

    interface Callback {
        fun onPcmData(data: ByteArray)
        fun onError(error: String)
    }

    fun start(callback: Callback): Boolean {
        if (isRecording) return true

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback.onError("AudioRecord not initialized")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            executor = Executors.newSingleThreadExecutor()

            executor?.execute {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readCount > 0) {
                        val data = buffer.copyOfRange(0, readCount)
                        callback.onPcmData(data)
                    } else if (readCount == AudioRecord.ERROR_INVALID_OPERATION ||
                        readCount == AudioRecord.ERROR_BAD_VALUE) {
                        callback.onError("AudioRecord read error: $readCount")
                        break
                    }
                }
            }

            return true
        } catch (e: SecurityException) {
            callback.onError("No RECORD_AUDIO permission")
            return false
        } catch (e: Exception) {
            callback.onError(e.message ?: "Failed to start recording")
            return false
        }
    }

    fun stop() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        executor?.shutdown()
        executor = null
    }

    fun isRecording(): Boolean = isRecording
}
