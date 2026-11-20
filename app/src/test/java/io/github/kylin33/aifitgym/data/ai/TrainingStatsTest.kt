package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.SessionPerformance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 统计口径的单测。
 *
 * 这些数字会直接进提示词、影响模型排多少组，算错了没人看得出来 ——
 * 「上周 36 组」和「上周 30 组」在模型嘴里都是一句「练得不错」。
 */
class TrainingStatsTest {

    private val day = TrainingStatsMath.DAY_MS

    /** 今天中午 12 点：拿它当基准，避免测试在午夜前后因为跨天偶发失败。 */
    private val noon: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun perf(
        sessionId: Long,
        exerciseId: String,
        at: Long,
        sets: Int,
        reps: Int,
        seconds: Int = 0,
        trackedSets: Int = 0,
        hitSets: Int = 0,
        overSets: Int = 0,
        restSets: Int = 0,
        restPlannedSum: Int = 0,
        restActualSum: Int = 0,
    ) = SessionPerformance(
        sessionId = sessionId,
        exerciseId = exerciseId,
        startedAt = at,
        sets = sets,
        bestReps = reps,
        bestSeconds = seconds,
        trackedSets = trackedSets,
        hitSets = hitSets,
        overSets = overSets,
        restSets = restSets,
        restPlannedSum = restPlannedSum,
        restActualSum = restActualSum,
    )

    // ------------------------------------------------------------ 时间窗

    @Test
    fun window_counts_same_day_twice_as_one_training_day() {
        val rows = listOf(
            perf(1, "A", noon - 4 * 3600_000L, sets = 3, reps = 10),
            perf(2, "B", noon - 3 * 3600_000L, sets = 4, reps = 12),
            perf(3, "A", noon - day, sets = 3, reps = 9),
        )

        val w = TrainingStatsMath.window(
            performances = rows,
            branchOf = mapOf("A" to "PUSH", "B" to "PULL"),
            since = noon - 7 * day,
            until = noon + day,
            days = 7,
        )

        assertEquals(2, w.trainingDays) // 今天两场算一天 + 昨天
        assertEquals(3, w.sessions)
        assertEquals(10, w.totalSets)
        assertEquals(mapOf("PUSH" to 6, "PULL" to 4), w.setsByBranch)
    }

    @Test
    fun window_excludes_rows_outside_bounds() {
        val rows = listOf(
            perf(1, "A", noon - 8 * day, sets = 5, reps = 10),
            perf(2, "A", noon, sets = 2, reps = 10),
        )

        val w = TrainingStatsMath.window(rows, mapOf("A" to "PUSH"), noon - 7 * day, noon + day, 7)

        assertEquals(2, w.totalSets)
        assertEquals(1, w.sessions)
    }

    @Test
    fun window_reports_branches_with_zero_sets() {
        val rows = listOf(perf(1, "A", noon, sets = 4, reps = 10))
        val w = TrainingStatsMath.window(rows, mapOf("A" to "PUSH"), noon - 7 * day, noon + day, 7)

        val untrained = w.untrainedBranches().map { it.key }
        assertFalse("PUSH" in untrained)
        assertTrue("PULL" in untrained)
        assertTrue("LEGS" in untrained)
    }

    @Test
    fun empty_window_has_no_untrained_list() {
        // 完全没练过时不该报「六条流派都欠练」，那会让模型以为他练得很偏
        val w = TrainingStatsMath.window(emptyList(), emptyMap(), noon - day, noon, 7)
        assertTrue(w.isEmpty)
        assertTrue(w.untrainedBranches().isEmpty())
    }

    // ------------------------------------------------------------ 趋势

    @Test
    fun trend_needs_three_points() {
        assertEquals(TrendKind.INSUFFICIENT, TrainingStatsMath.trendOf(emptyList()))
        assertEquals(TrendKind.INSUFFICIENT, TrainingStatsMath.trendOf(listOf(10)))
        assertEquals(TrendKind.INSUFFICIENT, TrainingStatsMath.trendOf(listOf(12, 9)))
    }

    @Test
    fun trend_declines_only_after_two_consecutive_drops() {
        // 单次掉下来只是那天状态不好，不能判成退步
        assertEquals(TrendKind.STABLE, TrainingStatsMath.trendOf(listOf(12, 12, 9)))
        assertEquals(TrendKind.DECLINING, TrainingStatsMath.trendOf(listOf(12, 11, 9)))
    }

    @Test
    fun trend_improves_only_after_two_consecutive_gains() {
        assertEquals(TrendKind.STABLE, TrainingStatsMath.trendOf(listOf(9, 9, 12)))
        assertEquals(TrendKind.IMPROVING, TrainingStatsMath.trendOf(listOf(9, 11, 12)))
    }

    @Test
    fun trend_flat_when_oscillating() {
        assertEquals(TrendKind.STABLE, TrainingStatsMath.trendOf(listOf(12, 9, 12, 9)))
    }

    // ------------------------------------------------------------ 恢复

    @Test
    fun last_trained_days_only_keeps_recent_branches() {
        val rows = listOf(
            perf(1, "A", noon - 2 * 3600_000L, sets = 3, reps = 10),
            perf(2, "B", noon - 3 * day, sets = 3, reps = 10),
            perf(3, "C", noon - 20 * day, sets = 3, reps = 10),
        )

        val days = TrainingStatsMath.lastTrainedDays(
            rows,
            branchOf = mapOf("A" to "PUSH", "B" to "PULL", "C" to "LEGS"),
            now = noon,
        )

        assertEquals(0, days["PUSH"])
        assertEquals(3, days["PULL"])
        // 20 天前的不进简报：它跟「今天还能不能练腿」已经没关系了
        assertFalse("LEGS" in days)
    }

    // ------------------------------------------------------------ 渲染

    @Test
    fun prompt_text_states_there_is_no_data_instead_of_inventing() {
        val stats = TrainingStats(
            last7 = WindowStats(days = 7),
            prev7 = WindowStats(days = 7),
            last30 = WindowStats(days = 30),
            daysSinceLast = null,
        )

        val text = stats.toPromptText()

        assertTrue(text.contains("没有训练记录"))
        assertFalse(text.contains("近 7 天"))
    }

    @Test
    fun prompt_text_reports_periods_and_trends() {
        val stats = TrainingStats(
            last7 = WindowStats(7, trainingDays = 3, sessions = 4, totalSets = 36, setsByBranch = mapOf("PUSH" to 18, "PULL" to 6)),
            prev7 = WindowStats(7, trainingDays = 2, sessions = 3, totalSets = 20, setsByBranch = mapOf("PUSH" to 20)),
            last30 = WindowStats(30, trainingDays = 9, sessions = 12, totalSets = 118, setsByBranch = mapOf("PUSH" to 52, "PULL" to 21)),
            trends = listOf(
                ExerciseTrend("0001", "标准俯卧撑", listOf(12, 11, 9), isTimed = false, kind = TrendKind.DECLINING),
            ),
            daysSinceLast = 2,
        )

        val text = stats.toPromptText()

        assertTrue(text.contains("4 场 / 3 个训练日 / 36 组"))
        assertTrue(text.contains("推力 18"))
        assertTrue(text.contains("（本周 +16 组 / +80%）"))
        assertTrue(text.contains("标准俯卧撑 12→11→9 次 · 连续两次下降"))
        assertTrue(text.contains("2 天前"))
        // 30 天里 0 组的力量流派要点名，否则模型看不出偏科
        assertTrue(text.contains("核心"))
    }

    // ------------------------------------------------------------ 达标与休息

    @Test
    fun progress_needs_at_least_three_tracked_sets() {
        val rows = listOf(
            // 老记录：没有目标快照，trackedSets 是 0
            perf(1, "A", noon - day, sets = 2, reps = 12, trackedSets = 2, hitSets = 2),
            perf(2, "B", noon - day, sets = 3, reps = 10, trackedSets = 3, hitSets = 3),
        )

        val result = TrainingStatsMath.progressOf(rows, mapOf("A" to "俯卧撑", "B" to "深蹲"))

        assertEquals(1, result.size)
        assertEquals("深蹲", result[0].name)
    }

    @Test
    fun progress_sums_across_sessions_and_keeps_rest_ratio() {
        val rows = listOf(
            perf(
                1, "A", noon - 3 * day, sets = 3, reps = 12,
                trackedSets = 3, hitSets = 3, overSets = 1,
                restSets = 3, restPlannedSum = 270, restActualSum = 150,
            ),
            perf(
                2, "A", noon - day, sets = 3, reps = 13,
                trackedSets = 3, hitSets = 3, overSets = 2,
                restSets = 3, restPlannedSum = 270, restActualSum = 156,
            ),
        )

        val p = TrainingStatsMath.progressOf(rows, mapOf("A" to "俯卧撑")).single()

        assertEquals(6, p.trackedSets)
        assertEquals(6, p.hitSets)
        assertEquals(3, p.overSets)
        // 只做除法、不四舍五入：报「歇了 51 秒」比「51.2 秒」更像人话，误差也无所谓
        assertEquals("俯卧撑 达标 6/6 · 超额 3 组 · 平均歇 51 秒（计划 90）", p.describe())
    }

    @Test
    fun progress_omits_rest_part_when_nothing_recorded() {
        val rows = listOf(perf(1, "A", noon, sets = 3, reps = 10, trackedSets = 3, hitSets = 2))

        val p = TrainingStatsMath.progressOf(rows, mapOf("A" to "俯卧撑")).single()

        assertEquals("俯卧撑 达标 2/3", p.describe())
    }

    @Test
    fun prompt_text_lists_progress_below_trends() {
        val stats = TrainingStats(
            last7 = WindowStats(7, trainingDays = 2, sessions = 2, totalSets = 12, setsByBranch = mapOf("PUSH" to 12)),
            prev7 = WindowStats(7),
            last30 = WindowStats(30, trainingDays = 2, sessions = 2, totalSets = 12, setsByBranch = mapOf("PUSH" to 12)),
            daysSinceLast = 1,
            progress = listOf(
                ExerciseProgress(
                    exerciseId = "0001",
                    name = "标准俯卧撑",
                    trackedSets = 12,
                    hitSets = 12,
                    overSets = 3,
                    restSets = 9,
                    restPlannedSum = 810,
                    restActualSum = 468,
                ),
            ),
        )

        val text = stats.toPromptText()

        assertTrue(text.contains("达标与休息（近 30 天"))
        assertTrue(text.contains("标准俯卧撑 达标 12/12 · 超额 3 组 · 平均歇 52 秒（计划 90）"))
    }
}
