package ai.guiji.duix.test.service

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阿里云百炼平台 TTS 服务 - 使用 qwen3-tts-flash-realtime 实时语音合成
 * WebSocket URL: wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-tts-flash-realtime
 *
 * 协议（新版本，区别于 Qwen-Omni 的旧版 run-task 协议）：
 *   客户端事件：
 *     - session.update           设置 voice/format/sample_rate
 *     - input_text_buffer.append 添加待合成文本
 *     - input_text_buffer.commit 触发合成
 *     - input_text_buffer.clear  清空缓冲区
 *     - session.finish           结束会话
 *   服务端事件：
 *     - session.created/updated
 *     - response.audio.delta     音频数据（Base64 编码的 PCM）
 *     - response.audio.done      音频流结束
 *     - response.done            整个响应结束
 *     - session.finished         会话结束
 */
class QwenTtsService {

    companion object {
        private const val TAG = "QwenTtsService"
        private const val SAMPLE_RATE = 24000  // qwen3-tts-flash-realtime 仅支持 24kHz
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 800L
        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 60L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isSynthesizing = AtomicBoolean(false)
    private var shouldStop = false  // 主动停止标志

    interface Callback {
        fun onAudioData(pcmData: ByteArray)  // PCM 24kHz mono 16bit
        fun onComplete()
        fun onError(error: String)
    }

    /**
     * 合成语音（流式TTS）
     * @param text 要合成的文本
     * @param voice 音色，默认 Cherry（中文女声）
     * @param callback 回调
     */
    fun synthesize(text: String, voice: String = AiConfig.TTS_DEFAULT_VOICE, callback: Callback) {
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

        Log.i(TAG, "Qwen TTS 开始 (第${retryCount + 1}次): voice=$voice, text=${text.take(30)}...")

        val request = Request.Builder()
            .url(AiConfig.TTS_WS_URL)
            .addHeader("Authorization", "Bearer ${AiConfig.DASHSCOPE_API_KEY}")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "TTS WebSocket 已连接")
                // 1. 发送 session.update 设置音色和参数
                val sessionUpdate = JSONObject().apply {
                    put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").take(16))
                    put("type", "session.update")
                    put("session", JSONObject().apply {
                        put("voice", voice)
                        put("mode", "server_commit")  // 服务端自动判断合成时机
                        put("language_type", "Chinese")
                        put("response_format", "pcm")
                        put("sample_rate", SAMPLE_RATE)
                    })
                }
                webSocket.send(sessionUpdate.toString())
                Log.i(TAG, "已发送 session.update")

                // 2. 立即追加文本
                val appendText = JSONObject().apply {
                    put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").take(16))
                    put("type", "input_text_buffer.append")
                    put("text", text)
                }
                webSocket.send(appendText.toString())
                Log.i(TAG, "已发送 input_text_buffer.append")

                // 3. 提交触发合成
                val commitText = JSONObject().apply {
                    put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").take(16))
                    put("type", "input_text_buffer.commit")
                }
                webSocket.send(commitText.toString())
                Log.i(TAG, "已发送 input_text_buffer.commit")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    val eventType = message.optString("type", "")

                    when (eventType) {
                        "session.created", "session.updated" -> {
                            Log.i(TAG, "TTS session: $eventType")
                        }
                        "response.created" -> {
                            Log.i(TAG, "TTS response 创建")
                        }
                        "response.audio.delta" -> {
                            // Base64 编码的 PCM 音频数据
                            val audioB64 = message.optString("delta", "")
                            if (audioB64.isNotEmpty() && !shouldStop) {
                                try {
                                    val audioBytes = android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT)
                                    if (audioBytes.isNotEmpty()) {
                                        callback.onAudioData(audioBytes)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Base64 decode error", e)
                                }
                            }
                        }
                        "response.audio.done" -> {
                            Log.i(TAG, "TTS 音频流完成")
                        }
                        "response.done" -> {
                            Log.i(TAG, "TTS 响应完成")
                            if (!shouldStop) {
                                isSynthesizing.set(false)
                                callback.onComplete()
                            }
                        }
                        "error" -> {
                            val errObj = message.optJSONObject("error")
                            val errMsg = errObj?.optString("message", "Unknown") ?: "Unknown"
                            val errCode = errObj?.optString("code", "") ?: ""
                            Log.e(TAG, "TTS 错误: $errCode - $errMsg")
                            handleError("$errCode: $errMsg", callback, retryCount, text, voice)
                        }
                        "session.finished" -> {
                            Log.i(TAG, "TTS session 结束")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse TTS message error", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 实时TTS协议下一般不会收到二进制消息，备用
                val data = bytes.toByteArray()
                if (data.isNotEmpty() && !shouldStop) {
                    callback.onAudioData(data)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "TTS WebSocket 失败 (attempt ${retryCount + 1}): ${t.message}", t)
                handleError("WebSocket失败: ${t.message ?: "未知"}", callback, retryCount, text, voice)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "TTS WebSocket 已关闭: $code $reason")
                isSynthesizing.set(false)
                if (code != 1000 && code != 1005 && !shouldStop) {
                    // 非正常关闭且未主动停止，可能需要重试
                    Log.w(TAG, "TTS 非正常关闭 code=$code")
                }
            }
        }

        webSocket = client.newWebSocket(request, listener)
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
            Log.w(TAG, "TTS 失败，${RETRY_DELAY_MS}ms 后重试 (${retryCount + 1}/$MAX_RETRIES)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                synthesizeInternal(text, voice, callback, retryCount + 1)
            }, RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "TTS 重试${MAX_RETRIES}次后仍失败: $error")
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
            // 发送 session.finish 让服务端清理
            webSocket?.send(JSONObject().apply {
                put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").take(16))
                put("type", "session.finish")
            }.toString())
        } catch (e: Exception) {
            Log.w(TAG, "发送 session.finish 异常: ${e.message}")
        }
        try {
            webSocket?.close(1000, "Client stop")
        } catch (e: Exception) {
            Log.w(TAG, "关闭 WebSocket 异常: ${e.message}")
        }
        webSocket = null
    }

    fun isSynthesizing(): Boolean = isSynthesizing.get()
}
