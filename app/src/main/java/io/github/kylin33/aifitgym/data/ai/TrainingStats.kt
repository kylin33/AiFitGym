package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.SessionPerformance
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao
import io.github.kylin33.aifitgym.data.skill.SkillBranch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 训练统计：**程序算数，模型解释**。
 *
 * ## 为什么不让模型自己算
 *
 * 原始记录是几百条「某天某动作做了几组几次」。把原始记录丢给它有三个问题：
 *  - 算术不可靠。问「上周练了几组」它会给一个看起来很合理的错数；
 *  - token 贵。30 天明细轻松上万 token，每次对话都烧一遍；
 *  - 口径不明。它没法知道「训练日」是算自然日还是算场次，每次结果都不一样。
 *
 * 所以这里负责把能算的都算准（频率、组数、流派分布、趋势标签），
 * 模型只做它擅长的部分：解读这些数字的含义、据此改计划。
 */
data class TrainingStats(
    val last7: WindowStats,
    val prev7: WindowStats,
    val last30: WindowStats,
    /** 近期在练的动作及其趋势，最多 [MAX_TRENDS] 条。 */
    val trends: List<ExerciseTrend> = emptyList(),
    /** 距上次训练几天；null 表示 30 天窗口内一条记录都没有。 */
    val daysSinceLast: Int? = null,
    /**
     * 每条力量流派最近一次练是几天前，只含近 14 天练过的。
     *
     * 「推了 18 组」和「昨天刚推了 18 组」是两件事：前者是量，后者是「今天不该再推」。
     * 模型看不出这个区别就会排出「昨天练胸，今天还练胸」。
     */
    val lastTrainedByBranch: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = last30.totalSets == 0

    /**
     * 渲染成简报里的一段纯文本。
     *
     * 用文本而不是 JSON：同样的信息，`推 18 · 拉 6` 比
     * `{"push":18,"pull":6}` 少一半 token，模型读起来也不会把字段名当动作名。
     */
    fun toPromptText(): String {
        if (isEmpty) {
            // 简报另一处已经写了「最近训练：没有记录」，这里只说数据本身，
            // 不重复同一句话——重复会让模型以为有两份互相印证的证据
            return "【训练统计】近 30 天没有训练记录，别编「你最近练得不错」这类话。"
        }

        val sb = StringBuilder()
        sb.append("【训练统计·程序已算好，直接引用，不要自己重新统计】\n")
        sb.append("近 7 天：").append(last7.describe()).append('\n')
        sb.append("前 7 天：").append(prev7.describe()).append(changeNote()).append('\n')
        sb.append("近 30 天：").append(last30.describe()).append('\n')
        last30.untrainedBranches().takeIf { it.isNotEmpty() }?.let { branches ->
            sb.append("30 天里一条力量流派都没练的：")
                .append(branches.joinToString("、") { it.label })
                .append('\n')
        }
        if (trends.isNotEmpty()) {
            sb.append("动作表现（近 30 天每次训练的单场最好成绩，最多 4 次）：\n")
            trends.forEach { sb.append("  · ").append(it.describe()).append('\n') }
        }
        if (lastTrainedByBranch.isNotEmpty()) {
            val text = SkillBranch.entries.mapNotNull { branch ->
                val days = lastTrainedByBranch[branch.key] ?: return@mapNotNull null
                "${branch.label} ${if (days == 0) "今天" else "$days 天前"}"
            }.joinToString(" · ")
            sb.append("各流派最近一次：").append(text).append('\n')
        }
        sb.append(
            when (val days = daysSinceLast) {
                null -> "距上次训练：30 天以上"
                0 -> "距上次训练：今天"
                else -> "距上次训练：$days 天前"
            },
        )
        return sb.toString().trimEnd()
    }

    /** 本周比上周多了还是少了，只给数字不给结论。 */
    private fun changeNote(): String {
        if (prev7.totalSets == 0) {
            return if (last7.totalSets == 0) "" else "（上周没练）"
        }
        val delta = last7.totalSets - prev7.totalSets
        if (delta == 0) return "（本周与上一周持平）"
        val sign = if (delta > 0) "+" else ""
        val percent = delta * 100 / prev7.totalSets
        return "（本周 $sign$delta 组 / $sign$percent%）"
    }

}

/** 一个时间窗里的训练量。 */
data class WindowStats(
    val days: Int,
    /** 有几个自然日练过（一天练两次算一天）。 */
    val trainingDays: Int = 0,
    val sessions: Int = 0,
    val totalSets: Int = 0,
    /** 流派 key → 组数，只含有组数的。 */
    val setsByBranch: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = totalSets == 0

    /**
     * 这窗口内一组都没练的**力量**流派。
     *
     * 只数推 / 拉 / 腿 / 核心：一个练徒手的人不练有氧是选择，不是偏科；
     * 把有氧也算进缺口，模型每次都会劝他「加点有氧」，三句话之后就没人看了。
     */
    fun untrainedBranches(): List<SkillBranch> =
        if (isEmpty) {
            emptyList()
        } else {
            STRENGTH_BRANCHES.filter { (setsByBranch[it.key] ?: 0) == 0 }
        }

    private companion object {
        val STRENGTH_BRANCHES = listOf(
            SkillBranch.PUSH,
            SkillBranch.PULL,
            SkillBranch.LEGS,
            SkillBranch.CORE,
        )
    }

    fun describe(): String {
        if (isEmpty) return "没有训练"
        val sb = StringBuilder()
        sb.append(sessions).append(" 场")
        sb.append(" / ").append(trainingDays).append(" 个训练日")
        sb.append(" / ").append(totalSets).append(" 组")
        // 按枚举顺序输出，模型的「推拉是否均衡」判断才有一致的可比位置
        val parts = SkillBranch.entries.mapNotNull { branch ->
            val sets = setsByBranch[branch.key] ?: return@mapNotNull null
            "${branch.label} $sets"
        }
        if (parts.isNotEmpty()) sb.append("｜").append(parts.joinToString(" · "))
        return sb.toString()
    }
}

/** 趋势标签。口径写在 [TrainingStatsMath.trendOf] 里，提示词会引用同一套说法。 */
enum class TrendKind {
    /** 最近两次都在涨。 */
    IMPROVING,

    /** 最近两次都在掉。 */
    DECLINING,

    /** 有起伏但没有连续方向。 */
    STABLE,

    /** 不足 3 次，不判趋势。 */
    INSUFFICIENT,
    ;

    val label: String
        get() = when (this) {
            IMPROVING -> "上升"
            DECLINING -> "连续两次下降"
            STABLE -> "持平"
            INSUFFICIENT -> "次数太少，看不出趋势"
        }
}

/** 一个动作的成绩走向。 */
data class ExerciseTrend(
    val exerciseId: String,
    val name: String,
    /** 最近几次的单场最好成绩，旧 → 新。 */
    val values: List<Int>,
    val isTimed: Boolean,
    val kind: TrendKind,
) {
    fun describe(): String {
        val unit = if (isTimed) "秒" else "次"
        return "$name ${values.joinToString("→")} $unit · ${kind.label}"
    }
}

/**
 * 纯计算部分，全部可单测 —— 统计口径是这份功能里最容易悄悄算错的东西，
 * 藏在数据库查询里就没法验证了。
 */
object TrainingStatsMath {

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** 判趋势至少要有 3 个数据点，2 个点连成一条线说明不了任何事。 */
    const val MIN_TREND_POINTS = 3

    /** 本地自然日的序号，用来数「训练日」。用 UTC 会把凌晨的训练算到前一天。 */
    fun dayKey(millis: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val calendar = Calendar.getInstance(zone, Locale.US)
        calendar.timeInMillis = millis
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    /** 把一批表现按时间窗聚合成训练日 / 场次 / 组数 / 流派分布。 */
    fun window(
        performances: List<SessionPerformance>,
        branchOf: Map<String, String>,
        since: Long,
        until: Long,
        days: Int,
    ): WindowStats {
        val inWindow = performances.filter { it.startedAt >= since && it.startedAt < until }
        if (inWindow.isEmpty()) return WindowStats(days = days)

        val sessions = inWindow.map { it.sessionId }.distinct()
        val trainingDays = inWindow.map { dayKey(it.startedAt) }.distinct().size
        val totalSets = inWindow.sumOf { it.sets }

        val byBranch = HashMap<String, Int>()
        inWindow.forEach { row ->
            val key = branchOf[row.exerciseId] ?: SkillBranch.CORE.key
            byBranch[key] = (byBranch[key] ?: 0) + row.sets
        }

        return WindowStats(
            days = days,
            trainingDays = trainingDays,
            sessions = sessions.size,
            totalSets = totalSets,
            setsByBranch = byBranch.filterValues { it > 0 },
        )
    }

    /**
     * 每条力量流派最近一次训练是几天前，只留 [withinDays] 天内的。
     *
     * 用「距现在多少小时 / 24」而不是自然日相减：这里要的是「昨天还是前天」，
     * 差几个小时的边界误差无所谓；真正需要自然日的「训练日」用的是 [dayKey]。
     */
    fun lastTrainedDays(
        performances: List<SessionPerformance>,
        branchOf: Map<String, String>,
        now: Long,
        withinDays: Int = 14,
    ): Map<String, Int> = performances
        .groupBy { branchOf[it.exerciseId] ?: SkillBranch.CORE.key }
        .mapValues { (_, rows) ->
            ((now - rows.maxOf { it.startedAt }) / DAY_MS).toInt().coerceAtLeast(0)
        }
        .filterValues { it <= withinDays }

    /**
     * 趋势判定。
     *
     * 刻意保守：**必须连续两次同向变化**才算上升/下降。单次掉下来可能只是
     * 那天没睡好、前一组多做了两个、或者换了更难的变式 —— 拿它当疲劳信号，
     * 教练就会一直让用户减量，练半年没进步。
     */
    fun trendOf(values: List<Int>): TrendKind {
        if (values.size < MIN_TREND_POINTS) return TrendKind.INSUFFICIENT
        val last = values[values.size - 1]
        val prev = values[values.size - 2]
        val prevPrev = values[values.size - 3]
        // 严格同向：12→12→9 只掉了一次（前一次是持平），不算连续下降
        return when {
            last > prev && prev > prevPrev -> TrendKind.IMPROVING
            last < prev && prev < prevPrev -> TrendKind.DECLINING
            else -> TrendKind.STABLE
        }
    }
}

/**
 * 从数据库取出原料并组装 [TrainingStats]。
 *
 * 只查一次 30 天窗口的聚合结果，7 天 / 前 7 天 / 30 天都在内存里切 ——
 * 三次查询换来的是三次往返和三套容易写歪的 SQL。
 */
class TrainingStatsBuilder(
    private val sessionDao: WorkoutSessionDao,
    private val exerciseDao: ExerciseDao,
) {

    suspend fun build(now: Long = System.currentTimeMillis()): TrainingStats {
        val day = TrainingStatsMath.DAY_MS
        val since30 = now - 30 * day
        val since14 = now - 14 * day
        val since7 = now - 7 * day

        val performances = runCatching { sessionDao.performancesBetween(since30, now) }
            .getOrDefault(emptyList())

        val emptyWindow = { days: Int -> WindowStats(days = days) }
        if (performances.isEmpty()) {
            return TrainingStats(
                last7 = emptyWindow(7),
                prev7 = emptyWindow(7),
                last30 = emptyWindow(30),
                daysSinceLast = null,
            )
        }

        val ids = performances.map { it.exerciseId }.distinct()
        val exercises = runCatching { exerciseDao.findByIds(ids) }.getOrDefault(emptyList())
        val branchOf = exercises.associate { it.id to it.branch }
        val nameOf = exercises.associate { it.id to it.name }

        val lastDone = performances.maxOf { it.startedAt }

        return TrainingStats(
            last7 = TrainingStatsMath.window(performances, branchOf, since7, now, 7),
            prev7 = TrainingStatsMath.window(performances, branchOf, since14, since7, 7),
            last30 = TrainingStatsMath.window(performances, branchOf, since30, now, 30),
            trends = buildTrends(performances, nameOf),
            daysSinceLast = ((now - lastDone) / day).toInt().coerceAtLeast(0),
            lastTrainedByBranch = TrainingStatsMath.lastTrainedDays(performances, branchOf, now),
        )
    }

    /**
     * 挑出「值得看趋势」的动作。
     *
     * 条件是近 30 天至少练过 2 次 —— 一次两次连不成线。再按场次降序取前 6 个控制 token；
     * 计次与计时分开取值，混在一起比大小没有意义。
     */
    private fun buildTrends(
        performances: List<SessionPerformance>,
        nameOf: Map<String, String>,
    ): List<ExerciseTrend> {
        return performances
            .groupBy { it.exerciseId }
            .filter { (_, rows) -> rows.size >= 2 }
            .entries
            .sortedByDescending { it.value.size }
            .take(MAX_TRENDS)
            .mapNotNull { (exerciseId, rows) ->
                val name = nameOf[exerciseId] ?: return@mapNotNull null
                val ordered = rows.sortedBy { it.startedAt }
                // 以最近一次的形态为准：同一动作中途从计次改成计时，早期数据不可比
                val timed = ordered.last().isTimed
                val values = ordered
                    .filter { it.isTimed == timed }
                    .map { if (timed) it.bestSeconds else it.bestReps }
                    .filter { it > 0 }
                    .takeLast(MAX_POINTS)
                if (values.isEmpty()) return@mapNotNull null
                ExerciseTrend(
                    exerciseId = exerciseId,
                    name = name,
                    values = values,
                    isTimed = timed,
                    kind = TrainingStatsMath.trendOf(values),
                )
            }
    }

    private companion object {
        const val MAX_TRENDS = 6

        /** 只留最近 4 次：再往前与「现在该不该加量」已经没关系。 */
        const val MAX_POINTS = 4
    }
}
