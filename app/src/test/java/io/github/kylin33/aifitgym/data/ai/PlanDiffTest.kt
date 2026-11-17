package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineCategory
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 改动清单是用户点「应用」之前唯一的判断依据，也是「应用之后计划长什么样」的预演。
 * 它算错了不会崩，只会让用户以为 AI 改了个寂寞（或改了一大堆），所以钉死。
 */
class PlanDiffTest {

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
    )

    private fun item(id: String, sets: Int, reps: Int?, seconds: Int? = null, rest: Int = 90) =
        RoutineExerciseEntity(
            routineId = 1,
            exerciseId = id,
            orderIndex = 0,
            targetSets = sets,
            targetReps = reps,
            targetDurationSec = seconds,
            restDurationSec = rest,
        )

    private fun draft(vararg items: PlanDraftItem) = PlanDraft(
        name = "推日",
        description = "",
        reason = "",
        category = RoutineCategory.CALISTHENICS,
        items = items.toList(),
        dropped = 0,
    )

    @Test
    fun marks_new_changed_kept_and_removed() {
        val old = listOf(
            item("A", sets = 3, reps = 10),
            item("B", sets = 3, reps = 10),
            item("C", sets = 3, reps = 10),
        )
        val new = draft(
            // A 原样保留
            PlanDraftItem(exercise("A", "俯卧撑"), sets = 3, reps = 10, durationSec = null, restSec = 90, note = null),
            // B 加量
            PlanDraftItem(exercise("B", "深蹲"), sets = 4, reps = 12, durationSec = null, restSec = 90, note = null),
            // D 新增
            PlanDraftItem(exercise("D", "平板支撑"), sets = 3, reps = null, durationSec = 45, restSec = 60, note = null),
        )

        val changes = PlanDiff.compare(old, new) { id -> "动作$id" }
        val byKind = changes.groupBy { it.kind }
        assertEquals(1, byKind[ProposalChangeKind.KEEP]?.size)
        assertEquals(1, byKind[ProposalChangeKind.CHANGE]?.size)
        assertEquals(1, byKind[ProposalChangeKind.ADD]?.size)
        assertEquals(1, byKind[ProposalChangeKind.REMOVE]?.size)

        // 差异描述要能一眼看出「从多少到多少」
        val changed = byKind.getValue(ProposalChangeKind.CHANGE).single()
        assertEquals("深蹲", changed.title)
        assertTrue(changed.detail.contains("3 组 × 10 次"))
        assertTrue(changed.detail.contains("4 组 × 12 次"))

        // 被移除的动作要显示名字，而不是 id
        assertEquals("动作C", byKind.getValue(ProposalChangeKind.REMOVE).single().title)
    }

    @Test
    fun rest_change_alone_counts_as_change() {
        val old = listOf(item("A", sets = 3, reps = 10, rest = 90))
        val new = draft(
            PlanDraftItem(exercise("A", "俯卧撑"), sets = 3, reps = 10, durationSec = null, restSec = 60, note = null),
        )

        val change = PlanDiff.compare(old, new) { it }.single()
        assertEquals(ProposalChangeKind.CHANGE, change.kind)
        assertTrue(change.detail.contains("休息 90s → 60s"))
    }

    @Test
    fun identical_plan_has_no_effective_change() {
        val old = listOf(item("A", sets = 3, reps = 10))
        val new = draft(
            PlanDraftItem(exercise("A", "俯卧撑"), sets = 3, reps = 10, durationSec = null, restSec = 90, note = null),
        )

        val proposal = PlanProposal(draft = new, target = null, targetName = "", changes = PlanDiff.compare(old, new) { it })
        assertEquals(0, proposal.effectiveCount)
    }

    @Test
    fun rir_change_alone_counts_as_change() {
        // 组数次数都没动、只把「留 3 次」改成「留 1 次」，这是实打实的加量
        val old = listOf(item("A", sets = 3, reps = 10).copy(targetRir = 3))
        val new = draft(
            PlanDraftItem(
                exercise = exercise("A", "俯卧撑"),
                sets = 3,
                reps = 10,
                durationSec = null,
                restSec = 90,
                note = null,
                rir = 1,
            ),
        )

        val change = PlanDiff.compare(old, new) { it }.single()
        assertEquals(ProposalChangeKind.CHANGE, change.kind)
        assertTrue(change.detail.contains("留 3 次余力"))
        assertTrue(change.detail.contains("留 1 次余力"))
    }

    @Test
    fun timed_and_reps_are_formatted_differently() {
        assertEquals("3 组 × 12 次", PlanDiff.targetText(3, 12, null))
        assertEquals("3 组 × 45 秒", PlanDiff.targetText(3, null, 45))
        assertEquals("3 组 × 按达标次数", PlanDiff.targetText(3, null, null))
        assertEquals("3 组 × 12 次 · 留 2 次余力", PlanDiff.targetText(3, 12, null, 2))
    }
}
