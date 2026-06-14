package ai.guiji.duix.test.service

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Edge TTS 服务 - 使用微软 Edge 浏览器的免费 TTS 服务
 * 无需 API Key，音质优秀，支持中文
 */
class EdgeTtsService {

    companion object {
        private const val TAG = "EdgeTtsService"
        private const val WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val REQUEST_ID_PREFIX = "2CBD2"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val SYNTHESIS_TIMEOUT_MS = 30000L

        const val VOICE_XIAOXIAO = "zh-CN-XiaoxiaoNeural"
        const val VOICE_XIAOYI = "zh-CN-XiaoyiNeural"
        const val VOICE_YUNYANG = "zh-CN-YunyangNeural"
        const val VOICE_YUNXI = "zh-CN-YunxiNeural"
        const val VOICE_JENNY = "en-US-JennyNeural"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isSynthesizing = AtomicBoolean(false)
    private var shouldStop = false  // 主动停止标志
    private var currentSessionId = 0L  // 会话 ID，用于区分新旧请求

    // 收集音频数据 - 使用synchronized保护
    private val audioChunks = mutableListOf<ByteArray>()
    private var audioFormat: String? = null

    // 超时处理
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    interface Callback {
        fun onAudioData(data: ByteArray)
        fun onComplete()
        fun onError(error: String)
    }

    /**
     * 合成语音并返回 MP3 音频数据（带重试逻辑）
     */
    fun synthesize(text: String, voice: String = VOICE_XIAOXIAO, callback: Callback) {
        synthesizeWithRetry(text, voice, callback, 0)
    }

    private fun synthesizeWithRetry(text: String, voice: String, callback: Callback, retryCount: Int) {
        if (!isSynthesizing.compareAndSet(false, true)) {
            Log.w(TAG, "synthesize: isSynthesizing=true, 拒绝请求")
            callback.onError("正在合成中，请稍候")
            return
        }
        shouldStop = false
        val sessionId = System.currentTimeMillis()
        currentSessionId = sessionId
        Log.i(TAG, "synthesize: sessionId=$sessionId, voice=$voice, text='${text.take(30)}...'")

        synchronized(audioChunks) {
            audioChunks.clear()
        }
        audioFormat = null

        val requestId = REQUEST_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").uppercase(Locale.getDefault()).take(28)

        val url = "$WSS_URL?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&ConnectionId=$requestId"

        val request = Request.Builder()
            .url(url)
            .build()

        // 设置超时
        timeoutRunnable = Runnable {
            if (isSynthesizing.get() && !shouldStop) {
                Log.w(TAG, "Synthesis timeout, retryCount=$retryCount")
                try {
                    webSocket?.close(1000, "Timeout")
                } catch (e: Exception) {
                    Log.e(TAG, "关闭WebSocket异常", e)
                }
                isSynthesizing.set(false)
                if (retryCount < MAX_RETRIES && !shouldStop) {
                    Log.i(TAG, "Retrying synthesis (attempt ${retryCount + 1}/$MAX_RETRIES)")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!shouldStop) {
                            synthesizeWithRetry(text, voice, callback, retryCount + 1)
                        }
                    }, RETRY_DELAY_MS)
                } else if (!shouldStop) {
                    callback.onError("语音合成超时，请重试")
                }
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable!!, SYNTHESIS_TIMEOUT_MS)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Edge TTS WebSocket connected")

                // 1. 发送配置消息
                val configMessage = "X-Timestamp:${Date()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(configMessage)

                // 2. 发送SSML消息
                // 根据语音名称推断语言代码
                val langCode = if (voice.startsWith("zh-")) "zh-CN" else "en-US"
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$langCode'>" +
                        "<voice name='$voice'>" +
                        "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
                        escapeXml(text) +
                        "</prosody>" +
                        "</voice>" +
                        "</speak>"

                val ssmlMessage = "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nPath:ssml\r\n\r\n$ssml"
                webSocket.send(ssmlMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (currentSessionId != sessionId) {
                    Log.w(TAG, "onMessage: 忽略旧会话消息 (current=$currentSessionId, msg=$sessionId)")
                    return
                }
                if (text.contains("Path:turn.start")) {
                    Log.i(TAG, "Synthesis started")
                } else if (text.contains("Path:turn.end")) {
                    val chunkCount: Int
                    val fullAudio: ByteArray
                    synchronized(audioChunks) {
                        chunkCount = audioChunks.size
                        fullAudio = combineAudioChunks()
                    }
                    Log.i(TAG, "Synthesis completed, total chunks: $chunkCount, audioSize: ${fullAudio.size}")
                    isSynthesizing.set(false)
                    cancelTimeout()
                    if (fullAudio.isNotEmpty()) {
                        callback.onAudioData(fullAudio)
                        callback.onComplete()
                    } else {
                        // 合成完成但无音频数据，视为失败
                        Log.w(TAG, "Edge TTS returned no audio data")
                        callback.onError("No audio data received")
                    }
                    webSocket.close(1000, "Done")
                } else if (text.contains("Path:audio.metadata")) {
                    // 音频元数据，忽略
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size > 2) {
                    val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    if (data.size > 2 + headerLength) {
                        val audioData = data.copyOfRange(2 + headerLength, data.size)
                        if (audioData.isNotEmpty()) {
                            synchronized(audioChunks) {
                                audioChunks.add(audioData)
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Edge TTS WebSocket failure (attempt ${retryCount + 1}/$MAX_RETRIES, sessionId=$sessionId)", t)
                // 忽略旧会话的失败回调
                if (currentSessionId != sessionId) {
                    Log.w(TAG, "onFailure: 忽略旧会话回调")
                    return
                }
                isSynthesizing.set(false)
                cancelTimeout()

                if (shouldStop) return

                if (retryCount < MAX_RETRIES) {
                    Log.i(TAG, "Retrying synthesis (attempt ${retryCount + 1}/$MAX_RETRIES)")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!shouldStop) {
                            synthesizeWithRetry(text, voice, callback, retryCount + 1)
                        }
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
                Log.i(TAG, "Edge TTS WebSocket closed, sessionId=$sessionId")
                // 忽略旧会话的关闭回调
                if (currentSessionId != sessionId) return
                isSynthesizing.set(false)
                cancelTimeout()
            }
        })
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun combineAudioChunks(): ByteArray {
        // 调用方需在synchronized(audioChunks)块内调用
        if (audioChunks.isEmpty()) return ByteArray(0)
        val startIdx = if (audioChunks.size > 1 && audioChunks[0].size < 100) 1 else 0
        var totalSize = 0
        for (i in startIdx until audioChunks.size) {
            totalSize += audioChunks[i].size
        }
        val result = ByteArray(totalSize)
        var offset = 0
        for (i in startIdx until audioChunks.size) {
            System.arraycopy(audioChunks[i], 0, result, offset, audioChunks[i].size)
            offset += audioChunks[i].size
        }
        return result
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun stop() {
        shouldStop = true
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
