package ai.guiji.duix.test.service

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * LLM服务 - 使用Agnes AI的agnes-2.0-flash模型
 * 支持流式SSE输出
 */
class LlmService {

    companion object {
        private const val BASE_URL = "https://apihub.agnes-ai.com/v1"
        private const val API_KEY = "sk-TuKWa0JQb9nGiUc7d6goWxpRzhUGfRpALI1DASAf1qOIXNCs"
        private const val MODEL = "agnes-2.0-flash"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val messages = JSONArray()

    interface Callback {
        fun onToken(token: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
    }

    init {
        // 添加系统提示词
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", "你是一个友好的AI数字人助手。请用简洁、自然的口语化方式回答问题，每次回复控制在2-3句话以内。不要使用markdown格式。")
        messages.put(systemMsg)
    }

    fun chat(userMessage: String, callback: Callback) {
        // 添加用户消息
        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", userMessage)
        messages.put(userMsg)

        val requestBody = JSONObject()
        requestBody.put("model", MODEL)
        requestBody.put("messages", messages)
        requestBody.put("stream", true)
        requestBody.put("max_tokens", 512)
        requestBody.put("temperature", 0.7)

        val body = requestBody.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val fullText = StringBuilder()

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
                                // 保存助手回复到历史
                                val assistantMsg = JSONObject()
                                assistantMsg.put("role", "assistant")
                                assistantMsg.put("content", fullText.toString())
                                messages.put(assistantMsg)
                                callback.onComplete(fullText.toString())
                                break
                            }
                            try {
                                val json = JSONObject(data)
                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content", "") ?: ""
                                    if (content.isNotEmpty()) {
                                        fullText.append(content)
                                        callback.onToken(content)
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

    fun clearHistory() {
        for (i in messages.length() - 1 downTo 0) { messages.remove(i) }
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", "你是一个友好的AI数字人助手。请用简洁、自然的口语化方式回答问题，每次回复控制在2-3句话以内。不要使用markdown格式。")
        messages.put(systemMsg)
    }
}
