package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.RoutineCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析层是整条链路里最容易出错、又最难在真机上排查的一段：
 * 模型多写一句「以下是计划」、少一个闭合围栏、把 category 写成中文，
 * 都会让用户看到一张空卡片或者干脆没有卡片。这里把已知的几种形态钉住。
 */
class PlanBlockParserTest {

    private val block = """
        {"name":"推日 · 上肢推力","reason":"拉力落后，先补推力容量","description":"",
         "category":"CALISTHENICS",
         "items":[
           {"exerciseId":"0653","sets":4,"reps":12,"restSec":90,"note":"顶峰停一秒"},
           {"exerciseId":"0463","sets":3,"durationSec":45}
         ]}
    """.trimIndent()

    @Test
    fun split_keeps_natural_language_and_extracts_plan() {
        val raw = "你上周只练了两次，先补回来。\n\n```json_routine\n$block\n```"
        val result = PlanBlockParser.split(raw)

        assertEquals("你上周只练了两次，先补回来。", result.text)
        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.first().contains("0653"))
    }

    @Test
    fun split_hides_block_that_is_still_streaming() {
        // 流式输出到一半：有围栏开头，没有闭合
        val raw = "好，我给你排一套：\n\n```json_routine\n{\"name\":\"推日\",\"items\":[{\"exer"
        val result = PlanBlockParser.split(raw)

        assertEquals("好，我给你排一套：", result.text)
        assertTrue(result.blocks.isEmpty())
    }

    @Test
    fun split_keeps_plain_code_block_in_text() {
        val raw = "动作要点：\n```\n手肘贴近身体\n```\n就这样。"
        val result = PlanBlockParser.split(raw)

        assertTrue(result.blocks.isEmpty())
        assertTrue(result.text.contains("手肘贴近身体"))
    }

    @Test
    fun parse_fills_defaults_for_missing_fields() {
        val parsed = PlanBlockParser.parse("""{"items":[{"exerciseId":"0653"}]}""")

        assertNotNull(parsed)
        val item = parsed!!.items.single()
        assertEquals("0653", item.exerciseId)
        assertEquals(3, item.sets)          // 默认 3 组
        assertNull(item.reps)               // 两个口径都没给
        assertNull(item.durationSec)
    }

    @Test
    fun parse_returns_null_on_garbage() {
        assertNull(PlanBlockParser.parse("not json at all"))
    }

    @Test
    fun category_maps_chinese_and_aliases() {
        assertEquals(RoutineCategory.CALISTHENICS, PlanBlockParser.categoryOf("CALISTHENICS"))
        assertEquals(RoutineCategory.HIIT_CARDIO, PlanBlockParser.categoryOf("HIIT_CARDIO"))
        assertEquals(RoutineCategory.HIIT_CARDIO, PlanBlockParser.categoryOf("有氧间歇"))
        assertEquals(RoutineCategory.CALISTHENICS, PlanBlockParser.categoryOf(""))
    }
}
