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
    // [DIAG] 诊断用：累计发送字节数和包数
    private var totalBytesSent = 0L
    private var totalPacketsSent = 0
    private var lastDiagTimeMs = 0L

    // [Phase 3.1] 自动重连（默认关闭，等根因明确后由 HybridAsrService 启用）
    var enableAutoReconnect: Boolean = false
    private var reconnectCount = 0
    private val maxReconnectAttempts = 5
    private val reconnectDelaysMs = longArrayOf(1000, 2000, 4000, 8000, 16000)  // 指数退避
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var savedCallback: Callback? = null
    private val reconnectRunnable = Runnable {
        Log.i(TAG, "[DIAG] 自动重连触发: 第 $reconnectCount 次")
        val cb = savedCallback
        if (cb != null) {
            isRunning = false
            webSocket = null
            start(cb)
        }
    }

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
        // [DIAG] 启动诊断
        val keyPreview = AiConfig.DASHSCOPE_API_KEY.take(8) + "..."
        Log.i(TAG, "[DIAG] ASR.start: url=${AiConfig.ASR_WS_URL}, model=${AiConfig.ASR_MODEL}, taskId=$taskId, keyPrefix=$keyPreview, reconnectCount=$reconnectCount")
        // [Phase 3.1] 保存 callback 用于重连
        savedCallback = callback
        totalBytesSent = 0
        totalPacketsSent = 0
        lastDiagTimeMs = System.currentTimeMillis()

        val request = Request.Builder()
            .url(AiConfig.ASR_WS_URL)
            .addHeader("Authorization", "bearer ${AiConfig.DASHSCOPE_API_KEY}")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // [DIAG] WebSocket 连接成功
                Log.i(TAG, "[DIAG] WebSocket onOpen: HTTP ${response.code}, protocol=${response.protocol}")
                // [Phase 3.1] 重连成功，重置计数
                if (reconnectCount > 0) {
                    Log.i(TAG, "[DIAG] 自动重连成功！累计 $reconnectCount 次重连")
                    reconnectCount = 0
                }
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
                Log.i(TAG, "[DIAG] 发送run-task指令: ${runTask.toString().take(200)}")
                webSocket.send(runTask.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    val header = message.getJSONObject("header")
                    val event = header.optString("event", "")
                    // [DIAG] 收到消息
                    Log.d(TAG, "[DIAG] onMessage: event=$event, len=${text.length}")

                    when (event) {
                        "task-started" -> {
                            isRunning = true
                            Log.i(TAG, "[DIAG] task-started: ASR服务就绪，可以开始发送音频")
                            callback.onReady()
                        }
                        "result-generated" -> {
                            val sentence = message.getJSONObject("payload")
                                .getJSONObject("output").getJSONObject("sentence")
                            val resultText = sentence.optString("text", "")
                            val isFinal = sentence.optBoolean("end_time", false) ||
                                sentence.optString("status", "") == "completed"
                            // [DIAG] 识别结果
                            Log.d(TAG, "[DIAG] result-generated: textLen=${resultText.length}, isFinal=$isFinal, text='${resultText.take(50)}'")
                            if (isFinal) {
                                callback.onFinalResult(resultText)
                            } else {
                                callback.onPartialResult(resultText)
                            }
                        }
                        "task-finished" -> {
                            isRunning = false
                            Log.i(TAG, "[DIAG] task-finished: 服务端结束, totalBytesSent=$totalBytesSent, totalPacketsSent=$totalPacketsSent")
                            callback.onClosed()
                        }
                        "task-failed" -> {
                            val errorCode = header.optString("error_code", "")
                            val errorMsg = header.optString("error_message", "Unknown error")
                            isRunning = false
                            // [DIAG] 任务失败（鉴权/限流/参数错误等）
                            Log.e(TAG, "[DIAG] task-failed: code=$errorCode, msg=$errorMsg")
                            callback.onError("$errorCode: $errorMsg")
                        }
                        else -> {
                            Log.d(TAG, "[DIAG] 未知event: $event")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[DIAG] Parse message error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // [DIAG] WebSocket 连接失败
                Log.e(TAG, "[DIAG] WebSocket onFailure: totalBytesSent=$totalBytesSent, totalPacketsSent=$totalPacketsSent", t)
                isRunning = false
                val errorMsg = buildString {
                    if (response != null) {
                        append("HTTP ${response.code}")
                        val errBody: String? = try { response.body?.string() } catch (_: Exception) { null }
                        if (!errBody.isNullOrEmpty()) append(": $errBody")
                    } else {
                        append(t.message ?: t.javaClass.simpleName)
                    }
                }
                Log.e(TAG, "[DIAG] WebSocket onFailure 错误信息: $errorMsg")
                // [Phase 3.1] 自动重连调度
                if (enableAutoReconnect && reconnectCount < maxReconnectAttempts) {
                    val delay = reconnectDelaysMs[reconnectCount.coerceAtMost(reconnectDelaysMs.size - 1)]
                    Log.w(TAG, "[DIAG] 安排第 ${reconnectCount + 1} 次重连，${delay}ms 后")
                    reconnectCount++
                    reconnectHandler.removeCallbacks(reconnectRunnable)
                    reconnectHandler.postDelayed(reconnectRunnable, delay)
                } else if (reconnectCount >= maxReconnectAttempts) {
                    Log.e(TAG, "[DIAG] 已达最大重连次数 ($maxReconnectAttempts)，放弃重连")
                }
                callback.onError(errorMsg)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // [DIAG] WebSocket 关闭
                Log.i(TAG, "[DIAG] WebSocket onClosed: code=$code, reason='$reason', totalBytesSent=$totalBytesSent, totalPacketsSent=$totalPacketsSent")
                isRunning = false
                callback.onClosed()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "[DIAG] WebSocket onClosing: code=$code, reason='$reason'")
            }
        })
    }

    fun sendAudio(data: ByteArray) {
        if (isRunning) {
            webSocket?.send(ByteString.of(*data))
            totalBytesSent += data.size
            totalPacketsSent++
            // [DIAG] 每 5 秒打印一次发送统计
            val now = System.currentTimeMillis()
            if (now - lastDiagTimeMs >= 5000) {
                Log.i(TAG, "[DIAG] sendAudio统计: 累计发送 $totalBytesSent 字节 / $totalPacketsSent 包 (avg=${if (totalPacketsSent > 0) totalBytesSent / totalPacketsSent else 0} bytes/包)")
                lastDiagTimeMs = now
            }
        } else {
            // [DIAG] isRunning=false 丢弃音频数据（重要！录音还在跑但 isRunning 已经停止）
            if (totalPacketsSent == 0) {
                // 只在第一次打印，避免刷屏
                Log.w(TAG, "[DIAG] sendAudio 丢弃: isRunning=false（ASR 未就绪或已关闭）")
            }
        }
    }

    fun stop() {
        if (isRunning) {
            // [DIAG] 主动停止
            Log.i(TAG, "[DIAG] ASR.stop: 发送finish-task, totalBytesSent=$totalBytesSent")
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
        } else {
            // [DIAG] stop 时 isRunning 已为 false
            Log.w(TAG, "[DIAG] ASR.stop: isRunning=false，无需发送finish-task")
        }
    }

    fun close() {
        // [DIAG] 完全关闭
        Log.i(TAG, "[DIAG] ASR.close: isRunning=$isRunning")
        // [Phase 3.1] 关闭时取消重连调度
        reconnectHandler.removeCallbacks(reconnectRunnable)
        savedCallback = null
        reconnectCount = 0
        stop()
        webSocket?.close(1000, "Client closing")
        webSocket = null
    }
}
