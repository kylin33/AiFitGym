package io.github.kylin33.aifitgym.data.skill

import io.github.kylin33.aifitgym.data.repo.SetOutcome
import kotlin.math.ceil

/**
 * 六大流派。前四条是徒手力量，后两条是心肺与耐力。
 *
 * `key` 就是存进数据库的值（`exercises.branch` / `branch_progress.branch`），
 * 与 `tools/tag_exercises.py` 里的枚举必须一字不差。
 */
enum class SkillBranch(val key: String, val label: String, val en: String) {
    PUSH("PUSH", "推力", "PUSH"),
    PULL("PULL", "拉力", "PULL"),
    LEGS("LEGS", "下肢", "LEGS"),
    CORE("CORE", "核心", "CORE"),
    HIIT("HIIT", "爆发心肺", "HIIT"),
    CARDIO("CARDIO", "有氧耐力", "CARDIO"),
    ;

    companion object {
        fun from(key: String?): SkillBranch? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }

        fun of(key: String?): SkillBranch = from(key) ?: CORE

        /** 老数据包缺 `branch` 时的兜底：按部位归到最接近的流派，宁可粗糙也不要空流派。 */
        fun fallbackOf(bodyPart: String): SkillBranch = when (bodyPart) {
            "chest", "shoulders" -> PUSH
            "back", "upper arms", "lower arms" -> PULL
            "upper legs", "lower legs" -> LEGS
            "cardio" -> CARDIO
            else -> CORE
        }
    }
}

/**
 * 达标口径。四种类型通吃「计次 / 计时 / 累计 / 单组极限」。
 *
 * 存在 `exercises.unlockType` 里（字符串），所以动作库重灌不会丢规则；
 * 判定只有 [UnlockRequirement.meets] 一份实现。
 */
enum class UnlockMetricType {
    /** 单次训练完成 N 组，每组不少于 X 次（如 3 组 × 15 次俯卧撑）。 */
    SETS_AND_REPS,

    /** 单次训练完成 N 组，每组不少于 X 秒（如 3 组 × 60 秒平板）。 */
    SETS_AND_DURATION,

    /** 单次训练累计 X 次，组数不限（如 100 个波比）。 */
    TOTAL_VOLUME_REPS,

    /** 单组连续坚持 X 秒（如连续慢跑 1800 秒）。 */
    CONTINUOUS_DURATION,
    ;

    companion object {
        fun from(raw: String?): UnlockMetricType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: SETS_AND_REPS
    }
}

/**
 * 一条通关条件。
 *
 * [text] 是脚本写好的展示文案（「3 组 × 每侧 8 次」这种带单位的说法模板拼不出来），
 * 但「熟练度突破」打折后目标值变了，文案必须重算，所以 [label] 带一个折扣参数。
 */
data class UnlockRequirement(
    val type: UnlockMetricType,
    /** 目标组数；累计类和单组极限类没有组数概念，固定 1。 */
    val sets: Int,
    /** 目标值：计次是次数、计时是秒数。 */
    val value: Int,
    val text: String,
) {
    val isTimed: Boolean
        get() = type == UnlockMetricType.SETS_AND_DURATION ||
            type == UnlockMetricType.CONTINUOUS_DURATION

    val isVolume: Boolean get() = type == UnlockMetricType.TOTAL_VOLUME_REPS

    val unit: String get() = if (isTimed) "秒" else "次"

    /**
     * 熟练度突破后的条件：只下调目标值，组数不动。
     *
     * 组数代表训练结构（「做满 3 组」本身就是要求），砍组数等于换了个动作；
     * 目标值才是能力门槛，打折打在它身上最合理。
     */
    fun discounted(percent: Int): UnlockRequirement {
        val safe = percent.coerceIn(0, SkillRules.MAX_DISCOUNT)
        if (safe <= 0) return this
        val reduced = ceil(value * (100 - safe) / 100.0).toInt().coerceAtLeast(1)
        return copy(value = reduced)
    }

    /** 展示文案；[percent] > 0 时会按折后值重新拼。 */
    fun label(percent: Int = 0): String {
        if (percent <= 0) return text.ifBlank { defaultText() }
        return when (type) {
            UnlockMetricType.SETS_AND_REPS -> "$sets 组 × $value 次"
            UnlockMetricType.SETS_AND_DURATION -> "$sets 组 × $value 秒"
            UnlockMetricType.TOTAL_VOLUME_REPS -> "单场累计 $value 次"
            UnlockMetricType.CONTINUOUS_DURATION -> "连续 $value 秒"
        }
    }

    private fun defaultText(): String = when (type) {
        UnlockMetricType.SETS_AND_REPS -> "$sets 组 × $value 次"
        UnlockMetricType.SETS_AND_DURATION -> "$sets 组 × $value 秒"
        UnlockMetricType.TOTAL_VOLUME_REPS -> "单场累计 $value 次"
        UnlockMetricType.CONTINUOUS_DURATION -> "连续 $value 秒"
    }

    /**
     * 四种口径的唯一实现。写成 `when` 而不是多态：规则太短，多态反而绕；
     * 加新类型时编译器会强制这里补分支（enum 穷尽）。
     */
    fun meets(outcomes: List<SetOutcome>): Boolean {
        if (outcomes.isEmpty() || value <= 0) return false
        return when (type) {
            UnlockMetricType.SETS_AND_REPS ->
                outcomes.count { it.reps >= value } >= sets

            UnlockMetricType.SETS_AND_DURATION ->
                outcomes.count { it.seconds >= value } >= sets

            UnlockMetricType.TOTAL_VOLUME_REPS ->
                outcomes.sumOf { it.reps } >= value

            UnlockMetricType.CONTINUOUS_DURATION ->
                outcomes.any { it.seconds >= value }
        }
    }
}

/** 双轨升级的数值规则，集中在这里，调参只改这一处。 */
object SkillRules {

    /** 每完成 1 组该流派动作给的 XP（不管是不是树上的主干动作）。 */
    const val XP_PER_SET = 10

    /** 攒够这么多 XP 触发一次「熟练度突破」。 */
    const val XP_PER_BREAKTHROUGH = 300

    /** 每次突破把当前阶的要求降低多少。 */
    const val BREAKTHROUGH_STEP = 20

    /** 最多降到原要求的 40%，再降就变成「点一下就算通关」了。 */
    const val MAX_DISCOUNT = 60

    /** 每条链的阶数。 */
    const val MAX_TIER = 8
}
