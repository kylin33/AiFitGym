package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AI 计划块的数据结构。
 *
 * 字段全部带默认值，等于把「模型少写一个 key」当成正常情况处理 ——
 * 提示词再严，模型偶尔也会漏字段或写成字符串。少一个 `sets` 不该让整段计划作废。
 */
@Serializable
data class AiPlanJson(
    val name: String = "",
    val description: String = "",
    /** CALISTHENICS / HIIT_CARDIO，也容忍中文「徒手力量」。 */
    val category: String = "",
    /** 教练为什么排这套，一句话；展示在卡片顶部。 */
    val reason: String = "",
    val items: List<AiPlanItemJson> = emptyList(),
)

@Serializable
data class AiPlanItemJson(
    val exerciseId: String = "",
    val sets: Int = 3,
    /** 计次，与 [durationSec] 二选一。 */
    val reps: Int? = null,
    /** 计时（秒），与 [reps] 二选一。 */
    val durationSec: Int? = null,
    val restSec: Int? = null,
    /** 教练对这条的一句话嘱咐。 */
    val note: String? = null,
)

/** 解析并校验过的一条计划动作：动作本体来自 Room，所以名字/动图/肌群都对得上。 */
data class PlanDraftItem(
    val exercise: ExerciseEntity,
    val sets: Int,
    val reps: Int?,
    val durationSec: Int?,
    val restSec: Int,
    val note: String?,
)

/**
 * 一份可以「一键导入」的计划。
 *
 * [dropped] 是被丢掉的条目数（模型编了不存在的 exerciseId，或字段离谱到没法修）——
 * 有值时卡片上会提示一句，而不是假装什么都没发生。
 */
data class PlanDraft(
    val name: String,
    val description: String,
    val reason: String,
    val category: RoutineCategory,
    val items: List<PlanDraftItem>,
    val dropped: Int,
)

/**
 * 把「自然语言 + ```json_routine``` 代码块」的混合回复拆开。
 *
 * 为什么不用「让模型只输出 JSON」：那样就没有教练味了，用户看到的会是一坨字段。
 * 混排的代价是必须把代码块从正文里剥掉，否则聊天气泡里会露出原始 JSON。
 *
 * 流式输出时这个函数同样能用：代码块还没闭合就**截断**（不显示半截 JSON），
 * 所以界面上不会出现「`{"name":"推` 这种一闪而过的脏字。
 */
object PlanBlockParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    data class Split(val text: String, val blocks: List<String>)

    fun split(raw: String): Split {
        val blocks = mutableListOf<String>()
        val shown = StringBuilder()
        var cursor = 0

        while (true) {
            val start = raw.indexOf("```", cursor)
            if (start < 0) {
                shown.append(raw, cursor, raw.length)
                break
            }
            val lineEnd = raw.indexOf('\n', start)
            if (lineEnd < 0) {
                // 围栏刚开、还没换行：正在流式输出，先不显示
                shown.append(raw, cursor, start)
                break
            }
            val tag = raw.substring(start + 3, lineEnd).trim().lowercase()
            val close = raw.indexOf("```", lineEnd)
            if (close < 0) {
                // 有头无尾：整段藏起来，等它写完
                shown.append(raw, cursor, start)
                break
            }
            val content = raw.substring(lineEnd + 1, close)
            val isJson = tag.isEmpty() || tag.startsWith("json")
            if (isJson && looksLikePlan(content)) {
                // 围栏之前的正文要先补上，否则「先说一句再给计划」的回复会丢掉前半段
                shown.append(raw, cursor, start)
                blocks += content
            } else {
                // 普通代码块（模型顺手给了个示例），原样留在正文里
                shown.append(raw, cursor, close + 3)
            }
            cursor = close + 3
        }

        return Split(text = shown.toString().trim(), blocks = blocks)
    }

    fun parse(block: String): AiPlanJson? =
        runCatching { json.decodeFromString<AiPlanJson>(block.trim()) }.getOrNull()

    /**
     * 判断一段代码块是不是训练计划。
     *
     * 只看 `items` + `exerciseId` 两个特征词，不看围栏标签 ——
     * 有的模型会写成 ```json 而不是 ```json_routine，不该因此丢掉一份有效计划。
     */
    private fun looksLikePlan(content: String): Boolean =
        content.contains("\"items\"") && content.contains("exerciseId")

    fun categoryOf(raw: String): RoutineCategory = when {
        raw.contains("HIIT", ignoreCase = true) -> RoutineCategory.HIIT_CARDIO
        raw.contains("CARDIO", ignoreCase = true) -> RoutineCategory.HIIT_CARDIO
        raw.contains("有氧", ignoreCase = true) -> RoutineCategory.HIIT_CARDIO
        raw.contains("间歇", ignoreCase = true) -> RoutineCategory.HIIT_CARDIO
        else -> RoutineCategory.fromKey(raw)
    }
}
