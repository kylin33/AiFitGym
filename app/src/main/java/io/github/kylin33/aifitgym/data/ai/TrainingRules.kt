package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineCategory
import io.github.kylin33.aifitgym.data.skill.SkillBranch
import kotlin.math.roundToInt

/**
 * 训练规则：**能算的都算，模型只做判断**。
 *
 * 提示词里写着「复合动作休息 90~180 秒」「新手 RIR 3」，但模型十次里有两次会写
 * `restSec: 30` 配深蹲。所以这几种规则做成纯函数，在计划落库前由程序兜一遍，
 * 提示词只负责让模型**理解**为什么，不指望它每次都算对。
 *
 * 这一层刻意不碰数据库、不碰 Compose —— 全都能单测，见 `TrainingRulesTest`。
 */

/**
 * 动作类型。决定休息区间和默认 RIR 的三分类，比「流派」更接近训练学上的分法。
 *
 * 库里没有标注过复合/孤立，只能从**部位 + 参与肌群数 + 计次方式**推。
 * 这是近似值，用途仅限「给模型一个参照、给程序一个兜底区间」，
 * 不参与任何成绩计算 —— 推错了一个动作，最坏结果是休息多给了 30 秒。
 */
enum class ExerciseClass(val label: String) {
    /** 多关节、多肌群：俯卧撑、引体、深蹲、波比。 */
    COMPOUND("复合"),

    /** 单关节：弯举、侧平举、腿屈伸。 */
    ISOLATION("孤立"),

    /** 静态支撑：平板、悬垂、靠墙静蹲。 */
    STATIC("静态"),

    /** 有氧 / 间歇：波比跳、开合跳、跑步。 */
    CARDIO("有氧"),
    ;

    companion object {
        /** 大部位默认按复合算 —— 这块的徒手动作用力都是整条链一起发。 */
        private val COMPOUND_PARTS = setOf("胸部", "背部", "大腿", "全身")

        /** 肩部动作一半是推举（复合）一半是平举（孤立），看辅助肌群数量再判。 */
        private val AMBIGUOUS_PARTS = setOf("肩部", "腰腹")

        fun of(exercise: ExerciseEntity): ExerciseClass {
            val branch = SkillBranch.from(exercise.branch)
            if (branch == SkillBranch.HIIT || branch == SkillBranch.CARDIO) return CARDIO
            // 计时类：核心是静态支撑，其余（拉伸、静蹲）也同理
            if (exercise.unlockType.contains("DURATION")) return STATIC
            val part = exercise.bodyPartCn
            if (part in COMPOUND_PARTS) return COMPOUND
            if (part in AMBIGUOUS_PARTS && exercise.secondaryMuscles.size >= 2) return COMPOUND
            return ISOLATION
        }
    }
}

/**
 * 动作难度，只给模型做取舍参考。
 *
 * 优先用技能树的阶数（那是脚本按「从易到难」排过的），没有阶数的自由池动作
 * 退化成「复合=中、孤立=低」。不假装精确：库里 2303 条没有任何强度标签，
 * 编一套 1~10 的难度分只会让人以为它是量过的。
 */
enum class Difficulty(val label: String) {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    ;

    companion object {
        fun of(exercise: ExerciseEntity, cls: ExerciseClass = ExerciseClass.of(exercise)): Difficulty = when {
            exercise.skillLevel >= 5 -> HIGH
            exercise.skillLevel in 1..4 -> MEDIUM
            cls == ExerciseClass.COMPOUND -> MEDIUM
            else -> LOW
        }
    }
}

/**
 * 组间休息。
 *
 * 推荐区间来自训练学惯例（复合动作要等磷酸原恢复，孤立动作没必要坐那么久），
 * 程序只兜**下界**和**总上限**：
 *  - 下界必须抬起来 —— 深蹲歇 30 秒不是「练得狠」，是下一组直接崩，练成有氧；
 *  - 上界放宽到 [MAX_SEC] —— 接近极限的单组真的需要 3 分钟以上，硬压到 180 反而错。
 *
 * 也就是说这里不做「削峰」，只做「填坑」。提示词负责让模型别乱给。
 */
object RestPolicy {

    /** 全局下限：HIIT 的 10 秒间歇已经是最短的合理值。 */
    const val MIN_SEC = 10

    /** 全局上限。用户要求把天花板从 180 提到 240。 */
    const val MAX_SEC = 240

    /** 推荐区间，提示词与简报引用同一份，免得两处对不上。 */
    fun bandOf(cls: ExerciseClass, category: RoutineCategory): IntRange = when {
        category == RoutineCategory.HIIT_CARDIO -> 10..90
        cls == ExerciseClass.COMPOUND -> 90..180
        cls == ExerciseClass.ISOLATION -> 45..90
        else -> 30..60
    }

    fun defaultOf(cls: ExerciseClass, category: RoutineCategory): Int = when {
        category == RoutineCategory.HIIT_CARDIO -> 20
        cls == ExerciseClass.COMPOUND -> 120
        cls == ExerciseClass.ISOLATION -> 60
        else -> 45
    }

    /** 模型没给 / 给得太小 / 给得太大，都在这里拉回可执行的范围。 */
    fun coerce(requested: Int?, cls: ExerciseClass, category: RoutineCategory): Int {
        val band = bandOf(cls, category)
        val raw = requested ?: return defaultOf(cls, category)
        return raw.coerceAtLeast(band.first).coerceIn(MIN_SEC, MAX_SEC)
    }
}

/**
 * RIR（Reps In Reserve）：一组结束时还能再做几次。
 *
 * 这是「做几次」之外唯一能让模型知道**做到什么程度**的量。写进计划里，
 * 训练页会显示「留 2 次余力」，用户不用猜「10 次」是做到力竭还是留力。
 *
 * 计时类动作不谈 RIR（撑 45 秒没有「还能再撑几次」这回事）。
 */
object RirPolicy {

    const val MIN = 0
    const val MAX = 5

    fun defaultOf(cls: ExerciseClass, beginner: Boolean): Int? = when (cls) {
        ExerciseClass.COMPOUND -> if (beginner) 3 else 2
        ExerciseClass.ISOLATION -> if (beginner) 2 else 1
        // 静态支撑和有氧的「余力」靠时长/间歇表达，不塞 RIR
        ExerciseClass.STATIC, ExerciseClass.CARDIO -> null
    }

    fun coerce(requested: Int?, cls: ExerciseClass, beginner: Boolean): Int? {
        if (cls == ExerciseClass.STATIC || cls == ExerciseClass.CARDIO) return null
        if (requested == null) return defaultOf(cls, beginner)
        return requested.coerceIn(MIN, MAX)
    }

    /** 「留 2 次余力」；0 说成「做到力竭」更好懂。 */
    fun describe(rir: Int): String = when {
        rir <= 0 -> "做到力竭"
        else -> "留 $rir 次余力"
    }
}

/** 一次训练该练多少。区间给模型，警告给用户。 */
data class VolumeAdvice(
    val minSets: Int,
    val maxSets: Int,
    val minExercises: Int,
    val maxExercises: Int,
    /** 一句话理由，直接进简报。 */
    val reason: String,
) {
    fun toPromptLine(): String = buildString {
        append("单次 $minSets~$maxSets 组、$minExercises~$maxExercises 个动作")
        append("（$reason）")
    }
}

/**
 * 训练量控制。
 *
 * 依据只有一条：**他最近实际练了多少**。档案里写「每周 3 次」但最近 7 天一场没练的人，
 * 不能按「每周 3 次」给他排 24 组；反过来，一个每场都在 25 组的人，
 * 排 12 组是在浪费他的时间。
 *
 * 上界取近期的 1.25 倍：加量的节奏以「周」为单位，单次突然翻倍就是伤病的来源。
 */
object VolumePolicy {

    /** 单次组数的硬上限 —— 超过它无论记录如何都不合理。 */
    const val HARD_MAX_SETS = 24

    /** 停练多久算「重启」，需要减量。 */
    const val RESTART_DAYS = 14

    fun advise(
        stats: TrainingStats,
        beginner: Boolean,
        daysSinceLast: Int? = stats.daysSinceLast,
    ): VolumeAdvice {
        val average = averageSetsPerSession(stats)
            ?: return VolumeAdvice(
                minSets = 9,
                maxSets = 12,
                minExercises = 3,
                maxExercises = if (beginner) 5 else 6,
                reason = "还没有可参考的记录，先按最小有效量排",
            )

        val restart = (daysSinceLast ?: 0) >= RESTART_DAYS
        // 停了两周以上：按七成重启，不是接着上次的强度练
        val base = if (restart) (average * 0.7).roundToInt().coerceAtLeast(6) else average

        val cap = if (beginner) 16 else HARD_MAX_SETS
        val maxSets = (base * 1.25).roundToInt().coerceIn(10, cap)
        val minSets = (base * 0.8).roundToInt().coerceAtLeast(6).coerceAtMost(maxSets)

        return VolumeAdvice(
            minSets = minSets,
            maxSets = maxSets,
            minExercises = if (beginner) 3 else 4,
            maxExercises = if (beginner) 5 else 7,
            reason = when {
                restart -> "停练 ${daysSinceLast} 天，按上次的七成重启"
                beginner -> "新手先建立动作质量，组数不必堆满"
                else -> "按他近期每场约 $average 组排，别一次加太多"
            },
        )
    }

    /**
     * 近期每场平均组数。
     *
     * 先用近 7 天：它最接近「现在能承受多少」。近 7 天没练才退到 30 天，
     * 因为那时候 7 天窗口是空的，拿 0 去算会得出「他练不了」的荒谬结论。
     */
    fun averageSetsPerSession(stats: TrainingStats): Int? {
        val window = when {
            stats.last7.sessions > 0 -> stats.last7
            stats.last30.sessions > 0 -> stats.last30
            else -> return null
        }
        if (window.totalSets <= 0 || window.sessions <= 0) return null
        return (window.totalSets.toDouble() / window.sessions).roundToInt().coerceAtLeast(6)
    }
}
