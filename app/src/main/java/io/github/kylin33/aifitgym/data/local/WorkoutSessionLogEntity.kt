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
    /**
     * 这组之前那次休息，计划多少秒。
     *
     * 和 [restActualSec] 成对存：只存「有没有跳过休息」的布尔，阈值就焊死在数据里了，
     * 而「歇一半算不算提前」这种判断以后一定想改。0 表示前面没有休息（每组第一组）。
     */
    val restPlannedSec: Int = 0,
    /**
     * 同上，实际过了多少秒。
     *
     * 提前结束休息会明显小于 [restPlannedSec]，点过「+15 秒」则会大于 ——
     * 两个方向都是信号（前者多半是「这组很轻松」，后者是「强度偏高或恢复不足」）。
     * 存事实不存结论：一次提前可能只是赶时间，判断留给统计层和教练。
     */
    val restActualSec: Int = 0,
    /**
     * 当时界面上摆的那个目标次数 / 秒数快照。
     *
     * 必须快照，不能事后再去 join `routine_exercises`：计划会被改、会被删，
     * 而「当时要求 10 次、他做了 15 次」这个对比一旦丢了就再也算不出来。
     * 计划没规定时取的是界面默认值（计次 10、计时 30）—— 那正是用户当时看到的目标。
     * null 表示这条记录没有可比的目标（考级模式、v8 之前的老记录）。
     */
    val targetReps: Int? = null,
    val targetDurationSec: Int? = null,
)
