package io.github.kylin33.aifitgym.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 一次完整训练的总记录（历史快照）。
 *
 * 关键点是**快照**：`routineNameSnapshot` 存的是开始时那一刻的计划名，
 * 之后用户改计划名、改动作、甚至把计划删了，这条历史都还是当时的样子。
 *
 * `routineId` 可为空，并且用 `ON DELETE SET_NULL`：计划被删掉时这里只丢掉「指向关系」，
 * 记录本身留着 —— 训练历史不该被一个编辑动作连带删除。删除后靠快照名仍然读得懂。
 *
 * 注意 FK 的子列必须建索引，否则 Room 编译期会直接报错（全表扫描做级联）。
 */
@Serializable
@Entity(
    tableName = "workout_sessions",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["routineId"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("routineId"), Index("startTime")],
)
data class WorkoutSessionLogEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    /** 原计划 id；计划被删后置空。 */
    val routineId: Long? = null,
    /** 计划名快照，计划改名/删除都不影响。 */
    val routineNameSnapshot: String = "",
    val startTime: Long = 0,
    /** 结束时刻；为 0 表示这次训练没正常收尾（进程被杀）。 */
    val endTime: Long = 0,
    val totalDurationSec: Int = 0,
) {
    val isFinished: Boolean get() = endTime > 0
}

/**
 * 单组明细。
 *
 * 独立成表而不是塞进总表的 JSON 字段：以后要做「该动作历史最好成绩」
 * （技能树解锁判据）和「肌肉恢复期」都要按 exerciseId 聚合，关系表才查得动。
 *
 * `sessionId` 用 CASCADE：删掉一次训练，它的组明细没有独立存在意义。
 * `exerciseId` 仍然**不设外键**，理由同 [RoutineExerciseEntity] —— 动作库整表重建时
 * 不能把用户历史一起带走。
 */
@Serializable
@Entity(
    tableName = "workout_set_logs",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionLogEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("exerciseId")],
)
data class WorkoutSetLogEntity(
    @PrimaryKey(autoGenerate = true) val setLogId: Long = 0,
    val sessionId: Long,
    val exerciseId: String,
    /** 该动作的第几组，1 起。 */
    val setIndex: Int,
    /** 实际完成次数；计时动作为 0。 */
    val actualReps: Int = 0,
    /** 实际坚持秒数；计次动作为 0。 */
    val actualDurationSec: Int = 0,
    /** 自觉用力程度 1~10，没问就是 null。 */
    val rpe: Int? = null,
)
