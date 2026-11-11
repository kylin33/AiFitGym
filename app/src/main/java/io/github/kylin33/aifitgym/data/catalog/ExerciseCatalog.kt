package io.github.kylin33.aifitgym.data.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `assets/exercises.json` 的顶层结构。
 *
 * 这个文件由 `tools/build_catalogue.py` 生成，字段顺序/命名都以脚本输出为准；
 * 反序列化时开了 `ignoreUnknownKeys`，所以脚本以后多吐字段也不会崩。
 */
@Serializable
data class ExerciseCatalog(
    val version: Int = 1,
    val source: String = "",
    val generatedAt: String = "",
    val count: Int = 0,
    val items: List<ExerciseJson> = emptyList(),
)

/**
 * 单条动作的原始 JSON。
 *
 * 命名保持与上游 openGym 一致（camelCase），中文标签是打包脚本预先查好表写进来的，
 * 所以这里不需要再做一次枚举翻译。
 */
@Serializable
data class ExerciseJson(
    val id: String,
    val name: String = "",
    val nameEn: String = "",
    val hasNameZh: Boolean = false,
    val bodyPart: String = "",
    val bodyPartCn: String = "",
    val category: String = "",
    val categoryCn: String = "",
    val equipment: String = "",
    val equipmentCn: String = "",
    val target: String = "",
    val targetCn: String = "",
    val secondaryMuscles: List<String> = emptyList(),
    val description: String = "",
    val steps: List<String> = emptyList(),
    @SerialName("stepsCn") val stepsInChinese: Boolean = false,
    /** 相对 assets 的演示图路径，如 `gifs/0001.webp`；null 表示上游没有图。 */
    val image: String? = null,
    val femaleVariant: String? = null,

    // ---------------------------------------------------------- 打标字段
    // 由 `tools/tag_exercises.py` 写入。老数据包没有这三块时走默认值，
    // 反序列化不会失败（全部给了默认值），只是技能树会退化成「只有自由池」。
    /** PUSH / PULL / LEGS / CORE / HIIT / CARDIO。 */
    val branch: String = "",
    /** 0 = 自由训练池；1~8 = 该流派第 N 阶主干动作。 */
    val skillLevel: Int = 0,
    val isBodyweightFriendly: Boolean = true,
    /** 仅主干动作有值：SETS_AND_REPS / SETS_AND_DURATION / TOTAL_VOLUME_REPS / CONTINUOUS_DURATION。 */
    val unlockType: String = "SETS_AND_REPS",
    val unlockSets: Int = 3,
    val unlockValue: Int = 0,
    val unlockText: String = "",
)
