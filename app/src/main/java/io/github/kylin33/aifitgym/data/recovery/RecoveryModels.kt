package io.github.kylin33.aifitgym.data.recovery

import kotlin.math.exp

/**
 * 72 小时肌肉恢复算法。
 *
 * 三步走，每一步都只做一件事：
 *  1. **加权累加**：一组主发力肌 +1.0、副肌群 +0.4（和热力图的权重同一套，
 *     见 [io.github.kylin33.aifitgym.data.anatomy.MuscleScoring]），所以「练了几组」直接等于「攒了多少负荷」。
 *  2. **时间衰减**：每个批次按练完到现在的小时数线性打折 —— 刚练完 1.0，24h 0.66，48h 0.33，72h 归零。
 *     线性而不是指数，是因为用户能自己心算：「昨天练的胸还有三分之二」。
 *  3. **饱和映射**：负荷是开区间无上限的（有人能练 20 组），热力图要 0…1，
 *     用 `1 - exp(-load / 4)` 压到 0…1，永远压不爆。
 *
 * 阈值和 assets/anatomy/muscles.json 里的 `fatigue.bands` 对齐（0.25 / 0.5），
 * 中间再插一档，配色就能走「灰 → 黄 → 橙 → 红」四级。
 */
object RecoveryPolicy {

    /** 恢复窗口：线性衰减到零的总时长。 */
    const val WINDOW_HOURS = 72f

    /**
     * 饱和尺度（单位：加权组数）。
     *
     * 直观校准：1 组 ≈ 0.22（轻微）、3 组 ≈ 0.53（恢复中）、6 组 ≈ 0.78（酸痛）、
     * 10 组 ≈ 0.92（很酸）。取 4 而不是 1，是因为上游色阶的注释是 `1 - exp(-x)`，
     * 直接套的话单组就把肌肉涂成深红，用户会以为做一组就废了。
     */
    const val SATURATION = 4f

    /** 低于此值算「已恢复」，画底色。 */
    const val READY_BELOW = 0.12f

    /** 轻微疲劳。 */
    const val LIGHT_BELOW = 0.3f

    /** 恢复中（对应 muscles.json 的 recoveringBelow 0.5，略放宽好让橙色早点出现）。 */
    const val SORE_BELOW = 0.55f

    /** 深度酸痛（红）。 */
    const val HEAVY_BELOW = 0.78f

    /** 时间衰减系数：0h → 1.0，24h → 0.667，48h → 0.333，72h → 0。 */
    fun decayAt(hours: Float): Float = (1f - hours / WINDOW_HOURS).coerceIn(0f, 1f)

    /** 负荷 → 疲劳度 0…1。 */
    fun fatigueOf(load: Float): Float = if (load <= 0f) 0f else 1f - exp(-load / SATURATION)

    /** 疲劳度 → 离散档位 0…4（给 MuscleBodyMap 的恢复期配色用）。 */
    fun levelOf(fatigue: Float): Int = when {
        fatigue < READY_BELOW -> 0
        fatigue < LIGHT_BELOW -> 1
        fatigue < SORE_BELOW -> 2
        fatigue < HEAVY_BELOW -> 3
        else -> 4
    }

    fun statusOf(fatigue: Float): String = when (levelOf(fatigue)) {
        0 -> "已恢复"
        1 -> "轻微"
        2 -> "恢复中"
        3 -> "酸痛"
        else -> "很酸"
    }

    /** 距离「完全恢复」还剩多少小时：最后一组练完满 72 小时才算归零。 */
    fun remainingHours(hoursSince: Float): Float = (WINDOW_HOURS - hoursSince).coerceIn(0f, WINDOW_HOURS)
}

/** 单块肌肉的当前状态。 */
data class MuscleLoad(
    val slug: String,
    val nameCn: String,
    val nameEn: String,
    /** 72 小时内涉及它的原始组数（主发力 + 辅助都记一次，用于文案）。 */
    val sets: Int,
    /** 衰减后的加权负荷，热力图的原料。 */
    val load: Float,
    /** 0…1 疲劳度。 */
    val fatigue: Float,
    val lastTrainedAt: Long,
    val hoursSince: Float,
) {
    val level: Int get() = RecoveryPolicy.levelOf(fatigue)
    val status: String get() = RecoveryPolicy.statusOf(fatigue)

    /** 还需要多久彻底恢复，用于「约 12 小时后」这类文案。 */
    val remainingHours: Float get() = RecoveryPolicy.remainingHours(hoursSince)
}

/** 恢复看板的一份快照。 */
data class RecoverySnapshot(
    /** 只含有负荷的肌肉，按疲劳度降序 —— [deepest] 就是今天最该避开的那块。 */
    val loads: List<MuscleLoad> = emptyList(),
    /** slug → 疲劳度，给连续色阶用（可选）。 */
    val scores: Map<String, Float> = emptyMap(),
    /** slug → 档位 0…4，给 [io.github.kylin33.aifitgym.ui.components.MuscleBodyMap] 的 fatigue 模式。 */
    val levels: Map<String, Int> = emptyMap(),
    /** 窗口内总组数。 */
    val totalSets: Int = 0,
    /** 词表里的肌肉总数，用来算「还有几处是满血」。 */
    val muscleCount: Int = 0,
    val lastTrainedAt: Long? = null,
) {
    val isEmpty: Boolean get() = loads.isEmpty()

    /** 还在恢复期（≥ 轻微）的肌肉数。 */
    val recoveringCount: Int get() = loads.count { it.fatigue >= RecoveryPolicy.READY_BELOW }

    /** 完全恢复的肌肉数。 */
    val readyCount: Int get() = (muscleCount - recoveringCount).coerceAtLeast(0)

    val deepest: MuscleLoad? get() = loads.firstOrNull()
}

/** 某个肌群在统计窗口内的训练量。 */
data class MuscleVolume(
    val slug: String,
    val nameCn: String,
    val nameEn: String,
    /** 原始组数。 */
    val sets: Int,
    /** 加权组数（主 1.0 / 副 0.4），排行榜按这个排。 */
    val load: Float,
    /** 占窗口总量的比例。 */
    val share: Float,
    /** 相对最大值归一化，热力图用。 */
    val normalized: Float,
)

/** 训练量看板（7 天 / 30 天）的一份快照。 */
data class VolumeSnapshot(
    val days: Int,
    val sessions: Int = 0,
    val totalSets: Int = 0,
    val durationSec: Int = 0,
    val muscles: List<MuscleVolume> = emptyList(),
    /** 重点肌群里一组都没练到的，按词表顺序。 */
    val gaps: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = totalSets == 0

    val top: MuscleVolume? get() = muscles.firstOrNull()

    /** 最高占比；用来判断练得是不是太偏。 */
    val focusShare: Float get() = top?.share ?: 0f

    /** 换一个时间窗时，热力图需要 slug → 0…1。 */
    fun scores(): Map<String, Float> = muscles.associate { it.slug to it.normalized }
}
