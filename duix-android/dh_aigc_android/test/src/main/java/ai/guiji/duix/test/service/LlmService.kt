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
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val SYSTEM_PROMPT = "你是一个友好的数字人助手，名叫小杜。请用简洁、自然的口语风格回答问题，每次回答不超过100字。不要使用markdown格式，用纯文本回答。"
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
        systemMsg.put("content", SYSTEM_PROMPT)
        messages.put(systemMsg)
    }

    fun chat(userMessage: String, callback: Callback) {
        chatWithRetry(userMessage, callback, 0)
    }

    private fun chatWithRetry(userMessage: String, callback: Callback, retryCount: Int) {
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
                // 如果是重试，需要移除刚才添加的用户消息（避免重复）
                // 但重试时我们重新添加，所以先移除
                removeLastMessage()

                if (retryCount < MAX_RETRIES) {
                    // 延迟重试
                    Thread {
                        try {
                            Thread.sleep(RETRY_DELAY_MS)
                        } catch (_: InterruptedException) {}
                        chatWithRetry(userMessage, callback, retryCount + 1)
                    }.start()
                } else {
                    val errorMsg = when {
                        e.message?.contains("timeout", ignoreCase = true) == true -> "请求超时，请检查网络"
                        e.message?.contains("connect", ignoreCase = true) == true -> "无法连接服务器"
                        e.message?.contains("dns", ignoreCase = true) == true -> "DNS解析失败"
                        else -> "网络错误: ${e.message ?: "未知错误"}"
                    }
                    callback.onError(errorMsg)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    removeLastMessage()
                    val errorBody = try { response.body?.string() } catch (_: Exception) { "" }
                    val errorMsg = when (response.code) {
                        401 -> "API认证失败"
                        429 -> "请求过于频繁，请稍后重试"
                        500, 502, 503 -> "服务器暂时不可用"
                        else -> "HTTP ${response.code}: $errorBody"
                    }

                    if (retryCount < MAX_RETRIES && response.code in 500..599) {
                        Thread {
                            try {
                                Thread.sleep(RETRY_DELAY_MS)
                            } catch (_: InterruptedException) {}
                            chatWithRetry(userMessage, callback, retryCount + 1)
                        }.start()
                    } else {
                        callback.onError(errorMsg)
                    }
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
                    var line: String?
                    var lastValidText = ""

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

                                // 处理不完整的流 - 如果没有收到完整内容但有部分内容
                                if (fullText.isEmpty() && lastValidText.isNotEmpty()) {
                                    fullText.append(lastValidText)
                                }

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
                                        lastValidText = content
                                        callback.onToken(content)
                                    }
                                }
                            } catch (e: Exception) {
                                // Skip malformed JSON chunks
                            }
                        }
                    }

                    // 处理流意外结束的情况
                    if (fullText.isNotEmpty()) {
                        // 流有内容但没收到 [DONE]，仍然保存
                        val assistantMsg = JSONObject()
                        assistantMsg.put("role", "assistant")
                        assistantMsg.put("content", fullText.toString())
                        messages.put(assistantMsg)
                        callback.onComplete(fullText.toString())
                    } else if (retryCount < MAX_RETRIES) {
                        // 空响应，重试
                        removeLastMessage()
                        Thread {
                            try {
                                Thread.sleep(RETRY_DELAY_MS)
                            } catch (_: InterruptedException) {}
                            chatWithRetry(userMessage, callback, retryCount + 1)
                        }.start()
                    } else {
                        callback.onComplete("")
                    }

                    reader.close()
                } catch (e: Exception) {
                    // 流读取异常，但如果已有部分内容，仍然返回
                    if (fullText.isNotEmpty()) {
                        val assistantMsg = JSONObject()
                        assistantMsg.put("role", "assistant")
                        assistantMsg.put("content", fullText.toString())
                        messages.put(assistantMsg)
                        callback.onComplete(fullText.toString())
                    } else {
                        removeLastMessage()
                        callback.onError(e.message ?: "解析响应失败")
                    }
                }
            }
        })
    }

    private fun removeLastMessage() {
        if (messages.length() > 0) {
            messages.remove(messages.length() - 1)
        }
    }

    fun clearHistory() {
        for (i in messages.length() - 1 downTo 0) { messages.remove(i) }
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", SYSTEM_PROMPT)
        messages.put(systemMsg)
    }
}
