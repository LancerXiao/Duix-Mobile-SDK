package ai.guiji.duix.test.service

import android.util.Log

/**
 * ASR Fallback 决策器（Phase 1.3 骨架）
 *
 * 职责：根据 ASR 错误类型，决定下一步操作
 * - 鉴权错（401/403）→ 禁用 ASR，引导用文字输入
 * - 网络错 → fallback 到 Android ASR
 * - Android ASR 不可用 → 提示用文字输入
 * - NoMatch（无语音） → 不需 fallback，自动重新监听
 * - 其他 → 默认文字输入引导
 *
 * 骨架阶段：**不接通** HybridAsrService.onError，避免破坏 Phase 1.1 [DIAG] 诊断
 * 等 Phase 1.1 实测定位根因后，再根据真实错误类型接通对应分支
 *
 * 设计原则：
 * - 纯函数 + 不可变状态：便于单元测试
 * - 单次决策：每次错误调一次 decide()，不维护复杂状态机
 */
class AsrFallbackManager {

    companion object {
        private const val TAG = "AsrFallbackManager"
    }

    /**
     * Fallback 决策结果
     */
    enum class Action {
        /** 不需 fallback，自动重新监听（如 NoMatch） */
        RETRY_AUTO_LISTEN,
        /** 禁用 ASR，引导用文字输入（鉴权错） */
        DISABLE_ASR_USE_TEXT,
        /** 切换到 Android ASR（网络错 + Android 可用） */
        FALLBACK_TO_ANDROID,
        /** Android 不可用，提示用文字输入（小米常见） */
        PROMPT_USE_TEXT,
        /** 其他未知错误，弹错误信息后让用户决定 */
        SHOW_ERROR_TO_USER
    }

    /**
     * 输入：错误信息 + 当前 DashScope 状态 + Android ASR 是否可用
     * 输出：fallback 决策
     */
    data class Context(
        val errorMessage: String,
        val isAndroidAsrAvailable: Boolean,
        val currentAsrEngine: String  // "dashscope" | "android" | "disabled"
    )

    /**
     * 决策：给定错误 + 上下文，返回下一步 Action
     *
     * 注意：这里不做任何实际动作（不切引擎、不发 Toast），只返回决策
     * CallActivity 根据决策执行具体动作
     */
    fun decide(context: Context): Action {
        val errorLower = context.errorMessage.lowercase()
        Log.i(TAG, "[DIAG] AsrFallbackManager.decide: error='${context.errorMessage.take(80)}', isAndroid=${context.isAndroidAsrAvailable}, currentEngine=${context.currentAsrEngine}")

        return when {
            // 鉴权错：API key 无效
            errorLower.contains("401") ||
            errorLower.contains("403") ||
            errorLower.contains("invalid") && errorLower.contains("api") ||
            errorLower.contains("invalidapikey") ||
            errorLower.contains("unauthorized") -> {
                Log.i(TAG, "[DIAG] 决策: DISABLE_ASR_USE_TEXT (鉴权错)")
                Action.DISABLE_ASR_USE_TEXT
            }

            // 已经在用 Android ASR 失败了 → 不能无限 fallback
            context.currentAsrEngine == "android" -> {
                Log.i(TAG, "[DIAG] 决策: PROMPT_USE_TEXT (已在用 Android，不能再 fallback)")
                Action.PROMPT_USE_TEXT
            }

            // Android ASR 不可用（小米/部分国产 ROM）
            !context.isAndroidAsrAvailable -> {
                Log.i(TAG, "[DIAG] 决策: PROMPT_USE_TEXT (Android ASR 不可用)")
                Action.PROMPT_USE_TEXT
            }

            // NoMatch / No speech → 自动重新监听
            errorLower.contains("no match") ||
            errorLower.contains("no speech") ||
            errorLower.contains("nomatch") -> {
                Log.i(TAG, "[DIAG] 决策: RETRY_AUTO_LISTEN (无语音匹配)")
                Action.RETRY_AUTO_LISTEN
            }

            // 网络错（连接失败、超时、DNS）
            errorLower.contains("timeout") ||
            errorLower.contains("connect") ||
            errorLower.contains("dns") ||
            errorLower.contains("network") ||
            errorLower.contains("unreachable") -> {
                Log.i(TAG, "[DIAG] 决策: FALLBACK_TO_ANDROID (网络错)")
                Action.FALLBACK_TO_ANDROID
            }

            // 其他错误：弹错误信息让用户决定
            else -> {
                Log.i(TAG, "[DIAG] 决策: SHOW_ERROR_TO_USER (未知错误)")
                Action.SHOW_ERROR_TO_USER
            }
        }
    }

    /**
     * 给定 Action，返回对应的用户可读提示文案
     * CallActivity 用这个在 status bar / error banner 显示
     */
    fun getUserMessage(action: Action): String = when (action) {
        Action.RETRY_AUTO_LISTEN -> "未识别到语音，请重试"
        Action.DISABLE_ASR_USE_TEXT -> "语音服务认证失败，请使用文字输入"
        Action.FALLBACK_TO_ANDROID -> "切换到 Android 语音识别"
        Action.PROMPT_USE_TEXT -> "设备不支持语音识别，请使用文字输入"
        Action.SHOW_ERROR_TO_USER -> "语音识别出错，请稍后重试"
    }
}
