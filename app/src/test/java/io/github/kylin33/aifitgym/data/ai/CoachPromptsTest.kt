package io.github.kylin33.aifitgym.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词的四层结构与模块加载。
 *
 * 这里不测「提示词写得好不好」——那测不出来。测的是结构约束：
 * 四层的顺序、核心层不受模块影响（否则「加跑步不改核心」就是空话）、
 * 模块能被关键词选中且不会一次全塞进去。
 */
class CoachPromptsTest {

    private fun system(modules: List<TrainingModule> = emptyList(), review: Boolean = false) =
        CoachPrompts.system(
            poolText = "0001|俯卧撑|计次|PUSH|低",
            poolSize = 1,
            review = review,
            routineNames = listOf("推日"),
            modules = modules,
        )

    // ------------------------------------------------------------ 四层结构

    @Test
    fun four_layers_appear_in_order() {
        val text = system()
        val l1 = text.indexOf("# 一、角色与行为规则")
        val l3 = text.indexOf("# 三、训练决策规则")
        val l4 = text.indexOf("# 四、输出协议")
        assertTrue("缺第一层", l1 >= 0)
        assertTrue("缺第三层", l3 > l1)
        assertTrue("缺第四层", l4 > l3)
        assertTrue("第二层在独立消息里", CoachPrompts.context("x").startsWith("# 二、用户动态上下文"))
    }

    @Test
    fun safety_boundary_lives_in_layer_one_and_rules_in_layer_three() {
        val text = system()
        val l1End = text.indexOf("# 三、训练决策规则")
        val head = text.substring(0, l1End)
        // 底线是「不需要判断就执行」的，放第一层；需要看情况区分的放 3.11
        assertTrue(head.contains("## 安全边界"))
        assertTrue(head.contains("不做医疗诊断"))
        assertTrue(text.substring(l1End).contains("3.11 安全规则"))
        assertTrue(text.substring(l1End).contains("延迟性酸痛（DOMS）"))
    }

    // ------------------------------------------------------- 核心层不被模块污染

    @Test
    fun decisions_layer_is_identical_with_or_without_modules() {
        fun decisions(t: String) = t.substring(
            t.indexOf("# 三、训练决策规则"),
            t.indexOf("## 3.12 专项模块"),
        )
        assertEquals(decisions(system()), decisions(system(listOf(TrainingModule.RUNNING, TrainingModule.HIIT))))
    }

    @Test
    fun protocol_layer_is_identical_with_or_without_modules() {
        fun protocol(t: String) = t.substring(t.indexOf("# 四、输出协议"))
        assertEquals(protocol(system()), protocol(system(listOf(TrainingModule.CORE))))
    }

    @Test
    fun module_index_is_always_there_but_body_only_when_loaded() {
        val idle = system()
        TrainingModule.entries.forEach { module ->
            assertTrue("索引里缺 ${module.id}", idle.contains("${module.id} ${module.label}"))
            assertFalse("没加载却带了正文：${module.id}", idle.contains(module.body.lines().first()))
        }
        val loaded = system(listOf(TrainingModule.RUNNING))
        assertTrue(loaded.contains("本轮已加载：跑步"))
        assertTrue(loaded.contains(TrainingModule.RUNNING.body))
    }

    @Test
    fun no_line_keeps_source_indentation() {
        // 动作池是多行文本，塞进 trimIndent 的字符串里会把整段协议带歪。
        // 这类排版错误肉眼很难发现，但白烧 token，还会让模型以为缩进分组有意义。
        system(listOf(TrainingModule.RUNNING, TrainingModule.HIIT)).lines().forEach { line ->
            assertFalse("缩进残留：$line", line.startsWith("    "))
        }
    }

    // ------------------------------------------------------------ 模块选择

    @Test
    fun selector_matches_the_topic() {
        assertEquals(listOf(TrainingModule.RUNNING), TrainingModuleSelector.pick("我想开始跑步，一周跑三次"))
        assertEquals(listOf(TrainingModule.HIIT), TrainingModuleSelector.pick("给我一套HIIT，20分钟"))
        assertEquals(listOf(TrainingModule.CORE), TrainingModuleSelector.pick("腹肌怎么练才有效"))
        assertEquals(listOf(TrainingModule.HYPERTROPHY), TrainingModuleSelector.pick("想变壮一点，围度太小"))
        assertEquals(listOf(TrainingModule.FAT_LOSS), TrainingModuleSelector.pick("减脂期训练怎么调整"))
        assertEquals(listOf(TrainingModule.PERIODIZATION), TrainingModuleSelector.pick("长期计划怎么排，老是卡住"))
    }

    @Test
    fun blank_or_unrelated_message_loads_nothing() {
        assertTrue(TrainingModuleSelector.pick("").isEmpty())
        assertTrue(TrainingModuleSelector.pick("   ").isEmpty())
        assertTrue(TrainingModuleSelector.pick("明天练什么").isEmpty())
    }

    @Test
    fun at_most_two_modules_are_loaded() {
        val picked = TrainingModuleSelector.pick("减脂期想增肌，加上跑步和核心，长期周期怎么排")
        assertEquals(TrainingModuleSelector.MAX_ACTIVE, picked.size)
        assertEquals(picked, TrainingModuleSelector.pick("减脂期想增肌，加上跑步和核心，长期周期怎么排"))
    }

    @Test
    fun selector_is_case_insensitive() {
        assertEquals(listOf(TrainingModule.HIIT), TrainingModuleSelector.pick("HIIT 和 Tabata 有什么区别"))
        assertEquals(listOf(TrainingModule.HIIT), TrainingModuleSelector.pick("hiit 和 tabata 有什么区别"))
    }

    // ------------------------------------------------------------ 模块自身约束

    @Test
    fun every_module_is_usable() {
        val ids = TrainingModule.entries.map { it.id }
        assertEquals("id 必须唯一", ids.size, ids.distinct().size)
        TrainingModule.entries.forEach { module ->
            assertTrue("${module.id} 缺中文名", module.label.isNotBlank())
            assertTrue("${module.id} 缺一句话纪律", module.core.length in 10..80)
            assertTrue("${module.id} 缺关键词", module.keywords.isNotEmpty())
            assertTrue("${module.id} 关键词不该大写或带空格", module.keywords.all { it == it.trim() })
            assertTrue("${module.id} 缺正文", module.body.length in 200..1600)
            assertTrue("${module.id} 正文标题对不上", module.body.startsWith("### ${module.id}"))
            assertEquals(module, TrainingModule.of(module.id))
        }
    }

    // ------------------------------------------------------------ 两种模式

    @Test
    fun review_mode_swaps_the_plan_protocol() {
        val chat = system()
        assertTrue(chat.contains("## 4.3 什么时候输出计划块"))
        assertTrue(chat.contains("json_routine"))

        val review = system(review = true)
        assertTrue(review.contains("## 4.3 复盘模式"))
        assertTrue(review.contains("## 4.4 修改计划的输出协议"))
        assertTrue("复盘也要能新建一套", review.contains("json_plan_update"))
    }
}
