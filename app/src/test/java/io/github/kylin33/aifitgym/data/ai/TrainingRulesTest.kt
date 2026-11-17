package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 训练规则的单测。
 *
 * 这几条规则会**改写模型给的数字**（休息、组数、RIR），写歪了用户不会报错，
 * 只会觉得「这教练排的计划总觉得哪里不对劲」—— 正是最该测的一类代码。
 */
class TrainingRulesTest {

    private fun exercise(
        id: String = "0001",
        name: String = "测试动作",
        bodyPartCn: String = "胸部",
        branch: String = "PUSH",
        unlockType: String = "SETS_AND_REPS",
        skillLevel: Int = 0,
        secondary: List<String> = emptyList(),
    ) = ExerciseEntity(
        id = id,
        name = name,
        nameEn = name,
        bodyPart = "chest",
        bodyPartCn = bodyPartCn,
        category = "calisthenics",
        categoryCn = "徒手体操",
        equipment = "body weight",
        equipmentCn = "徒手",
        target = "chest",
        targetCn = "胸大肌",
        secondaryMuscles = secondary,
        description = "",
        steps = emptyList(),
        stepsInChinese = false,
        image = null,
        searchKey = name,
        branch = branch,
        skillLevel = skillLevel,
        unlockType = unlockType,
    )

    // ------------------------------------------------------------ 动作分类

    @Test
    fun compound_is_recognised_by_body_part() {
        // 引体只有两个辅助肌群，靠「参与肌群数」判会误判成孤立，所以大部位优先
        assertEquals(ExerciseClass.COMPOUND, ExerciseClass.of(exercise(name = "引体向上", bodyPartCn = "背部")))
        assertEquals(ExerciseClass.COMPOUND, ExerciseClass.of(exercise(name = "深蹲", bodyPartCn = "大腿")))
        assertEquals(ExerciseClass.ISOLATION, ExerciseClass.of(exercise(name = "弯举", bodyPartCn = "上臂")))
    }

    @Test
    fun timed_and_cardio_are_not_strength_movements() {
        assertEquals(
            ExerciseClass.STATIC,
            ExerciseClass.of(exercise(name = "平板支撑", bodyPartCn = "腰腹", unlockType = "SETS_AND_DURATION")),
        )
        assertEquals(
            ExerciseClass.CARDIO,
            ExerciseClass.of(exercise(name = "波比", bodyPartCn = "全身", branch = "HIIT")),
        )
    }

    @Test
    fun difficulty_follows_skill_level_first() {
        assertEquals(Difficulty.HIGH, Difficulty.of(exercise(skillLevel = 6)))
        assertEquals(Difficulty.MEDIUM, Difficulty.of(exercise(skillLevel = 3)))
        assertEquals(Difficulty.MEDIUM, Difficulty.of(exercise(bodyPartCn = "背部")))
        assertEquals(Difficulty.LOW, Difficulty.of(exercise(bodyPartCn = "上臂")))
    }

    // ------------------------------------------------------------ 休息

    @Test
    fun rest_floor_is_raised_for_compound_actions() {
        // 深蹲歇 30 秒不是「练得狠」，是下一组直接崩
        assertEquals(90, RestPolicy.coerce(30, ExerciseClass.COMPOUND, RoutineCategory.CALISTHENICS))
        assertEquals(45, RestPolicy.coerce(20, ExerciseClass.ISOLATION, RoutineCategory.CALISTHENICS))
        assertEquals(30, RestPolicy.coerce(5, ExerciseClass.STATIC, RoutineCategory.CALISTHENICS))
    }

    @Test
    fun rest_ceiling_is_240_but_long_rests_are_not_trimmed_to_band() {
        // 上限放宽到 240：接近极限的单组真的需要三分钟以上
        assertEquals(240, RestPolicy.coerce(600, ExerciseClass.COMPOUND, RoutineCategory.CALISTHENICS))
        assertEquals(150, RestPolicy.coerce(150, ExerciseClass.COMPOUND, RoutineCategory.CALISTHENICS))
        // 孤立动作要 120 秒不算错，只是效率低 —— 不拦
        assertEquals(120, RestPolicy.coerce(120, ExerciseClass.ISOLATION, RoutineCategory.CALISTHENICS))
    }

    @Test
    fun rest_defaults_when_model_says_nothing() {
        assertEquals(120, RestPolicy.coerce(null, ExerciseClass.COMPOUND, RoutineCategory.CALISTHENICS))
        assertEquals(45, RestPolicy.coerce(null, ExerciseClass.STATIC, RoutineCategory.CALISTHENICS))
        // 间歇训练的 10 秒休息不能被力量动作的下界抬走
        assertEquals(10, RestPolicy.coerce(10, ExerciseClass.CARDIO, RoutineCategory.HIIT_CARDIO))
    }

    // ------------------------------------------------------------ RIR

    @Test
    fun rir_defaults_leave_more_in_reserve_for_beginners() {
        assertEquals(3, RirPolicy.defaultOf(ExerciseClass.COMPOUND, beginner = true))
        assertEquals(2, RirPolicy.defaultOf(ExerciseClass.COMPOUND, beginner = false))
        assertEquals(1, RirPolicy.defaultOf(ExerciseClass.ISOLATION, beginner = false))
        assertNull(RirPolicy.defaultOf(ExerciseClass.STATIC, beginner = true))
    }

    @Test
    fun rir_is_clamped_and_dropped_for_timed_actions() {
        assertEquals(5, RirPolicy.coerce(9, ExerciseClass.COMPOUND, beginner = false))
        assertEquals(0, RirPolicy.coerce(-2, ExerciseClass.COMPOUND, beginner = false))
        assertNull(RirPolicy.coerce(2, ExerciseClass.STATIC, beginner = false))
        assertNull(RirPolicy.coerce(2, ExerciseClass.CARDIO, beginner = false))
    }

    @Test
    fun rir_text_reads_like_a_coach() {
        assertEquals("留 2 次余力", RirPolicy.describe(2))
        assertEquals("做到力竭", RirPolicy.describe(0))
    }

    // ------------------------------------------------------------ 训练量

    private fun stats(sessions: Int, totalSets: Int, daysSinceLast: Int?) = TrainingStats(
        last7 = WindowStats(7, trainingDays = sessions, sessions = sessions, totalSets = totalSets),
        prev7 = WindowStats(7),
        last30 = WindowStats(30, trainingDays = sessions, sessions = sessions, totalSets = totalSets),
        daysSinceLast = daysSinceLast,
    )

    @Test
    fun no_history_starts_from_minimum_effective_dose() {
        val advice = VolumePolicy.advise(stats(0, 0, null), beginner = true)

        assertEquals(9, advice.minSets)
        assertEquals(12, advice.maxSets)
        assertTrue(advice.reason.contains("没有可参考"))
    }

    @Test
    fun volume_follows_recent_average_instead_of_profile() {
        // 每场 20 组的人，排 12 组是浪费他的时间；上限给到 24（1.25 倍封顶）
        val advice = VolumePolicy.advise(stats(3, 60, 1), beginner = false)

        assertEquals(24, advice.maxSets)
        assertEquals(16, advice.minSets)
    }

    @Test
    fun beginners_are_capped_lower() {
        val advice = VolumePolicy.advise(stats(3, 60, 1), beginner = true)

        assertEquals(16, advice.maxSets)
        assertTrue(advice.maxExercises <= 5)
    }

    @Test
    fun returning_after_two_weeks_restarts_at_seventy_percent() {
        val fresh = VolumePolicy.advise(stats(3, 60, 1), beginner = false)
        val restart = VolumePolicy.advise(stats(3, 60, 30), beginner = false)

        assertTrue(restart.maxSets < fresh.maxSets)
        assertTrue(restart.reason.contains("重启"))
    }
}
