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
 * 档案字段的 key。
 *
 * 单独列一份的理由只有一个：**区分「用户明确填的」和「系统默认的」**。
 * `level` 有默认值，用户没动过它也是「新手」；如果直接把这个值喂给模型，
 * 它会当成用户自述 —— 一个练了三年的人被当成新手排计划，第一次就废了。
 * 所以每个字段值之外，再用一个集合记住「哪些是用户自己说的」。
 */
object ProfileField {
    const val GOAL = "goal"
    const val LEVEL = "level"
    const val EXPERIENCE = "experience"
    const val DAYS_PER_WEEK = "daysPerWeek"
    const val SESSION_MINUTES = "sessionMinutes"
    const val TRAINING_DAYS = "trainingDays"
    const val EQUIPMENT = "equipment"
    const val LIMITATIONS = "limitations"
    const val PREFERRED_FOCUS = "preferredFocus"
    const val AVAILABLE_SPACE = "availableSpace"
    const val NOTES = "notes"

    /** 设置页展示与完整度统计的顺序：越靠前越影响计划质量。 */
    val ALL: List<String> = listOf(
        GOAL, LIMITATIONS, EQUIPMENT, EXPERIENCE, LEVEL,
        DAYS_PER_WEEK, SESSION_MINUTES, TRAINING_DAYS,
        PREFERRED_FOCUS, AVAILABLE_SPACE, NOTES,
    )

    /**
     * 没这三项，排出来的计划基本靠猜。
     *
     * 伤病排第一：器械猜错顶多练不了，伤病猜错是受伤。
     */
    val CRITICAL: List<String> = listOf(LIMITATIONS, EQUIPMENT, GOAL)

    fun label(field: String): String = when (field) {
        GOAL -> "目标"
        LEVEL -> "训练水平"
        EXPERIENCE -> "训练经验"
        DAYS_PER_WEEK -> "每周次数"
        SESSION_MINUTES -> "单次时长"
        TRAINING_DAYS -> "能练的日子"
        EQUIPMENT -> "可用器械"
        LIMITATIONS -> "伤病 / 禁忌"
        PREFERRED_FOCUS -> "偏好部位"
        AVAILABLE_SPACE -> "训练空间"
        NOTES -> "其他备注"
        else -> field
    }
}

/** 档案里那些「有默认值、但默认值不代表用户」的项，常量单独放一份好比对。 */
object ProfileDefaults {
    const val LEVEL = "新手"
    const val DAYS_PER_WEEK = 3
    const val SESSION_MINUTES = 40
}

/** 「什么都没有」也是一种明确回答，和「没问过」不是一回事 —— 所以它有确定取值。 */
const val EQUIPMENT_NONE = "无器械"

/** 可用器械的可选项。选 [EQUIPMENT_NONE] 时其它项会被清掉（见 ViewModel）。 */
val EQUIPMENT_OPTIONS: List<String> = listOf(
    EQUIPMENT_NONE, "弹力带", "单杠", "哑铃", "壶铃", "瑜伽垫", "长凳", "跑步机", "健身房",
)

/** 训练经验：问的是「练了多久」，不是「练得好不好」，后者是 [AiProfile.level]。 */
val EXPERIENCE_OPTIONS: List<String> = listOf(
    "不到 3 个月", "3~12 个月", "1~3 年", "3 年以上", "中断后重新开始",
)

/** 偏好部位 / 方向，多选。 */
val FOCUS_OPTIONS: List<String> = listOf(
    "胸", "背", "腿", "核心", "手臂", "肩", "全身", "减脂", "增肌", "体能",
)

/**
 * 用户自述的训练背景。
 *
 * 这些是「AI 无法从数据库里读出来」的信息：想练成什么样、一周能来几次、
 * 身上有没有旧伤、家里有什么器械。历史成绩和技能进度则走 Room 查询，不进这里。
 *
 * 全部可空/可空串：第一次打开时不该逼用户填表，直接聊也能用。
 * 但**没填就是没填**，不要用默认值冒充 —— 来源记在 [confirmed] 里。
 */
@Serializable
data class AiProfile(
    /** 目标，如「增肌」「减脂」「先练会引体向上」。 */
    val goal: String = "",
    /**
     * 训练水平：新手 / 有基础 / 进阶。
     *
     * 默认「新手」而不是「有基础」：默认值就是绝大多数新装用户的实际状态，
     * 猜高了模型一上手就排 4×10，用户做不完，第一印象就是「这 AI 不懂我」。
     * 但它仍然只是默认值，[confirmed] 里没有 [ProfileField.LEVEL] 时告诉模型别当真。
     */
    val level: String = ProfileDefaults.LEVEL,
    /**
     * 训练经验：练了多久、有没有长期中断。
     *
     * 和 [level] 分开是因为两者经常不一致：练过两年但停了半年的人，自评「有基础」，
     * 实际该按重新开始排；只练了三个月但很规律的人，自评「新手」，其实可以上强度。
     */
    val experience: String = "",
    /** 每周计划练几次。 */
    val daysPerWeek: Int = ProfileDefaults.DAYS_PER_WEEK,
    /** 单次可接受时长（分钟）。 */
    val sessionMinutes: Int = ProfileDefaults.SESSION_MINUTES,
    /**
     * 具体哪几天能练：1=周一 … 7=周日，空表示不限。
     *
     * 只有「每周 3 次」的话，AI 没法排周计划（它不知道隔天练还是连练三天），
     * 也没法回答「今天该练什么」。加了这行才谈得上「全程管理」。
     */
    val trainingDays: List<Int> = emptyList(),
    /**
     * 可用器械。空 = 没问过。
     *
     * 结构化而不是塞进自由文本：模型对清单的遵循度明显高于一段散文，
     * 而且「无器械」这种取值能直接约束动作池（`isBodyweightFriendly`）。
     */
    val equipment: List<String> = emptyList(),
    /**
     * 伤病、疼痛、动作禁忌。
     *
     * 从 [notes] 里拆出来单独一项，是这份档案里最重要的一条：
     * 器械、作息写错顶多排得不顺手；伤病是要做红线的信息。混在一段自由文本里，
     * 模型经常只抓走「有哑铃」而漏掉「右肩有旧伤」。
     */
    val limitations: String = "",
    /** 偏好部位 / 方向，如 胸、背、减脂。空 = 没问过。 */
    val preferredFocus: List<String> = emptyList(),
    /** 训练环境限制（空间、楼层、噪音）。空 = 没问过。 */
    val availableSpace: String = "",
    /** 器械、作息等其余限制。 */
    val notes: String = "",
    /**
     * 旧字段：v0.9 之前伤病存在这里。
     *
     * 保留只为把老数据读进来搬到 [limitations]（见 `AiSettingsRepository`），
     * 新代码不要写它，迁移后一律留空。
     */
    val injuries: String = "",
    /**
     * 用户**明确填过**的字段 key（[ProfileField]）。
     *
     * 不是「值非空」：`level` 默认「新手」，用户没动过时它非空但不算他说过。
     * 渲染简报时靠这个集合区分三态 —— 用户说的 / 系统默认 / 压根没填。
     */
    val confirmed: Set<String> = emptySet(),
) {
    /** 这项有没有实际内容（只看值，不看是谁填的）。 */
    fun hasValue(field: String): Boolean = when (field) {
        ProfileField.GOAL -> goal.isNotBlank()
        ProfileField.LEVEL -> level.isNotBlank()
        ProfileField.EXPERIENCE -> experience.isNotBlank()
        ProfileField.DAYS_PER_WEEK -> daysPerWeek > 0
        ProfileField.SESSION_MINUTES -> sessionMinutes > 0
        ProfileField.TRAINING_DAYS -> trainingDays.isNotEmpty()
        ProfileField.EQUIPMENT -> equipment.isNotEmpty()
        ProfileField.LIMITATIONS -> limitations.isNotBlank()
        ProfileField.PREFERRED_FOCUS -> preferredFocus.isNotEmpty()
        ProfileField.AVAILABLE_SPACE -> availableSpace.isNotBlank()
        ProfileField.NOTES -> notes.isNotBlank()
        else -> false
    }

    /**
     * 用户**真正给过内容**的项数。
     *
     * 要求「填过」且「有值」：点开又取消的选择不该算数，
     * `daysPerWeek` 这种有默认值的项也不该因为默认值就自动算一分。
     */
    val filledCount: Int get() = ProfileField.ALL.count { it in confirmed && hasValue(it) }

    /**
     * 关键项里还没有内容的。
     *
     * 这三项的默认值都是空，所以「有值」就等于「用户说过」；
     * 设置页用它提示「还缺什么」，提示词用它决定「排计划前该先问哪句」。
     */
    fun missingCritical(): List<String> = ProfileField.CRITICAL.filterNot { hasValue(it) }

    /** 某一项是不是用户自己说的（用来区分默认值与用户输入）。 */
    fun isConfirmed(field: String): Boolean = field in confirmed
}

/** 落盘的完整配置。密钥不在这里 —— 它单独走 Keystore 加密存储。 */
@Serializable
data class AiPreferences(
    val endpoint: AiEndpoint = AiEndpoint(),
    val profile: AiProfile = AiProfile(),
    /**
     * 全程 AI 教练：开启后，练完可以一键让教练复盘，并能直接改动计划。
     *
     * 默认关。不开的时候 App 就是「练 + 偶尔问一句」，不会因为装了 AI 功能就偷偷花 token。
     */
    val autoCoach: Boolean = false,
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
