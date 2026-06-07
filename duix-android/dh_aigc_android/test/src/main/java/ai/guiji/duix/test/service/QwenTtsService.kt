package ai.guiji.duix.test.service

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阿里云百炼平台 TTS 服务 - 使用 qwen3-tts-flash-realtime 实时语音合成
 * 通过WebSocket实现流式TTS，无需轮询
 */
class QwenTtsService {

    companion object {
        private const val TAG = "QwenTtsService"
        private const val SAMPLE_RATE = 24000  // qwen3-tts-flash-realtime 默认24kHz
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val SYNTHESIS_TIMEOUT_MS = 30000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isSynthesizing = AtomicBoolean(false)

    // 收集 PCM 音频数据
    private val audioChunks = mutableListOf<ByteArray>()
    private var taskId: String = ""

    // 超时处理
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    interface Callback {
        fun onAudioData(pcmData: ByteArray)  // PCM 24kHz mono 16bit
        fun onComplete()
        fun onError(error: String)
    }

    /**
     * 合成语音（流式TTS）
     */
    fun synthesize(text: String, voice: String = AiConfig.TTS_DEFAULT_VOICE, callback: Callback) {
        synthesizeWithRetry(text, voice, callback, 0)
    }

    private fun synthesizeWithRetry(text: String, voice: String, callback: Callback, retryCount: Int) {
        if (!isSynthesizing.compareAndSet(false, true)) {
            callback.onError("正在合成中，请稍候")
            return
        }

        synchronized(audioChunks) {
            audioChunks.clear()
        }
        taskId = UUID.randomUUID().toString().replace("-", "").take(32)

        val request = Request.Builder()
            .url(AiConfig.TTS_WS_URL)
            .addHeader("Authorization", "bearer ${AiConfig.DASHSCOPE_API_KEY}")
            .build()

        // 设置超时
        timeoutRunnable = Runnable {
            if (isSynthesizing.get()) {
                Log.w(TAG, "Synthesis timeout, retryCount=$retryCount")
                try { webSocket?.close(1000, "Timeout") } catch (e: Exception) { }
                isSynthesizing.set(false)
                if (retryCount < MAX_RETRIES) {
                    Log.i(TAG, "Retrying synthesis (attempt ${retryCount + 1}/$MAX_RETRIES)")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        synthesizeWithRetry(text, voice, callback, retryCount + 1)
                    }, RETRY_DELAY_MS)
                } else {
                    callback.onError("语音合成超时，请重试")
                }
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable!!, SYNTHESIS_TIMEOUT_MS)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Qwen TTS WebSocket connected")

                // 1. 发送 run-task 指令
                val runTask = JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("action", "run-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    })
                    put("payload", JSONObject().apply {
                        put("task_group", "audio")
                        put("task", "tts")
                        put("function", "SpeechSynthesizer")
                        put("model", AiConfig.TTS_MODEL)
                        put("parameters", JSONObject().apply {
                            put("text_type", "PlainText")
                            put("voice", voice)
                            put("format", "pcm")
                            put("sample_rate", SAMPLE_RATE)
                            put("volume", 50)
                            put("rate", 1.0)
                            put("pitch", 1.0)
                        })
                        put("input", JSONObject())
                    })
                }
                webSocket.send(runTask.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    val header = message.getJSONObject("header")
                    val event = header.optString("event", "")

                    when (event) {
                        "task-started" -> {
                            Log.i(TAG, "TTS task started")
                            // 发送要合成的文本
                            val finishTask = JSONObject().apply {
                                put("header", JSONObject().apply {
                                    put("action", "continue-task")
                                    put("task_id", taskId)
                                    put("streaming", "duplex")
                                })
                                put("payload", JSONObject().apply {
                                    put("input", JSONObject().apply {
                                        put("text", text)
                                    })
                                })
                            }
                            // 注意：这里需要先发送文本，再发送finish-task
                            // 因为onMessage是处理响应消息的回调，需要重新组织流程
                        }
                        "result-generated" -> {
                            // 收到音频数据
                            val payload = message.optJSONObject("payload")
                            val output = payload?.optJSONObject("output")
                            val audioDataStr = output?.optString("audio", "")
                            if (!audioDataStr.isNullOrEmpty()) {
                                try {
                                    val audioBytes = android.util.Base64.decode(audioDataStr, android.util.Base64.DEFAULT)
                                    if (audioBytes.isNotEmpty()) {
                                        synchronized(audioChunks) {
                                            audioChunks.add(audioBytes)
                                        }
                                        // 实时推送音频数据
                                        callback.onAudioData(audioBytes)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Base64 decode error", e)
                                }
                            }
                        }
                        "task-finished" -> {
                            Log.i(TAG, "TTS task finished")
                            isSynthesizing.set(false)
                            cancelTimeout()
                            callback.onComplete()
                            webSocket.close(1000, "Done")
                        }
                        "task-failed" -> {
                            val errorMsg = header.optString("error_message", "Unknown error")
                            val errorCode = header.optString("error_code", "")
                            Log.e(TAG, "TTS task failed: $errorCode - $errorMsg")
                            isSynthesizing.set(false)
                            cancelTimeout()
                            // 重试
                            if (retryCount < MAX_RETRIES) {
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    synthesizeWithRetry(text, voice, callback, retryCount + 1)
                                }, RETRY_DELAY_MS)
                            } else {
                                callback.onError("TTS失败: $errorMsg")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse TTS message error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Qwen TTS WebSocket failure (attempt ${retryCount + 1}/$MAX_RETRIES)", t)
                isSynthesizing.set(false)
                cancelTimeout()

                if (retryCount < MAX_RETRIES) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        synthesizeWithRetry(text, voice, callback, retryCount + 1)
                    }, RETRY_DELAY_MS)
                } else {
                    val errorMsg = when {
                        t.message?.contains("timeout", ignoreCase = true) == true -> "连接超时，请检查网络"
                        t.message?.contains("connect", ignoreCase = true) == true -> "无法连接服务器"
                        t.message?.contains("dns", ignoreCase = true) == true -> "DNS解析失败"
                        else -> "语音合成失败: ${t.message ?: "未知错误"}"
                    }
                    callback.onError(errorMsg)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Qwen TTS WebSocket closed: $code $reason")
                isSynthesizing.set(false)
                cancelTimeout()
            }
        })

        // 在连接打开后立即发送文本（这里需要异步处理）
        // 由于WebSocket的onOpen是异步的，我们用一个Runnable来延迟发送
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isSynthesizing.get()) {
                sendTextToTts(text)
            }
        }, 500)  // 等待连接建立
    }

    private fun sendTextToTts(text: String) {
        val sendText = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "continue-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("input", JSONObject().apply {
                    put("text", text)
                })
            })
        }
        webSocket?.send(sendText.toString())

        // 发送 finish-task
        val finishTask = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("input", JSONObject())
            })
        }
        webSocket?.send(finishTask.toString())
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun stop() {
        isSynthesizing.set(false)
        cancelTimeout()
        try {
            webSocket?.close(1000, "Client stopping")
        } catch (e: Exception) {
            Log.e(TAG, "关闭WebSocket异常", e)
        }
        webSocket = null
    }

    fun isSynthesizing(): Boolean = isSynthesizing.get()
}
