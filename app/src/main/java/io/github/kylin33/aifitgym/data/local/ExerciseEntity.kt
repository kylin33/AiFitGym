package io.github.kylin33.aifitgym.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 内置动作库里的一条动作。
 *
 * 字段基本对应 `assets/exercises.json` 里的一条记录，额外把分类/部位的英文枚举和中文标签
 * 各存一份，列表页就不用再做一次映射，也方便按中文直接搜索。
 *
 * [searchKey] 是预拼好的小写搜索串（中英文名 + 部位 + 目标肌群 + 副肌群），配合 LIKE 做模糊搜索。
 *
 * ## 打标字段（由 `tools/tag_exercises.py` 生成，不手改）
 * `branch` / `skillLevel` / `isBodyweightFriendly` 三个字段把 2303 个动作和 6 条技能树连起来：
 *  - [branch] 决定它属于哪条流派，训练结算时按这个给流派加经验；
 *  - [skillLevel] 为 0 表示「自由训练池」，1~8 表示该流派的第 N 阶主干动作 ——
 *    所以「流派 + 阶」能唯一定位一个节点，不需要额外的节点表；
 *  - [isBodyweightFriendly] 用于列表默认过滤掉健身房器械。
 */
@Entity(
    tableName = "exercises",
    indices = [
        Index("bodyPart"),
        Index("category"),
        Index("name"),
        Index(value = ["branch", "skillLevel"]),
    ],
)
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameEn: String,
    val bodyPart: String,
    val bodyPartCn: String,
    val category: String,
    val categoryCn: String,
    val equipment: String,
    val equipmentCn: String,
    /** 主发力肌群（英文枚举，如 spine / chest）。 */
    val target: String,
    /** 主发力肌群中文名，如「竖脊肌」。 */
    val targetCn: String,
    /** 辅助肌群中文名，JSON 里已经是中文，例如 ["臀肌", "腘绳肌"]。 */
    val secondaryMuscles: List<String>,
    val description: String,
    val steps: List<String>,
    val stepsInChinese: Boolean,
    /** 相对 assets 的演示图路径，如 `gifs/0001.webp`；null 表示上游没有图。 */
    val image: String?,
    val searchKey: String,

    /** 所属流派：PUSH / PULL / LEGS / CORE / HIIT / CARDIO。 */
    val branch: String = "CORE",
    /** 0 = 自由训练池；1~8 = 该流派第 N 阶的主干动作。 */
    val skillLevel: Int = 0,
    /** 达标口径：SETS_AND_REPS / SETS_AND_DURATION / TOTAL_VOLUME_REPS / CONTINUOUS_DURATION。 */
    val unlockType: String = "SETS_AND_REPS",
    val unlockSets: Int = 3,
    /** 目标值：计次是次数、计时是秒数；自由池动作为 0。 */
    val unlockValue: Int = 0,
    /** UI 直接展示的达标文案，如「3 组 × 每侧 8 次」。 */
    val unlockText: String = "",
    /** 该主干动作是否已通关。派生字段：动作库重灌时由 [ExerciseDao.replaceAll] 恢复。 */
    val isMastered: Boolean = false,
    /** 徒手 / 弹力带 / 哑铃 / 壶铃 / 跳绳 —— 在家就能练。 */
    val isBodyweightFriendly: Boolean = true,
) {
    /** 是不是技能树上的主干动作。 */
    val isBoss: Boolean get() = skillLevel in 1..8
}
