package io.github.kylin33.aifitgym.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档案来源标记。
 *
 * 这套东西服务的是一句话：**默认值不是用户说的话**。
 * 一个练了三年的人没动过设置，「水平」也是「新手」；把它当事实喂给模型，
 * 第一套计划就会排成入门动作。
 */
class AiProfileTest {

    @Test
    fun fresh_profile_has_no_filled_field() {
        val profile = AiProfile()

        assertEquals(0, profile.filledCount)
        // 有三个默认值（水平 / 每周次数 / 单次时长），但都不算他填的
        assertTrue(profile.confirmed.isEmpty())
    }

    @Test
    fun default_values_are_not_confirmed() {
        val profile = AiProfile()

        assertFalse(profile.isConfirmed(ProfileField.LEVEL))
        assertFalse(profile.isConfirmed(ProfileField.DAYS_PER_WEEK))
        // 但值本身是有内容的，所以「有值」和「他说过」必须分开判断
        assertTrue(profile.hasValue(ProfileField.LEVEL))
    }

    @Test
    fun all_critical_fields_missing_on_fresh_profile() {
        val missing = AiProfile().missingCritical()

        assertEquals(
            listOf(ProfileField.LIMITATIONS, ProfileField.EQUIPMENT, ProfileField.GOAL),
            missing,
        )
    }

    @Test
    fun filling_goal_and_equipment_removes_them_from_gap_list() {
        val profile = AiProfile(
            goal = "增肌",
            equipment = listOf("单杠"),
            confirmed = setOf(ProfileField.GOAL, ProfileField.EQUIPMENT),
        )

        assertEquals(listOf(ProfileField.LIMITATIONS), profile.missingCritical())
        assertEquals(2, profile.filledCount)
    }

    @Test
    fun cleared_selection_does_not_count_as_filled() {
        // 点开又取消：值没了，但「他表过态」这件事仍然记着
        val profile = AiProfile(
            experience = "",
            confirmed = setOf(ProfileField.EXPERIENCE),
        )

        assertEquals(0, profile.filledCount)
        assertFalse(profile.hasValue(ProfileField.EXPERIENCE))
    }

    @Test
    fun equipment_counts_as_value_even_when_it_says_none() {
        // 「无器械」是明确回答，不是空白 —— 有它就不该被 AI 再问一遍
        val profile = AiProfile(
            equipment = listOf(EQUIPMENT_NONE),
            confirmed = setOf(ProfileField.EQUIPMENT),
        )

        assertTrue(profile.hasValue(ProfileField.EQUIPMENT))
        assertFalse(ProfileField.EQUIPMENT in profile.missingCritical())
    }
}
