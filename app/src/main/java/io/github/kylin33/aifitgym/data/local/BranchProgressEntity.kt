package io.github.kylin33.aifitgym.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 六大流派的熟练度，固定 6 行。
 *
 * 这是整个「双轨升级」的账本：
 *  - [currentTier] 当前解锁到第几阶（1~8）。它同时是「正在攻克的主干动作」的指针 ——
 *    主干动作的 [ExerciseEntity.skillLevel] 等于它就参与考级判定。
 *  - [totalXp] 练该流派下**任意**动作累积的经验（每组 10 点），终身累计；
 *  - [xpSpent] 已经花掉的 XP。单独存一列而不是直接扣 [totalXp]，
 *    是为了让「累计经验」这个展示值不会因为突破而倒退。
 *  - [discountPercent] 熟练度突破累计降了多少要求（0/20/40/60）。
 *    只在当前阶有效，晋升一阶就清零。
 *
 * 不设外键：流派是代码里的枚举，不是表。
 *
 * 加 [Serializable] 是为了备份导出：这一行就是「用户的进度」本身，字段和表结构一致，
 * 单独再写一份 DTO 只会多一处需要跟着改的地方。
 */
@Serializable
@Entity(tableName = "branch_progress")
data class BranchProgressEntity(
    /** "PUSH" / "PULL" / "LEGS" / "CORE" / "HIIT" / "CARDIO"。 */
    @PrimaryKey val branch: String,
    /** 当前正在攻克的阶，1~8；8 且已通关时不会再涨。 */
    val currentTier: Int = 1,
    /** 终生累计经验。 */
    val totalXp: Int = 0,
    /** 已经花在「熟练度突破」上的经验。 */
    val xpSpent: Int = 0,
    /** 当前阶的通关要求打折幅度，0/20/40/60。 */
    val discountPercent: Int = 0,
    val updatedAt: Long = 0,
) {
    /** 现在还能用来突破的经验。 */
    val availableXp: Int get() = (totalXp - xpSpent).coerceAtLeast(0)
}
