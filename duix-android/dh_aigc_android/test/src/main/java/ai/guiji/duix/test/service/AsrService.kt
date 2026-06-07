package ai.guiji.duix.test.service

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ASR服务 - 使用阿里云百炼平台的 fun-asr-realtime 模型
 * 通过WebSocket实现实时语音识别
 */
class AsrService {

    companion object {
        private const val TAG = "AsrService"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var taskId: String = ""
    private var isRunning = false

    interface Callback {
        fun onReady()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
        fun onClosed()
    }

    fun start(callback: Callback) {
        if (isRunning) return

        taskId = UUID.randomUUID().toString().replace("-", "").take(32)

        val request = Request.Builder()
            .url(AiConfig.ASR_WS_URL)
            .addHeader("Authorization", "bearer ${AiConfig.DASHSCOPE_API_KEY}")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                // 发送run-task指令
                val runTask = JSONObject().apply {
                    put("header", JSONObject().apply {
                        put("action", "run-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    })
                    put("payload", JSONObject().apply {
                        put("task_group", "audio")
                        put("task", "asr")
                        put("function", "recognition")
                        put("model", AiConfig.ASR_MODEL)
                        put("parameters", JSONObject().apply {
                            put("format", "pcm")
                            put("sample_rate", 16000)
                            put("language_hints", JSONArray().put("zh"))
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
                            isRunning = true
                            callback.onReady()
                        }
                        "result-generated" -> {
                            val sentence = message.getJSONObject("payload")
                                .getJSONObject("output").getJSONObject("sentence")
                            val resultText = sentence.optString("text", "")
                            val isFinal = sentence.optBoolean("end_time", false) ||
                                sentence.optString("status", "") == "completed"
                            if (isFinal) {
                                callback.onFinalResult(resultText)
                            } else {
                                callback.onPartialResult(resultText)
                            }
                        }
                        "task-finished" -> {
                            isRunning = false
                            callback.onClosed()
                        }
                        "task-failed" -> {
                            val errorMsg = header.optString("error_message", "Unknown error")
                            isRunning = false
                            callback.onError(errorMsg)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse message error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                isRunning = false
                callback.onError(t.message ?: "WebSocket connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                isRunning = false
                callback.onClosed()
            }
        })
    }

    fun sendAudio(data: ByteArray) {
        if (isRunning) {
            webSocket?.send(ByteString.of(*data))
        }
    }

    fun stop() {
        if (isRunning) {
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
            isRunning = false
        }
    }

    fun close() {
        stop()
        webSocket?.close(1000, "Client closing")
        webSocket = null
    }
}
