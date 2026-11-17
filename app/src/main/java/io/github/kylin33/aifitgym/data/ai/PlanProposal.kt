package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.RoutineEntity
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity

/** 一条改动的性质。UI 用颜色区分，不用再读文字判断。 */
enum class ProposalChangeKind { ADD, REMOVE, CHANGE, KEEP }

/**
 * 改动清单里的一行。
 *
 * [detail] 是给用户看的差异描述（「3 组 × 10 次 → 4 组 × 12 次」），
 * 不是给机器读的 —— 用户点「应用」之前唯一能判断的依据就是这几行。
 */
data class ProposalChange(
    val kind: ProposalChangeKind,
    val title: String,
    val detail: String,
)

/**
 * 教练给出的计划改动建议。
 *
 * [target] 为 null 表示新建一套（模型填了 `targetRoutine:""`，或者它在复盘里
 * 直接吐了一份普通计划）。[changes] 由本地对比算出，**不采信模型的自述** ——
 * 让它自己总结改了什么，十次里有两三次会漏。
 */
data class PlanProposal(
    val draft: PlanDraft,
    val target: RoutineEntity?,
    /** 模型给的目标计划名，没匹配上时用来提示。 */
    val targetName: String,
    val changes: List<ProposalChange>,
) {
    val isNew: Boolean get() = target == null

    /** 真正会让计划变样的行数，0 表示模型只是想「保持原样」。 */
    val effectiveCount: Int get() = changes.count { it.kind != ProposalChangeKind.KEEP }
}

/**
 * 计划差异。
 *
 * 做成纯函数（不碰数据库）是为了能直接单测：这段逻辑决定了用户看到什么、
 * 以及「应用」之后计划长什么样，写错了比提示词写错更难发现。
 */
object PlanDiff {

    fun compare(
        old: List<RoutineExerciseEntity>,
        draft: PlanDraft,
        nameOf: (String) -> String,
    ): List<ProposalChange> {
        val oldById = old.associateBy { it.exerciseId }
        val newIds = draft.items.map { it.exercise.id }.toSet()
        val result = mutableListOf<ProposalChange>()

        draft.items.forEach { item ->
            val before = oldById[item.exercise.id]
            if (before == null) {
                result += ProposalChange(
                    kind = ProposalChangeKind.ADD,
                    title = item.exercise.name,
                    detail = "新增 · ${targetText(item.sets, item.reps, item.durationSec)}",
                )
            } else {
                val from = targetText(before.targetSets, before.targetReps, before.targetDurationSec)
                val to = targetText(item.sets, item.reps, item.durationSec)
                val kind = if (from == to && before.restDurationSec == item.restSec) {
                    ProposalChangeKind.KEEP
                } else {
                    ProposalChangeKind.CHANGE
                }
                val restNote = if (before.restDurationSec != item.restSec) {
                    "，休息 ${before.restDurationSec}s → ${item.restSec}s"
                } else {
                    ""
                }
                result += ProposalChange(
                    kind = kind,
                    title = item.exercise.name,
                    detail = if (kind == ProposalChangeKind.KEEP) {
                        "不变 · $to"
                    } else {
                        "$from → $to$restNote"
                    },
                )
            }
        }

        old.filter { it.exerciseId !in newIds }.forEach { removed ->
            result += ProposalChange(
                kind = ProposalChangeKind.REMOVE,
                title = nameOf(removed.exerciseId),
                detail = "移除",
            )
        }
        return result
    }

    /** 3 组 × 12 次 / 3 组 × 45 秒。 */
    fun targetText(sets: Int, reps: Int?, durationSec: Int?): String {
        val unit = when {
            durationSec != null && durationSec > 0 -> "${durationSec} 秒"
            reps != null && reps > 0 -> "$reps 次"
            else -> "按达标次数"
        }
        return "$sets 组 × $unit"
    }
}
