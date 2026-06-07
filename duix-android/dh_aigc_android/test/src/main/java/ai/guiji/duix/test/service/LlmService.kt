package ai.guiji.duix.test.service

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LLM服务 - 使用Agnes AI的agnes-2.0-flash模型
 * 支持流式SSE输出
 */
class LlmService {

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val messages = JSONArray()
    private val isRequesting = AtomicBoolean(false)

    interface Callback {
        fun onToken(token: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
    }

    init {
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", AiConfig.LLM_SYSTEM_PROMPT)
        messages.put(systemMsg)
    }

    fun chat(userMessage: String, callback: Callback) {
        if (!isRequesting.compareAndSet(false, true)) {
            callback.onError("正在请求中，请稍候")
            return
        }
        chatWithRetry(userMessage, callback, 0)
    }

    private fun chatWithRetry(userMessage: String, callback: Callback, retryCount: Int) {
        // 添加用户消息
        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", userMessage)
        synchronized(messages) {
            messages.put(userMsg)
        }

        val requestBody = JSONObject()
        requestBody.put("model", AiConfig.LLM_MODEL)
        requestBody.put("messages", messages)
        requestBody.put("stream", true)
        requestBody.put("max_tokens", 512)
        requestBody.put("temperature", 0.7)

        val body = requestBody.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${AiConfig.LLM_BASE_URL}/chat/completions")
            .addHeader("Authorization", "Bearer ${AiConfig.DASHSCOPE_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val fullText = StringBuilder()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                synchronized(messages) {
                    removeLastMessage()
                }

                if (retryCount < MAX_RETRIES) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        chatWithRetry(userMessage, callback, retryCount + 1)
                    }, RETRY_DELAY_MS)
                } else {
                    isRequesting.set(false)
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
                    synchronized(messages) {
                        removeLastMessage()
                    }
                    val errorBody = try { response.body?.string() } catch (_: Exception) { "" }
                    val errorMsg = when (response.code) {
                        401 -> "API认证失败"
                        429 -> "请求过于频繁，请稍后重试"
                        500, 502, 503 -> "服务器暂时不可用"
                        else -> "HTTP ${response.code}: $errorBody"
                    }

                    if (retryCount < MAX_RETRIES && response.code in 500..599) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            chatWithRetry(userMessage, callback, retryCount + 1)
                        }, RETRY_DELAY_MS)
                    } else {
                        isRequesting.set(false)
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
                                synchronized(messages) {
                                    val assistantMsg = JSONObject()
                                    assistantMsg.put("role", "assistant")
                                    assistantMsg.put("content", fullText.toString())
                                    messages.put(assistantMsg)
                                }

                                if (fullText.isEmpty() && lastValidText.isNotEmpty()) {
                                    fullText.append(lastValidText)
                                }

                                isRequesting.set(false)
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
                            } catch (_: Exception) {
                                // Skip malformed JSON chunks
                            }
                        }
                    }

                    // 处理流意外结束的情况
                    if (fullText.isNotEmpty()) {
                        synchronized(messages) {
                            val assistantMsg = JSONObject()
                            assistantMsg.put("role", "assistant")
                            assistantMsg.put("content", fullText.toString())
                            messages.put(assistantMsg)
                        }
                        isRequesting.set(false)
                        callback.onComplete(fullText.toString())
                    } else if (retryCount < MAX_RETRIES) {
                        synchronized(messages) {
                            removeLastMessage()
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            chatWithRetry(userMessage, callback, retryCount + 1)
                        }, RETRY_DELAY_MS)
                    } else {
                        isRequesting.set(false)
                        callback.onComplete("")
                    }

                    try { reader.close() } catch (_: Exception) {}
                } catch (e: Exception) {
                    if (fullText.isNotEmpty()) {
                        synchronized(messages) {
                            val assistantMsg = JSONObject()
                            assistantMsg.put("role", "assistant")
                            assistantMsg.put("content", fullText.toString())
                            messages.put(assistantMsg)
                        }
                        isRequesting.set(false)
                        callback.onComplete(fullText.toString())
                    } else {
                        synchronized(messages) {
                            removeLastMessage()
                        }
                        isRequesting.set(false)
                        callback.onError(e.message ?: "解析响应失败")
                    }
                }
            }
        })
    }

    private fun removeLastMessage() {
        // 调用方需在synchronized(messages)块内调用
        if (messages.length() > 0) {
            messages.remove(messages.length() - 1)
        }
    }

    fun clearHistory() {
        synchronized(messages) {
            for (i in messages.length() - 1 downTo 0) { messages.remove(i) }
            val systemMsg = JSONObject()
            systemMsg.put("role", "system")
            systemMsg.put("content", AiConfig.LLM_SYSTEM_PROMPT)
            messages.put(systemMsg)
        }
    }

    fun isRequesting(): Boolean = isRequesting.get()
}
