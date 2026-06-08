package ai.guiji.duix.test.service

/**
 * 统一API配置管理
 * 所有第三方API key和URL都集中在这里管理
 */
object AiConfig {
    // 百炼平台 DashScope API Key (用户提供 - 国内版)
    const val DASHSCOPE_API_KEY = "sk-71bd4c89525c4e0db2e713f5b87c1da1"

    // ASR - fun-asr-realtime (DashScope 国内版)
    // URL 使用国内版 dashscope.aliyuncs.com（用户提供的key是国内版的，国际版会401）
    const val ASR_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"
    const val ASR_MODEL = "fun-asr-realtime"

    // TTS - qwen3-tts-flash-realtime (DashScope 实时TTS)
    // 实时TTS使用专用路径 /realtime?model=xxx，协议是 session.update + input_text_buffer.*
    // 注意：model 通过 URL 查询参数传递，不再放在 payload 里
    const val TTS_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-tts-flash-realtime"
    const val TTS_MODEL = "qwen3-tts-flash-realtime"
    const val TTS_DEFAULT_VOICE = "Ethan"  // 中文男声 - 晨煦：阳光温暖活力朝气

    // TTS - MiMo (小米 mimo-v2.5-tts)
    // OpenAI 兼容的 Chat Completions 格式，REST API 非流式
    const val MIMO_API_KEY = "tp-cujq11e705x6k837werg4kyhnhen7fhy2ilo2yl7d91v01og"
    const val MIMO_TTS_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1/chat/completions"
    const val MIMO_TTS_MODEL = "mimo-v2.5-tts"
    const val MIMO_TTS_DEFAULT_VOICE = "白桦"  // 中文男声 - 成熟大叔

    // LLM - Agnes AI (agnes-2.0-flash)
    // 独立 API key，因为百炼 key 不能调普通 LLM 模型（Model.AccessDenied）
    const val AGNES_AI_API_KEY = "sk-TuKWa0JQb9nGiUc7d6goWxpRzhUGfRpALI1DASAf1qOIXNCs"
    const val LLM_BASE_URL = "https://apihub.agnes-ai.com/v1"
    const val LLM_MODEL = "agnes-2.0-flash"

    // LLM - MiMo (小米 mimo-v2.5)
    const val MIMO_LLM_BASE_URL = "https://token-plan-cn.xiaomimimo.com/v1"
    const val MIMO_LLM_MODEL = "mimo-v2.5"

    const val LLM_SYSTEM_PROMPT = "你是一个友好的数字人助手，名叫小杜。请用简洁、自然的口语风格回答问题，每次回答不超过100字。不要使用markdown格式，用纯文本回答。"

    // 数字人模型下载URL
    const val MODEL_BASE_CONFIG_URL = "http://114.215.183.45/downloads/duix/models/gj_dh_res.zip"
    const val MODEL_XIAOBEN_URL = "http://114.215.183.45/downloads/duix/models/bendi3_20240518.zip"
    const val MODEL_AIRUIKE_URL = "http://114.215.183.45/downloads/duix/models/airuike_20240409.zip"
    const val MODEL_NAME_XIAOBEN = "bendi3_20240518"
    const val MODEL_NAME_AIRUIKE = "airuike_20240409"

    // 下载线程配置
    const val DOWNLOAD_THREADS = 4               // 多线程分片下载数
    const val DOWNLOAD_CHUNK_SIZE = 1024 * 1024  // 1MB per chunk
    const val DOWNLOAD_RETRY_COUNT = 5           // 失败重试次数
    const val DOWNLOAD_TIMEOUT_SEC = 60          // 单个分片超时
}
