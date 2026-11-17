package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.skill.SkillBranch

/**
 * 计划体检：**应用之前，程序先替用户看一眼**。
 *
 * 提示词能约束九成情况，但它是「请求」不是「保证」—— 模型这一轮忽略了
 * 「右膝半月板损伤」，用户看到的就是一套深蹲日，而且是 AI 排的，他会照着练。
 * 所以这里做三道便宜但有效的检查，结果以提示的形式挂在计划卡片上：
 *
 *  1. 伤病部位：档案写了膝，计划里全是大腿动作 → 点名。
 *  2. 训练量：比建议上限多出三成 → 说清楚多在哪。
 *  3. 恢复：某条流派今天刚练过，这套又排了一堆 → 提醒隔天。
 *
 * 只警告不拦截：用户可能就是要练，或者档案里的伤早就好了。
 * 但「看到了还继续」和「根本不知道」是两件事 —— 这是 App 和聊天机器人的区别。
 */
object PlanGuard {

    /** 伤病关键词 → 会被牵动的部位（对应 `exercises.bodyPartCn`）。 */
    private val PART_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "膝" to listOf("大腿", "小腿"),
        "半月板" to listOf("大腿", "小腿"),
        "髌" to listOf("大腿"),
        "腿" to listOf("大腿", "小腿", "全身"),
        "髋" to listOf("大腿", "全身"),
        "踝" to listOf("小腿", "大腿"),
        "足" to listOf("小腿"),
        "脚" to listOf("小腿"),
        "跟腱" to listOf("小腿"),
        "腰" to listOf("腰腹", "背部"),
        "下背" to listOf("背部", "腰腹"),
        "椎间盘" to listOf("背部", "腰腹"),
        "脊柱" to listOf("背部", "腰腹"),
        "背" to listOf("背部"),
        "肩" to listOf("肩部", "胸部"),
        "肩袖" to listOf("肩部", "胸部"),
        "肘" to listOf("上臂", "前臂"),
        "腕" to listOf("前臂"),
        "手" to listOf("前臂"),
        "颈" to listOf("颈部", "肩部"),
        "脖" to listOf("颈部"),
        "腹" to listOf("腰腹"),
    )

    /**
     * 体检。返回给用户看的警告，最多 3 条。
     *
     * @param limitations 档案里的伤病/禁忌自由文本
     * @param advice 本次该练多少（[VolumePolicy.advise]）
     * @param lastTrainedByBranch 每条流派最近一次是几天前
     */
    fun check(
        draft: PlanDraft,
        limitations: String,
        advice: VolumeAdvice,
        lastTrainedByBranch: Map<String, Int> = emptyMap(),
    ): List<String> {
        val warnings = mutableListOf<String>()
        injuryWarning(draft.items, limitations)?.let(warnings::add)
        volumeWarning(draft.items, advice)?.let(warnings::add)
        recoveryWarning(draft.items, lastTrainedByBranch)?.let(warnings::add)
        return warnings.take(MAX_WARNINGS)
    }

    /** 从自由文本里认出受伤部位。认不出就返回空集，不瞎猜。 */
    fun partsOf(limitations: String): Set<String> {
        val text = limitations.trim()
        if (text.isEmpty()) return emptySet()
        return PART_KEYWORDS
            .filter { (keyword, _) -> text.contains(keyword) }
            .flatMap { it.second }
            .toSet()
    }

    private fun injuryWarning(items: List<PlanDraftItem>, limitations: String): String? {
        val parts = partsOf(limitations)
        if (parts.isEmpty()) return null
        val risky = items.filter { it.exercise.bodyPartCn in parts }
        if (risky.isEmpty()) return null
        val names = risky.take(3).joinToString("、") { it.exercise.name }
        val more = if (risky.size > 3) " 等 ${risky.size} 个" else ""
        return "档案里写了「${limitations.trim().take(20)}」，这套里的 $names$more 会牵动" +
            risky.first().exercise.bodyPartCn + "，第一次做轻一点，有疼就停。"
    }

    private fun volumeWarning(items: List<PlanDraftItem>, advice: VolumeAdvice): String? {
        val total = items.sumOf { it.sets }
        if (total <= advice.maxSets) return null
        return "这套一共 $total 组，超过建议的 ${advice.minSets}~${advice.maxSets} 组。" +
            "要么先练一半，要么砍掉一两个动作 —— 一次堆太多，练到后面全是垃圾组。"
    }

    private fun recoveryWarning(
        items: List<PlanDraftItem>,
        lastTrainedByBranch: Map<String, Int>,
    ): String? {
        if (lastTrainedByBranch.isEmpty()) return null
        // 只看「今天」练过的：昨天练今天再练同一个部位，只要量不大是允许的
        val today = lastTrainedByBranch.filterValues { it == 0 }
        if (today.isEmpty()) return null
        val hits = SkillBranch.entries.filter { branch ->
            branch.key in today.keys && items.any { it.exercise.branch == branch.key }
        }
        if (hits.isEmpty()) return null
        val sets = items.filter { item -> hits.any { it.key == item.exercise.branch } }.sumOf { it.sets }
        if (sets < RECOVERY_WARN_SETS) return null
        return "${hits.joinToString("、") { it.label }}今天已经练过了，这套又排了 $sets 组。" +
            "同一个部位连着上量，恢复跟不上就是练一次退一点。"
    }

    /** 这条流派今天练过、今天又排到这么多组，才值得提一句。 */
    private const val RECOVERY_WARN_SETS = 9

    private const val MAX_WARNINGS = 3
}
