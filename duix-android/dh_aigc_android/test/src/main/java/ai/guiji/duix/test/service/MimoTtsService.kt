package ai.guiji.duix.test.service

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小米 MiMo TTS 服务 - 使用 mimo-v2.5-tts 语音合成
 * API 地址: https://token-plan-cn.xiaomimimo.com/v1/chat/completions
 *
 * 协议（OpenAI 兼容的 Chat Completions 格式）：
 *   请求：
 *     - POST /chat/completions
 *     - messages 中 assistant 角色放待合成文本
 *     - audio 对象指定格式和音色
 *   响应：
 *     - choices[0].message.audio.data 返回 Base64 编码的 PCM 音频
 *
 * 音色列表：
 *   - 白桦（默认）：中文男声，成熟大叔
 *   - 苏打：中文男声，阳光少年
 */
class MimoTtsService {

    companion object {
        private const val TAG = "MimoTtsService"
        private const val BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1/chat/completions"
        private const val MODEL = "mimo-v2.5-tts"
        private const val DEFAULT_VOICE = "白桦"  // 中文男声，成熟大叔
        private const val AUDIO_FORMAT = "pcm16"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 800L
        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 60L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val isSynthesizing = AtomicBoolean(false)
    private var shouldStop = false  // 主动停止标志
    private var currentCall: Call? = null  // 当前请求，用于取消

    interface Callback {
        fun onAudioData(pcmData: ByteArray)  // PCM 16-bit 数据
        fun onComplete()
        fun onError(error: String)
    }

    /**
     * 合成语音
     * @param text 要合成的文本
     * @param voice 音色，默认 白桦（中文男声，成熟大叔）
     * @param callback 回调
     */
    fun synthesize(text: String, voice: String = DEFAULT_VOICE, callback: Callback) {
        if (text.isBlank()) {
            callback.onError("文本为空")
            return
        }
        synthesizeInternal(text, voice, callback, 0)
    }

    private fun synthesizeInternal(text: String, voice: String, callback: Callback, retryCount: Int) {
        if (isSynthesizing.getAndSet(true)) {
            callback.onError("正在合成中，请稍候")
            return
        }
        shouldStop = false

        Log.i(TAG, "MiMo TTS 开始 (第${retryCount + 1}次): voice=$voice, text=${text.take(30)}...")

        // 构建请求体
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
            })
            put("audio", JSONObject().apply {
                put("format", AUDIO_FORMAT)
                put("voice", voice)
            })
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer ${AiConfig.MIMO_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(MediaType.parse("application/json"), requestBody.toString()))
            .build()

        currentCall = client.newCall(request)
        currentCall!!.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "MiMo TTS 请求失败 (attempt ${retryCount + 1}): ${e.message}", e)
                handleError("请求失败: ${e.message ?: "未知"}", callback, retryCount, text, voice)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val errorBody = response.body()?.string() ?: ""
                        Log.e(TAG, "MiMo TTS HTTP 错误: ${response.code()} $errorBody")
                        handleError("HTTP ${response.code()}: $errorBody", callback, retryCount, text, voice)
                        return
                    }

                    val responseBody = response.body()?.string()
                    if (responseBody.isNullOrEmpty()) {
                        Log.e(TAG, "MiMo TTS 响应为空")
                        handleError("响应为空", callback, retryCount, text, voice)
                        return
                    }

                    if (shouldStop) {
                        Log.i(TAG, "MiMo TTS 已被停止，忽略响应")
                        isSynthesizing.set(false)
                        return
                    }

                    // 解析响应
                    val json = JSONObject(responseBody)
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        Log.e(TAG, "MiMo TTS 响应无 choices: $responseBody")
                        handleError("响应无 choices", callback, retryCount, text, voice)
                        return
                    }

                    val message = choices.getJSONObject(0).optJSONObject("message")
                    if (message == null) {
                        Log.e(TAG, "MiMo TTS 响应无 message")
                        handleError("响应无 message", callback, retryCount, text, voice)
                        return
                    }

                    val audio = message.optJSONObject("audio")
                    if (audio == null) {
                        Log.e(TAG, "MiMo TTS 响应无 audio")
                        handleError("响应无 audio", callback, retryCount, text, voice)
                        return
                    }

                    val audioDataB64 = audio.optString("data", "")
                    if (audioDataB64.isEmpty()) {
                        Log.e(TAG, "MiMo TTS 响应 audio.data 为空")
                        handleError("audio.data 为空", callback, retryCount, text, voice)
                        return
                    }

                    // Base64 解码得到 PCM 数据
                    val pcmData = android.util.Base64.decode(audioDataB64, android.util.Base64.DEFAULT)
                    Log.i(TAG, "MiMo TTS 收到音频数据: ${pcmData.size} bytes")

                    if (pcmData.isNotEmpty() && !shouldStop) {
                        callback.onAudioData(pcmData)
                    }

                    if (!shouldStop) {
                        isSynthesizing.set(false)
                        callback.onComplete()
                        Log.i(TAG, "MiMo TTS 合成完成")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "MiMo TTS 解析响应异常", e)
                    handleError("解析响应异常: ${e.message ?: "未知"}", callback, retryCount, text, voice)
                }
            }
        })
    }

    /**
     * 统一错误处理（带重试）
     */
    private fun handleError(
        error: String,
        callback: Callback,
        retryCount: Int,
        text: String,
        voice: String
    ) {
        isSynthesizing.set(false)
        if (shouldStop) {
            // 用户主动停止，不重试
            return
        }
        if (retryCount < MAX_RETRIES) {
            Log.w(TAG, "MiMo TTS 失败，${RETRY_DELAY_MS}ms 后重试 (${retryCount + 1}/$MAX_RETRIES)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                synthesizeInternal(text, voice, callback, retryCount + 1)
            }, RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "MiMo TTS 重试${MAX_RETRIES}次后仍失败: $error")
            callback.onError(error)
        }
    }

    /**
     * 主动停止当前合成
     */
    fun stop() {
        shouldStop = true
        isSynthesizing.set(false)
        try {
            currentCall?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "取消请求异常: ${e.message}")
        }
        currentCall = null
    }

    fun isSynthesizing(): Boolean = isSynthesizing.get()
}
