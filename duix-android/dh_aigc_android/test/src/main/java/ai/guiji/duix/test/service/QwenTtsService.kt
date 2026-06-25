package ai.guiji.duix.test.service

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        // WebSocket 无数据超时：如果连接建立后 15 秒内没有收到任何音频数据，认为连接异常
        private const val WS_NO_DATA_TIMEOUT_MS = 15000L
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

    // 会话 ID：每次 synthesize 递增，用于区分旧 WebSocket 回调和新请求
    private val sessionId = AtomicInteger(0)

    // 无数据超时检测
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var noDataTimeoutRunnable: Runnable? = null

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

    /**
     * [Bug fix] 流式合成语音（不使用 isSynthesizing 互斥锁）
     * 用于 LLM 流式输出的多句合成场景，每句独立 WebSocket 连接，互不干扰
     * @param text 要合成的文本
     * @param voice 音色
     * @param callback 回调
     */
    fun synthesizeStreaming(text: String, voice: String = AiConfig.TTS_DEFAULT_VOICE, callback: Callback) {
        if (text.isBlank()) {
            callback.onError("文本为空")
            return
        }
        // 流式模式不检查 isSynthesizing，每句独立连接
        synthesizeInternalStreaming(text, voice, callback, 0)
    }

    private fun synthesizeInternal(text: String, voice: String, callback: Callback, retryCount: Int) {
        if (isSynthesizing.getAndSet(true)) {
            callback.onError("正在合成中，请稍候")
            return
        }
        shouldStop = false

        // 递增会话 ID，旧 WebSocket 的回调会因 ID 不匹配而被忽略
        val currentSessionId = sessionId.incrementAndGet()

        Log.i(TAG, "Qwen TTS 开始 (第${retryCount + 1}次, session=$currentSessionId): voice=$voice, text=${text.take(30)}...")

        val request = Request.Builder()
            .url(AiConfig.TTS_WS_URL)
            .addHeader("Authorization", "Bearer ${AiConfig.DASHSCOPE_API_KEY}")
            .build()

        // 是否已收到音频数据（用于无数据超时检测）
        var receivedAudioData = false

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 检查是否是当前会话
                if (currentSessionId != sessionId.get()) {
                    Log.w(TAG, "忽略旧会话 onOpen (session=$currentSessionId, current=${sessionId.get()})")
                    return
                }
                Log.i(TAG, "TTS WebSocket 已连接 (session=$currentSessionId)")

                // 启动无数据超时检测
                scheduleNoDataTimeout(currentSessionId, callback, retryCount, text, voice)

                // 1. 发送 session.update 设置音色和参数
                val sessionUpdate = JSONObject().apply {
                    put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").take(16))
                    put("type", "session.update")
                    put("session", JSONObject().apply {
                        put("voice", voice)
                        put("mode", "server_commit")  // 服务端自动判断合成时机
                        put("language_type", "auto")  // 自动检测语言，支持中英文混合
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
                // 检查是否是当前会话
                if (currentSessionId != sessionId.get()) {
                    Log.d(TAG, "忽略旧会话消息 (session=$currentSessionId)")
                    return
                }
                if (shouldStop) return

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
                            // 收到音频数据，取消无数据超时
                            if (!receivedAudioData) {
                                receivedAudioData = true
                                cancelNoDataTimeout()
                            }
                            // Base64 编码的 PCM 音频数据
                            val audioB64 = message.optString("delta", "")
                            if (audioB64.isNotEmpty()) {
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
                            Log.i(TAG, "TTS 响应完成 (session=$currentSessionId)")
                            cancelNoDataTimeout()
                            isSynthesizing.set(false)
                            callback.onComplete()
                        }
                        "error" -> {
                            val errObj = message.optJSONObject("error")
                            val errMsg = errObj?.optString("message", "Unknown") ?: "Unknown"
                            val errCode = errObj?.optString("code", "") ?: ""
                            Log.e(TAG, "TTS 错误: $errCode - $errMsg (session=$currentSessionId)")
                            cancelNoDataTimeout()
                            handleError("$errCode: $errMsg", callback, retryCount, text, voice, currentSessionId)
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
                if (currentSessionId != sessionId.get() || shouldStop) return
                // 实时TTS协议下一般不会收到二进制消息，备用
                val data = bytes.toByteArray()
                if (data.isNotEmpty()) {
                    if (!receivedAudioData) {
                        receivedAudioData = true
                        cancelNoDataTimeout()
                    }
                    callback.onAudioData(data)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 检查是否是当前会话
                if (currentSessionId != sessionId.get()) {
                    Log.d(TAG, "忽略旧会话 onFailure (session=$currentSessionId)")
                    return
                }
                Log.e(TAG, "TTS WebSocket 失败 (attempt ${retryCount + 1}, session=$currentSessionId): ${t.message}", t)
                cancelNoDataTimeout()
                handleError("WebSocket失败: ${t.message ?: "未知"}", callback, retryCount, text, voice, currentSessionId)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "TTS WebSocket 已关闭: $code $reason (session=$currentSessionId, current=${sessionId.get()})")
                // 修复竞态条件：只在当前会话 ID 匹配时才重置 isSynthesizing
                // 避免旧 WebSocket 的 onClosed 干扰新请求
                if (currentSessionId == sessionId.get()) {
                    isSynthesizing.set(false)
                    if (code != 1000 && code != 1005 && !shouldStop) {
                        // 非正常关闭且未主动停止，可能需要重试
                        Log.w(TAG, "TTS 非正常关闭 code=$code, session=$currentSessionId")
                    }
                } else {
                    Log.d(TAG, "忽略旧会话 onClosed (session=$currentSessionId, current=${sessionId.get()})")
                }
            }
        }

        webSocket = client.newWebSocket(request, listener)
    }

    /**
     * [Bug fix] 流式合成内部实现（不使用 isSynthesizing 互斥锁）
     * 每句独立 WebSocket 连接，互不干扰
     * 不修改全局 webSocket/isSynthesizing 状态，避免影响其他句子
     */
    private fun synthesizeInternalStreaming(text: String, voice: String, callback: Callback, retryCount: Int) {
        // 流式模式不设置 isSynthesizing，不修改全局 webSocket
        // 每句使用独立的局部变量
        val currentSessionId = sessionId.incrementAndGet()
        var localWebSocket: WebSocket? = null
        var localReceivedAudio = false
        var localShouldStop = false
        var localNoDataTimeout: Runnable? = null

        Log.i(TAG, "Qwen TTS 流式合成 (第${retryCount + 1}次, session=$currentSessionId): voice=$voice, text=${text.take(30)}...")

        val request = Request.Builder()
            .url(AiConfig.TTS_WS_URL)
            .addHeader("Authorization", "Bearer ${AiConfig.DASHSCOPE_API_KEY}")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (currentSessionId != sessionId.get()) {
                    Log.w(TAG, "[流式] 忽略旧会话 onOpen (session=$currentSessionId)")
                    return
                }
                Log.i(TAG, "[流式] TTS WebSocket 已连接 (session=$currentSessionId)")

                // 无数据超时检测
                localNoDataTimeout = Runnable {
                    if (currentSessionId != sessionId.get() || localShouldStop) return@Runnable
                    Log.w(TAG, "[流式] Qwen TTS 无数据超时, session=$currentSessionId")
                    try { localWebSocket?.close(1000, "No data timeout") } catch (_: Exception) {}
                    handleStreamingError("连接超时：${WS_NO_DATA_TIMEOUT_MS}ms 内未收到音频数据", callback, retryCount, text, voice, currentSessionId)
                }
                timeoutHandler.postDelayed(localNoDataTimeout!!, WS_NO_DATA_TIMEOUT_MS)

                // 1. session.update
                val sessionUpdate = JSONObject().apply {
                    put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").take(16))
                    put("type", "session.update")
                    put("session", JSONObject().apply {
                        put("voice", voice)
                        put("mode", "server_commit")
                        put("language_type", "auto")
                        put("response_format", "pcm")
                        put("sample_rate", SAMPLE_RATE)
                    })
                }
                webSocket.send(sessionUpdate.toString())

                // 2. input_text_buffer.append
                val appendText = JSONObject().apply {
                    put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").take(16))
                    put("type", "input_text_buffer.append")
                    put("text", text)
                }
                webSocket.send(appendText.toString())

                // 3. input_text_buffer.commit
                val commitText = JSONObject().apply {
                    put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").take(16))
                    put("type", "input_text_buffer.commit")
                }
                webSocket.send(commitText.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (currentSessionId != sessionId.get() || localShouldStop) return
                try {
                    val message = JSONObject(text)
                    val eventType = message.optString("type", "")
                    when (eventType) {
                        "response.audio.delta" -> {
                            if (!localReceivedAudio) {
                                localReceivedAudio = true
                                localNoDataTimeout?.let { timeoutHandler.removeCallbacks(it) }
                            }
                            val audioB64 = message.optString("delta", "")
                            if (audioB64.isNotEmpty()) {
                                try {
                                    val audioBytes = android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT)
                                    if (audioBytes.isNotEmpty()) {
                                        callback.onAudioData(audioBytes)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "[流式] Base64 解码失败", e)
                                }
                            }
                        }
                        "response.done", "response.audio.done" -> {
                            Log.i(TAG, "[流式] TTS 响应完成 (session=$currentSessionId)")
                        }
                        "session.finished" -> {
                            Log.i(TAG, "[流式] TTS session 结束 (session=$currentSessionId)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[流式] Parse TTS message error", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (currentSessionId != sessionId.get() || localShouldStop) return
                val data = bytes.toByteArray()
                if (data.isNotEmpty()) {
                    if (!localReceivedAudio) {
                        localReceivedAudio = true
                        localNoDataTimeout?.let { timeoutHandler.removeCallbacks(it) }
                    }
                    callback.onAudioData(data)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (currentSessionId != sessionId.get()) return
                Log.e(TAG, "[流式] TTS WebSocket 失败 (session=$currentSessionId): ${t.message}", t)
                localNoDataTimeout?.let { timeoutHandler.removeCallbacks(it) }
                handleStreamingError("WebSocket失败: ${t.message ?: "未知"}", callback, retryCount, text, voice, currentSessionId)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "[流式] TTS WebSocket 已关闭: $code $reason (session=$currentSessionId)")
                localNoDataTimeout?.let { timeoutHandler.removeCallbacks(it) }
                // 正常关闭时回调 onComplete（如果没有错误且收到过音频）
                if (currentSessionId == sessionId.get() && !localShouldStop) {
                    if (localReceivedAudio) {
                        callback.onComplete()
                    } else {
                        callback.onError("未收到音频数据")
                    }
                }
            }
        }

        localWebSocket = client.newWebSocket(request, listener)
        // 不赋值给全局 webSocket，避免多句合成时互相覆盖
    }

    /**
     * [Bug fix] 流式合成的错误处理（带重试）
     */
    private fun handleStreamingError(
        error: String,
        callback: Callback,
        retryCount: Int,
        text: String,
        voice: String,
        errorSessionId: Int
    ) {
        if (errorSessionId != sessionId.get()) {
            Log.d(TAG, "[流式] 忽略旧会话错误 (session=$errorSessionId)")
            return
        }
        if (retryCount < MAX_RETRIES) {
            Log.w(TAG, "[流式] TTS 失败，${RETRY_DELAY_MS}ms 后重试 (${retryCount + 1}/$MAX_RETRIES)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (errorSessionId != sessionId.get()) return@postDelayed
                synthesizeInternalStreaming(text, voice, callback, retryCount + 1)
            }, RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "[流式] TTS 重试${MAX_RETRIES}次后仍失败: $error")
            callback.onError(error)
        }
    }

    /**
     * 无数据超时检测：连接建立后如果长时间没有收到音频数据，主动断开并重试
     */
    private fun scheduleNoDataTimeout(sessionId: Int, callback: Callback, retryCount: Int, text: String, voice: String) {
        cancelNoDataTimeout()
        noDataTimeoutRunnable = Runnable {
            if (this.sessionId.get() != sessionId || shouldStop) return@Runnable
            if (isSynthesizing.get()) {
                Log.w(TAG, "Qwen TTS 无数据超时 (${WS_NO_DATA_TIMEOUT_MS}ms), session=$sessionId")
                // 关闭当前 WebSocket
                try {
                    webSocket?.close(1000, "No data timeout")
                } catch (e: Exception) {
                    Log.w(TAG, "关闭超时 WebSocket 异常: ${e.message}")
                }
                handleError("连接超时：${WS_NO_DATA_TIMEOUT_MS}ms 内未收到音频数据", callback, retryCount, text, voice, sessionId)
            }
        }
        timeoutHandler.postDelayed(noDataTimeoutRunnable!!, WS_NO_DATA_TIMEOUT_MS)
    }

    private fun cancelNoDataTimeout() {
        noDataTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        noDataTimeoutRunnable = null
    }

    /**
     * 统一错误处理（带重试）
     */
    private fun handleError(
        error: String,
        callback: Callback,
        retryCount: Int,
        text: String,
        voice: String,
        errorSessionId: Int
    ) {
        // 检查是否是当前会话（避免旧会话的错误回调干扰新请求）
        if (errorSessionId != sessionId.get()) {
            Log.d(TAG, "忽略旧会话错误 (session=$errorSessionId, current=${sessionId.get()})")
            return
        }
        isSynthesizing.set(false)
        if (shouldStop) {
            // 用户主动停止，不重试
            return
        }
        if (retryCount < MAX_RETRIES) {
            Log.w(TAG, "TTS 失败，${RETRY_DELAY_MS}ms 后重试 (${retryCount + 1}/$MAX_RETRIES)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // 重试前再次检查是否已被新请求取代
                if (shouldStop || errorSessionId != this.sessionId.get()) {
                    Log.d(TAG, "重试取消: shouldStop=$shouldStop, session=$errorSessionId vs ${this.sessionId.get()}")
                    return@postDelayed
                }
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
        cancelNoDataTimeout()
        // 递增 sessionId 使旧 WebSocket 的回调失效
        sessionId.incrementAndGet()
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
