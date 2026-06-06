package ai.guiji.duix.test.service

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * TTS服务 - 使用小米MiMo的mimo-v2.5-tts模型
 * 流式返回PCM16音频数据
 */
class TtsService {

    companion object {
        private const val TAG = "TtsService"
        private const val BASE_URL = "https://api.xiaomimimo.com/v1"
        // 注意：需要MiMo API Key，这里先用Agnes的key作为占位
        // 用户需要去 https://platform.xiaomimimo.com/console/api-keys 获取
        private const val API_KEY = "sk-TuKWa0JQb9nGiUc7d6goWxpRzhUGfRpALI1DASAf1qOIXNCs"
        private const val MODEL = "mimo-v2.5-tts"
        private const val VOICE = "Chelsie"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    interface Callback {
        fun onPcmData(data: ByteArray)
        fun onComplete()
        fun onError(error: String)
    }

    fun synthesize(text: String, callback: Callback) {
        synthesize(text, VOICE, callback)
    }

    fun synthesize(text: String, voice: String, callback: Callback) {
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "用温柔的语气朗读")
                })
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
            })
            put("voice", voice)
            put("stream", true)
        }

        val body = requestBody.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .addHeader("api-key", API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                callback.onError(e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    callback.onError("HTTP ${response.code}: ${response.body?.string()}")
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        if (currentLine.startsWith("data: ")) {
                            val data = currentLine.substring(6).trim()
                            if (data == "[DONE]") {
                                callback.onComplete()
                                break
                            }
                            try {
                                val json = JSONObject(data)
                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content", "") ?: ""
                                    if (content.isNotEmpty()) {
                                        // MiMo TTS流式返回base64编码的PCM数据
                                        try {
                                            val pcmData = android.util.Base64.decode(content, android.util.Base64.NO_WRAP)
                                            if (pcmData.isNotEmpty()) {
                                                callback.onPcmData(pcmData)
                                            }
                                        } catch (e: Exception) {
                                            // 不是base64数据，可能是文本
                                            Log.d(TAG, "Non-base64 content: ${content.take(50)}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Skip malformed JSON
                            }
                        }
                    }
                    reader.close()
                } catch (e: Exception) {
                    callback.onError(e.message ?: "Parse error")
                }
            }
        })
    }
}
