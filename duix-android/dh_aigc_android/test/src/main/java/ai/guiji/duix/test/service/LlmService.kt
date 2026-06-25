package ai.guiji.duix.test.service

import android.util.Log
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
 * LLM服务 - 支持 Agnes AI 和 MiMo 等多引擎
 * 支持流式SSE输出
 */
class LlmService {

    companion object {
        private const val TAG = "LlmService"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        // 最大保留对话轮数（1轮 = 1条user + 1条assistant），超出时删除最早的轮次
        private const val MAX_CONVERSATION_ROUNDS = 20
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)  // 流式 SSE 每个 chunk 的读取超时
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val messages = JSONArray()
    private val isRequesting = AtomicBoolean(false)

    // 当前 LLM 引擎配置
    var baseUrl: String = AiConfig.LLM_BASE_URL
    var apiKey: String = AiConfig.AGNES_AI_API_KEY
    var model: String = AiConfig.LLM_MODEL

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

    /**
     * 切换 LLM 引擎
     */
    fun switchEngine(baseUrl: String, apiKey: String, model: String) {
        this.baseUrl = baseUrl
        this.apiKey = apiKey
        this.model = model
        Log.i(TAG, "LLM 引擎切换: model=$model, baseUrl=$baseUrl")
    }

    fun chat(userMessage: String, callback: Callback) {
        if (!isRequesting.compareAndSet(false, true)) {
            callback.onError("正在请求中，请稍候")
            return
        }
        Log.i(TAG, "[DIAG] LLM.chat: text='${userMessage.take(50)}', url=$baseUrl/chat/completions, model=$model, keyPrefix=${apiKey.take(8)}...")
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
        requestBody.put("model", model)
        requestBody.put("messages", messages)
        requestBody.put("stream", true)
        requestBody.put("max_tokens", 256)
        requestBody.put("temperature", 0.7)

        val body = requestBody.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val fullText = StringBuilder()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e(TAG, "[DIAG] LLM.onFailure: retryCount=$retryCount", e)
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
                    Log.e(TAG, "[DIAG] LLM.onResponse: HTTP ${response.code}, retryCount=$retryCount")
                    synchronized(messages) {
                        removeLastMessage()
                    }
                    val errorBody = try { response.body?.string() } catch (_: Exception) { "" }
                    Log.e(TAG, "[DIAG] LLM errorBody: $errorBody")
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
                    var doneReceived = false

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
                                doneReceived = true
                                trimHistory()
                                callback.onComplete(fullText.toString())
                                break
                            }
                            try {
                                val json = JSONObject(data)
                                val choices = json.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    // optString 对 JSON null 返回字符串 "null"，必须用 isNull 检查
                                    val content = if (delta != null && !delta.isNull("content")) {
                                        delta.getString("content")
                                    } else {
                                        ""
                                    }
                                    // [Bug fix] 过滤字符串 "null"（LLM API 有时返回 JSON null 被转为字符串 "null"）
                                    // 与 CallActivity.invokeLlm 的 onToken 过滤保持一致
                                    // 避免 fullText 与 CallActivity 的 fullResponse 长度不一致
                                    // 导致 onComplete 中 remaining 截取位置错误（可能朗读到 "thinking" 等异常文本）
                                    if (content.isNotEmpty() && !content.equals("null", ignoreCase = true)) {
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

                    // 处理流意外结束的情况（未收到 [DONE]）
                    if (!doneReceived) {
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

    /**
     * 裁剪对话历史，保留 system 消息 + 最近 MAX_CONVERSATION_ROUNDS 轮对话
     * 防止消息数组无限增长导致 API 请求过大或内存溢出
     */
    private fun trimHistory() {
        synchronized(messages) {
            // messages[0] 是 system 消息，之后每2条为一轮（user + assistant）
            val maxMessages = 1 + MAX_CONVERSATION_ROUNDS * 2
            if (messages.length() > maxMessages) {
                // 删除最早的对话轮次（保留 system 消息）
                val removeCount = messages.length() - maxMessages
                // 确保删除偶数条（完整的轮次），从 index 1 开始
                val adjustedRemoveCount = removeCount / 2 * 2
                for (i in 1..adjustedRemoveCount) {
                    messages.remove(1) // 总是删除 index 1（system 消息之后最早的）
                }
                Log.i(TAG, "对话历史已裁剪: 删除 ${adjustedRemoveCount / 2} 轮, 剩余 ${messages.length()} 条消息")
            }
        }
    }

    fun isRequesting(): Boolean = isRequesting.get()
}
