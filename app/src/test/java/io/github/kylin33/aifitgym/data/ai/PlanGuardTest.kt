package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划体检的单测。
 *
 * 这里的目标不是「判得准」，而是「别漏、别吵」：漏了会让有伤的人照着练，
 * 吵了用户会把警告当成装饰，两条都不如不做。
 */
class PlanGuardTest {

    private fun item(
        id: String,
        name: String,
        bodyPartCn: String,
        branch: String,
        sets: Int = 3,
    ) = PlanDraftItem(
        exercise = ExerciseEntity(
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
            secondaryMuscles = emptyList(),
            description = "",
            steps = emptyList(),
            stepsInChinese = false,
            image = null,
            searchKey = name,
            branch = branch,
        ),
        sets = sets,
        reps = 10,
        durationSec = null,
        restSec = 90,
        note = null,
    )

    private fun draft(vararg items: PlanDraftItem) = PlanDraft(
        name = "测试计划",
        description = "",
        reason = "",
        category = RoutineCategory.CALISTHENICS,
        items = items.toList(),
        dropped = 0,
    )

    private val generous = VolumeAdvice(9, 24, 3, 8, "测试")

    @Test
    fun injury_keywords_map_to_body_parts() {
        assertTrue("大腿" in PlanGuard.partsOf("右膝半月板损伤"))
        assertTrue("肩部" in PlanGuard.partsOf("左肩肩袖发炎"))
        assertTrue("腰腹" in PlanGuard.partsOf("腰不好"))
        assertTrue(PlanGuard.partsOf("").isEmpty())
    }

    @Test
    fun limitation_matching_plan_items_produce_one_warning() {
        val plan = draft(
            item("1", "深蹲", "大腿", "LEGS"),
            item("2", "箭步蹲", "大腿", "LEGS"),
            item("3", "俯卧撑", "胸部", "PUSH"),
        )

        val warnings = PlanGuard.check(plan, "右膝半月板损伤", generous)

        assertEquals(1, warnings.size)
        assertTrue(warnings.first().contains("深蹲"))
        // 不相关的动作不能一起点名，否则用户会以为整份计划都不能练
        assertFalse(warnings.first().contains("俯卧撑"))
    }

    @Test
    fun volume_over_budget_is_called_out() {
        val plan = draft(
            item("1", "俯卧撑", "胸部", "PUSH", sets = 5),
            item("2", "引体向上", "背部", "PULL", sets = 5),
            item("3", "深蹲", "大腿", "LEGS", sets = 5),
        )

        val warnings = PlanGuard.check(plan, "", VolumeAdvice(9, 12, 3, 5, ""))

        assertTrue(warnings.any { it.contains("15 组") && it.contains("12 组") })
    }

    @Test
    fun training_same_branch_today_is_a_recovery_flag() {
        val plan = draft(
            item("1", "俯卧撑", "胸部", "PUSH", sets = 5),
            item("2", "上斜俯卧撑", "胸部", "PUSH", sets = 4),
        )

        val warnings = PlanGuard.check(plan, "", generous, lastTrainedByBranch = mapOf("PUSH" to 0))

        assertTrue(warnings.any { it.contains("推力今天已经练过") })
    }

    @Test
    fun no_limitations_and_small_volume_stay_silent() {
        val plan = draft(item("1", "俯卧撑", "胸部", "PUSH", sets = 3))

        val warnings = PlanGuard.check(plan, "", generous, lastTrainedByBranch = mapOf("PUSH" to 3))

        assertTrue(warnings.isEmpty())
    }
}
