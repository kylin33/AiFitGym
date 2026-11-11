package io.github.kylin33.aifitgym.data.ai

import kotlinx.serialization.Serializable

/**
 * 服务端点配置。
 *
 * 只留三个参数就是因为协议统一：OpenAI、DeepSeek 官方、Gemini（它自己提供了
 * `/v1beta/openai/` 兼容端点）、OneAPI / NewAPI 这类中转站、以及本地 Ollama，
 * 全都吃标准的 `POST {baseUrl}/chat/completions`，请求体与响应体结构一致。
 *
 * [baseUrl] 约定**带版本段**（`.../v1`、`.../v1beta/openai`），因为中转站的
 * 路径前缀千奇百怪，猜错一次就是 404，不如让用户直接照抄服务商文档里的那串。
 * 万一用户把完整的 `.../chat/completions` 也粘进来，[chatUrl] 会认出来不再重复拼接。
 */
@Serializable
data class AiEndpoint(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    fun chatUrl(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.isEmpty() -> ""
            trimmed.endsWith("/chat/completions") -> trimmed
            else -> "$trimmed/chat/completions"
        }
    }

    /** 卡片上展示的短主机名，避免把整条 URL 铺在设置页摘要里。 */
    val hostLabel: String
        get() = baseUrl.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .ifBlank { "未设置" }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}

/**
 * 用户自述的训练背景。
 *
 * 这些是「AI 无法从数据库里读出来」的信息：想练成什么样、一周能来几次、
 * 身上有没有旧伤。历史成绩和技能进度则走 Room 查询，不进这里。
 *
 * 全部可空/可空串：第一次打开时不该逼用户填表，直接聊也能用。
 */
@Serializable
data class AiProfile(
    /** 目标，如「增肌」「减脂」「先练会引体向上」。 */
    val goal: String = "",
    /** 训练水平：新手 / 有基础 / 进阶。 */
    val level: String = "有基础",
    /** 每周计划练几次。 */
    val daysPerWeek: Int = 3,
    /** 单次可接受时长（分钟）。 */
    val sessionMinutes: Int = 40,
    /** 器械、伤病、作息等限制。 */
    val notes: String = "",
)

/** 落盘的完整配置。密钥不在这里 —— 它单独走 Keystore 加密存储。 */
@Serializable
data class AiPreferences(
    val endpoint: AiEndpoint = AiEndpoint(),
    val profile: AiProfile = AiProfile(),
)

/**
 * 设置页的一键填充。
 *
 * [baseUrl] 为空表示「中转站」这类必须自己填的选项，选中后只改提示不改值。
 */
data class AiProviderPreset(
    val label: String,
    val baseUrl: String,
    val model: String,
    val hint: String,
)

val AI_PRESETS: List<AiProviderPreset> = listOf(
    AiProviderPreset(
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
        hint = "便宜、中文好，国内直连不用梯子，最推荐",
    ),
    AiProviderPreset(
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        hint = "需要海外网络；gpt-4o-mini 够用且便宜",
    ),
    AiProviderPreset(
        label = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        model = "gemini-2.5-flash",
        hint = "用 Google 的 OpenAI 兼容端点，路径别漏了 /openai",
    ),
    AiProviderPreset(
        label = "中转站",
        baseUrl = "",
        model = "gpt-4o-mini",
        hint = "OneAPI / NewAPI 之类：填它给的地址（一般以 /v1 结尾）",
    ),
    AiProviderPreset(
        label = "本地 Ollama",
        baseUrl = "http://192.168.1.8:11434/v1",
        model = "qwen2.5:7b",
        hint = "手机与电脑同一 WiFi；地址换成你电脑的局域网 IP",
    ),
)

/** 设置页/聊天页共用的「是否可用」判断。 */
data class AiSettingsState(
    val preferences: AiPreferences = AiPreferences(),
    val hasKey: Boolean = false,
) {
    val ready: Boolean get() = hasKey && preferences.endpoint.isConfigured
}
