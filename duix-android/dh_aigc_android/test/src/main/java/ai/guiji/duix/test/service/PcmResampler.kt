package ai.guiji.duix.test.service

/**
 * PCM 重采样工具
 * 用于将任意采样率的 16bit mono PCM 数据重采样到目标采样率
 * 使用线性插值算法，实时性优先
 *
 * 重要：DUIX SDK 要求输入 PCM 16kHz mono 16bit（见 dhmfcc/mfcc.cpp 的 MFCC_RATE = 16000）
 * Qwen TTS (qwen3-tts-flash-realtime) 输出 24kHz PCM，必须重采样后才能推送给 DUIX
 */
object PcmResampler {

    /**
     * 将 PCM 16bit 数据重采样到目标采样率（mono）
     *
     * @param data 16bit PCM 数据（小端序）
     * @param sourceRate 源采样率
     * @param targetRate 目标采样率
     * @return 重采样后的 PCM 数据
     */
    fun resample(data: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (data.size < 2) return ByteArray(0)
        if (sourceRate == targetRate) return data

        val numInputSamples = data.size / 2
        if (numInputSamples == 0) return ByteArray(0)

        val ratio = numInputSamples.toDouble() * targetRate / sourceRate
        val numOutputSamples = ratio.toInt()
        if (numOutputSamples == 0) return ByteArray(0)

        val outputData = ByteArray(numOutputSamples * 2)

        // 把输入字节转换为 short 数组
        val inputSamples = ShortArray(numInputSamples)
        for (i in 0 until numInputSamples) {
            val offset = i * 2
            if (offset + 1 < data.size) {
                val low = data[offset].toInt() and 0xFF
                val high = data[offset + 1].toInt() and 0xFF
                inputSamples[i] = ((high shl 8) or low).toShort()
            }
        }

        // 线性插值重采样
        for (i in 0 until numOutputSamples) {
            val srcIndex = i.toDouble() * sourceRate / targetRate
            val srcIndexInt = srcIndex.toInt()
            val fraction = srcIndex - srcIndexInt

            val sample = if (srcIndexInt + 1 < numInputSamples) {
                (inputSamples[srcIndexInt] * (1.0 - fraction) +
                 inputSamples[srcIndexInt + 1] * fraction).toInt().toShort()
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
     * 多声道转单声道（平均）
     */
    fun toMono(data: ByteArray, sourceChannels: Int): ByteArray {
        if (sourceChannels <= 1) return data
        val bytesPerSample = 2
        val frameSize = bytesPerSample * sourceChannels
        val numFrames = data.size / frameSize
        val monoData = ByteArray(numFrames * bytesPerSample)

        for (i in 0 until numFrames) {
            var sum = 0L
            for (ch in 0 until sourceChannels) {
                val offset = i * frameSize + ch * bytesPerSample
                if (offset + 1 < data.size) {
                    val sample = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
                    val signedSample = if (sample > 32767) sample - 65536 else sample
                    sum += signedSample
                }
            }
            val avgSample = (sum / sourceChannels).toInt().toShort()
            monoData[i * 2] = (avgSample.toInt() and 0xFF).toByte()
            monoData[i * 2 + 1] = ((avgSample.toInt() shr 8) and 0xFF).toByte()
        }
        return monoData
    }
}
