package io.github.kylin33.aifitgym.data.anatomy

import io.github.kylin33.aifitgym.data.local.ExerciseEntity

/**
 * 动作 → 肌群强度（0f…1f）的换算。
 *
 * 分两种用法：
 *  - [forExercise] 单个动作预览：主肌群 = 1.0（深色），副肌群 = 0.4（浅色），不做归一化，
 *    这样任何动作的配色含义都一样。
 *  - [forPlan] 整份计划预览：按组数累加，再除以最大值归一化到 0…1，
 *    所以最吃力的那块肌肉永远是最深的一档，"偏推力 / 全身燃脂" 一眼能看出来。
 *
 * 输出的键都是人体图 slug（chest / upper-back / quadriceps…），可以直接喂给 MuscleHeatmap。
 */
object MuscleScoring {

    /** 主发力肌群权重。 */
    const val PRIMARY_WEIGHT = 1f

    /** 辅助肌群权重，与 assets/anatomy/muscles.json 的 secondaryWeight 对齐。 */
    const val SECONDARY_WEIGHT = 0.4f

    /** 计划里的一条：做哪个动作、几组。 */
    data class PlannedExercise(
        val exercise: ExerciseEntity,
        val sets: Int,
    )

    fun forExercise(taxonomy: MuscleTaxonomy, exercise: ExerciseEntity): Map<String, Float> {
        val raw = LinkedHashMap<String, Float>()

        // 1) 主发力肌群。target 是 cardio 的（心肺系统）画不出来，退到身体部位权重；
        //    身体部位也是 cardio 的话再退到全身，让 "全身燃脂" 至少能铺一层浅色。
        val primary = taxonomy.slugOf(exercise.target)
        if (primary != null) {
            raw[primary] = PRIMARY_WEIGHT
        } else {
            val byPart = taxonomy.bodyPartWeights(exercise.bodyPart)
            when {
                byPart.isNotEmpty() -> byPart.forEach { (slug, weight) ->
                    raw[slug] = (raw[slug] ?: 0f) + weight * PRIMARY_WEIGHT
                }

                else -> taxonomy.fullBodyWeights().forEach { (slug, weight) ->
                    raw[slug] = (raw[slug] ?: 0f) + weight * PRIMARY_WEIGHT
                }
            }
        }

        // 2) 辅助肌群，逐个累加
        exercise.secondaryMuscles.forEach { name ->
            val slug = taxonomy.slugOf(name) ?: return@forEach
            raw[slug] = (raw[slug] ?: 0f) + SECONDARY_WEIGHT
        }

        if (raw.isEmpty()) return emptyMap()

        // 主肌群同时被列进副肌群时会到 1.4，压回 0…1，避免颜色算出界
        return raw.mapValues { (_, value) -> value.coerceIn(0f, 1f) }
    }

    /**
     * 整份计划的肌群负荷。
     *
     * @param entries 计划条目，组数当权重（组数为 0 时按 1 算，避免新建条目不计入）
     * @return 归一化后的强度，最大值为 1f；空计划返回空 map
     */
    fun forPlan(taxonomy: MuscleTaxonomy, entries: List<PlannedExercise>): Map<String, Float> {
        if (entries.isEmpty()) return emptyMap()

        val accumulated = HashMap<String, Float>()
        entries.forEach { entry ->
            val weight = entry.sets.coerceAtLeast(1).toFloat()
            forExercise(taxonomy, entry.exercise).forEach { (slug, score) ->
                accumulated[slug] = (accumulated[slug] ?: 0f) + score * weight
            }
        }
        if (accumulated.isEmpty()) return emptyMap()

        val max = accumulated.values.max()
        if (max <= 0f) return emptyMap()

        return accumulated.mapValues { (_, value) -> (value / max).coerceIn(0f, 1f) }
    }

    /** 按强度从高到低排，图例和 "今天练了哪" 文案都用这个顺序。 */
    fun ranked(scores: Map<String, Float>): List<Pair<String, Float>> =
        scores.entries
            .filter { it.value > 0f }
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    /** 强度文案，避免每个调用方自己写阈值。 */
    fun labelLevel(score: Float): String = when {
        score >= 0.85f -> "重点"
        score >= 0.6f -> "主要"
        score >= 0.35f -> "参与"
        score > 0f -> "轻微"
        else -> "未涉及"
    }
}
