package io.github.kylin33.aifitgym.ui.workout

import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity
import io.github.kylin33.aifitgym.data.repo.WorkoutEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 休息画面该显示谁。
 *
 * 这条规则很容易在重构里被改回去：休息时主视觉一直画当前动作，用户做完最后一组
 * 盯着刚练完的动作歇 90 秒，时间到了才发现要换器械。所以单独锁住。
 */
class WorkoutSessionStateTest {

    private fun exercise(id: String, name: String) = ExerciseEntity(
        id = id,
        name = name,
        nameEn = name,
        bodyPart = "chest",
        bodyPartCn = "胸部",
        category = "calisthenics",
        categoryCn = "徒手体操",
        equipment = "body weight",
        equipmentCn = "徒手",
        target = "chest",
        targetCn = "胸大肌",
        secondaryMuscles = emptyList(),
        description = "",
        steps = emptyList(),
        stepsInChinese = false,
        image = null,
        searchKey = name,
        branch = "PUSH",
    )

    private fun entry(
        id: String,
        name: String,
        sets: Int = 3,
        reps: Int? = 10,
        seconds: Int? = null,
        backup: ExerciseEntity? = null,
    ) = WorkoutEntry(
        item = RoutineExerciseEntity(
            routineId = 1,
            exerciseId = id,
            orderIndex = 0,
            targetSets = sets,
            targetReps = reps,
            targetDurationSec = seconds,
            restDurationSec = 90,
        ),
        exercise = exercise(id, name),
        backup = backup,
    )

    private fun state(
        entries: List<WorkoutEntry>,
        phase: SessionPhase,
        entryIndex: Int = 0,
        setIndex: Int = 1,
        useBackup: Boolean = false,
    ) = WorkoutSessionState(
        phase = phase,
        entries = entries,
        entryIndex = entryIndex,
        setIndex = setIndex,
        useBackup = useBackup,
    )

    @Test
    fun rest_between_sets_keeps_current_exercise() {
        val s = state(
            entries = listOf(entry("a", "俯卧撑"), entry("b", "引体向上")),
            phase = SessionPhase.RESTING,
            setIndex = 2,
        )

        assertNull(s.upcomingEntry)
        assertFalse(s.restingIntoNext)
        assertEquals("俯卧撑", s.displayExercise?.name)
        assertEquals("俯卧撑 · 第 3/3 组", s.nextUpLabel)
    }

    @Test
    fun rest_after_last_set_shows_next_exercise() {
        val s = state(
            entries = listOf(entry("a", "俯卧撑"), entry("b", "引体向上", sets = 4)),
            phase = SessionPhase.RESTING,
            setIndex = 3,
        )

        assertEquals("引体向上", s.upcomingEntry?.exercise?.name)
        assertTrue(s.restingIntoNext)
        assertEquals("引体向上", s.displayExercise?.name)
        assertEquals("引体向上 · 第 1/4 组", s.nextUpLabel)
    }

    @Test
    fun last_exercise_never_previews_anything() {
        val s = state(
            entries = listOf(entry("a", "俯卧撑")),
            phase = SessionPhase.RESTING,
            setIndex = 3,
        )

        assertNull(s.upcomingEntry)
        assertFalse(s.restingIntoNext)
        // 没有下一个动作时停在当前动作上，界面靠「马上结束」提示收尾
        assertNull(s.nextUpLabel)
    }

    @Test
    fun resting_into_next_ignores_backup_switch() {
        val backup = exercise("a2", "跪姿俯卧撑")
        val s = state(
            entries = listOf(entry("a", "俯卧撑", backup = backup), entry("b", "引体向上")),
            phase = SessionPhase.RESTING,
            setIndex = 3,
            useBackup = true,
        )

        // 下一个动作还没开始，不该带上一项选择的平替状态
        assertEquals("引体向上", s.displayExercise?.name)
    }

    @Test
    fun backup_stays_visible_during_set_rest() {
        val backup = exercise("a2", "跪姿俯卧撑")
        val s = state(
            entries = listOf(entry("a", "俯卧撑", backup = backup), entry("b", "引体向上")),
            phase = SessionPhase.RESTING,
            setIndex = 1,
            useBackup = true,
        )

        assertEquals("跪姿俯卧撑", s.displayExercise?.name)
        assertEquals("跪姿俯卧撑 · 第 2/3 组", s.nextUpLabel)
    }

    @Test
    fun active_set_does_not_preview() {
        val s = state(
            entries = listOf(entry("a", "俯卧撑"), entry("b", "引体向上")),
            phase = SessionPhase.ACTIVE_SET,
            setIndex = 3,
        )

        assertNull(s.upcomingEntry)
        assertFalse(s.restingIntoNext)
        assertEquals("俯卧撑", s.displayExercise?.name)
    }
}
