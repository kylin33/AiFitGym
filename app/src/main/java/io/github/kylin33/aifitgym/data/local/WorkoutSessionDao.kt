package io.github.kylin33.aifitgym.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 某个动作在时间窗内的累计训练量，后面接肌肉恢复看板用。 */
data class ExerciseVolume(
    val exerciseId: String,
    val sets: Int,
    val reps: Int,
    val seconds: Int,
)

/** 某个动作单组最好成绩：计次看次数，计时看秒数。 */
data class ExerciseBest(
    val exerciseId: String,
    val reps: Int,
    val seconds: Int,
)

/**
 * 一次训练里某个动作做了几组。
 *
 * 恢复算法要按「每一批训练」分别算时间衰减，所以不能在 SQL 里直接 SUM ——
 * 那样会把今天刚练的组和三天前练的组混成一个数，衰减系数就没法套了。
 */
data class SetBurst(
    val exerciseId: String,
    val startedAt: Long,
    val sets: Int,
)

/** 时间窗内的训练次数与总时长，统计页顶部用。 */
data class SessionSummary(
    val sessions: Int,
    val durationSec: Int,
)

/**
 * 某动作在某次训练里的表现。
 *
 * 趋势判断的原料。按「动作 × 训练」聚合成一条，[bestReps] 取这一场的最好单组 ——
 * 一次练了 12/10/8/6，代表能力的是 12；把 6 也算进趋势，会把「第一组就累了」
 * 误判成「退步了」。
 */
data class SessionPerformance(
    val sessionId: Long,
    val exerciseId: String,
    val startedAt: Long,
    val sets: Int,
    val bestReps: Int,
    val bestSeconds: Int,
    /** 这一场里有目标可比对、且确实做了的组数；v8 之前的老记录为 0。 */
    val trackedSets: Int = 0,
    /** 其中做到或超过目标的组数。 */
    val hitSets: Int = 0,
    /** 其中超出目标的组数 —— 比达标更强的「还有余力」信号。 */
    val overSets: Int = 0,
    /** 有休息可算的组数（这组之前的休息计划秒数 > 0）。 */
    val restSets: Int = 0,
    val restPlannedSum: Int = 0,
    val restActualSum: Int = 0,
) {
    /** 这次是计时动作还是计次动作。 */
    val isTimed: Boolean get() = bestSeconds > 0 && bestReps == 0

    /** 这次的代表成绩：计次看次数，计时看秒数。 */
    val value: Int get() = if (isTimed) bestSeconds else bestReps
}

/** 历史列表的一行：总记录 + 组数快照。 */
data class SessionHeader(
    val sessionId: Long,
    val routineNameSnapshot: String,
    val startTime: Long,
    val endTime: Long,
    val totalDurationSec: Int,
    val setCount: Int,
) {
    val isFinished: Boolean get() = endTime > 0
}

/** 历史详情的一行：组明细 + 动作名/图（动作库整表重灌后名字仍能取到）。 */
data class SetLogRow(
    val setLogId: Long,
    val sessionId: Long,
    val exerciseId: String,
    val name: String,
    val image: String,
    val setIndex: Int,
    val actualReps: Int,
    val actualDurationSec: Int,
    val rpe: Int?,
) {
    val isTimed: Boolean get() = actualDurationSec > 0
}

@Dao
interface WorkoutSessionDao {

    // ------------------------------------------------------------ 写入

    @Insert
    suspend fun insertSession(session: WorkoutSessionLogEntity): Long

    /**
     * 收尾：补上结束时刻和总时长。
     *
     * 放在这里而不是「结束时一次性插整条」，是因为要保证组明细插入时 sessionId 已经存在（外键），
     * 而且中途被系统杀掉时，至少还能看出「这次训练开了但没做完」。
     */
    @Query(
        """
        UPDATE workout_sessions
        SET endTime = :endTime, totalDurationSec = :durationSec
        WHERE sessionId = :sessionId
        """
    )
    suspend fun finishSession(sessionId: Long, endTime: Long, durationSec: Int): Int

    @Insert
    suspend fun insertSet(log: WorkoutSetLogEntity): Long

    /** 一组都没做就退出的空壳训练不该留在历史里。 */
    @Query(
        """
        DELETE FROM workout_sessions
        WHERE sessionId = :sessionId
          AND NOT EXISTS (SELECT 1 FROM workout_set_logs WHERE sessionId = :sessionId)
        """
    )
    suspend fun deleteIfEmpty(sessionId: Long): Int

    // ------------------------------------------------------------ PR 判定

    /**
     * 该动作在**本次训练之前**的最高单组次数。
     *
     * `sessionId <> :excludeSessionId` 排掉本次自己，这样即使本次的组已经先落库了，
     * 也不会拿自己跟自己比出「新纪录」。没有任何历史时返回 null，调用方据此不判 PR。
     */
    @Query(
        """
        SELECT MAX(actualReps) FROM workout_set_logs
        WHERE exerciseId = :exerciseId AND sessionId <> :excludeSessionId AND actualReps > 0
        """
    )
    suspend fun bestRepsBefore(exerciseId: String, excludeSessionId: Long): Int?

    /** 同上，计时动作按坚持秒数比。 */
    @Query(
        """
        SELECT MAX(actualDurationSec) FROM workout_set_logs
        WHERE exerciseId = :exerciseId AND sessionId <> :excludeSessionId AND actualDurationSec > 0
        """
    )
    suspend fun bestSecondsBefore(exerciseId: String, excludeSessionId: Long): Int?

    // ------------------------------------------------------------ 读

    @Query("SELECT * FROM workout_sessions ORDER BY startTime DESC LIMIT :limit")
    fun observeSessions(limit: Int = 50): Flow<List<WorkoutSessionLogEntity>>

    @Query("SELECT * FROM workout_sessions WHERE sessionId = :sessionId")
    suspend fun findSession(sessionId: Long): WorkoutSessionLogEntity?

    @Query("SELECT * FROM workout_set_logs WHERE sessionId = :sessionId ORDER BY setLogId")
    suspend fun setsOfSession(sessionId: Long): List<WorkoutSetLogEntity>

    /**
     * 一批动作的历史最好成绩，技能树节点展示用。
     *
     * 一次查询拿全部，不要每个节点查一遍 —— 20 个节点就是 20 次往返。
     * 调用方必须保证 [ids] 非空（Room 会生成 `IN ()` 语法错误）。
     */
    @Query(
        """
        SELECT exerciseId,
               COALESCE(MAX(actualReps), 0) AS reps,
               COALESCE(MAX(actualDurationSec), 0) AS seconds
        FROM workout_set_logs
        WHERE exerciseId IN (:ids)
        GROUP BY exerciseId
        """
    )
    suspend fun bestsOf(ids: List<String>): List<ExerciseBest>

    @Query("SELECT COUNT(*) FROM workout_sessions WHERE endTime > 0")
    fun observeFinishedSessionCount(): Flow<Int>

    @Query("SELECT MAX(startTime) FROM workout_sessions WHERE endTime > 0")
    fun observeLastTrainedAt(): Flow<Long?>

    /**
     * 全部组明细的「指纹」（次数 + 秒数之和）。
     *
     * 历史页可以手动改成绩，而改成绩既不会新增训练、也不会改 `startTime`，
     * 「最近训练时间」这个刷新信号是感知不到的。加一个随明细变化的轻量信号，
     * 技能树上的「最好 X 次」才能跟着改后的数字走。
     */
    @Query("SELECT COALESCE(SUM(actualReps + actualDurationSec), 0) FROM workout_set_logs")
    fun observeSetFingerprint(): Flow<Long>

    @Query("SELECT COALESCE(SUM(totalDurationSec), 0) FROM workout_sessions WHERE endTime > 0")
    fun observeTotalDurationSec(): Flow<Int>

    /**
     * 聚合 [since] 之后的训练量；热力图按这个结果乘动作的肌群权重。
     * 时间在总表上，所以要 JOIN 一次。
     */
    @Query(
        """
        SELECT s.exerciseId,
               COUNT(*) AS sets,
               COALESCE(SUM(s.actualReps), 0) AS reps,
               COALESCE(SUM(s.actualDurationSec), 0) AS seconds
        FROM workout_set_logs s
        JOIN workout_sessions w ON w.sessionId = s.sessionId
        WHERE w.startTime >= :since
        GROUP BY s.exerciseId
        """
    )
    suspend fun volumeSince(since: Long): List<ExerciseVolume>

    /**
     * 时间窗内「每个动作、每次训练」做了几组。
     *
     * 时间戳在总表上（明细只有 setIndex，不带时间），所以要 JOIN 一次拿 startTime。
     * 按 (exerciseId, startTime) 分组而不是按动作汇总：恢复算法要给每一批分别乘衰减系数。
     */
    @Query(
        """
        SELECT s.exerciseId AS exerciseId,
               w.startTime AS startedAt,
               COUNT(*) AS sets
        FROM workout_set_logs s
        JOIN workout_sessions w ON w.sessionId = s.sessionId
        WHERE w.startTime >= :since AND w.startTime <= :until
        GROUP BY s.exerciseId, w.startTime
        ORDER BY w.startTime
        """
    )
    suspend fun burstsBetween(since: Long, until: Long): List<SetBurst>

    /** 时间窗内正常收尾的训练次数与总时长。 */
    @Query(
        """
        SELECT COUNT(*) AS sessions,
               COALESCE(SUM(totalDurationSec), 0) AS durationSec
        FROM workout_sessions
        WHERE endTime > 0 AND startTime >= :since AND startTime <= :until
        """
    )
    suspend fun summaryBetween(since: Long, until: Long): SessionSummary

    // ------------------------------------------------------------ AI 教练的背景资料

    /** 最近几场练完的训练（新→旧），用于拼「最近训练」摘要。 */
    @Query("SELECT * FROM workout_sessions WHERE endTime > 0 ORDER BY startTime DESC LIMIT :limit")
    suspend fun recentFinishedSessions(limit: Int): List<WorkoutSessionLogEntity>

    /** 上面那几场的全部组明细，一次取回。调用方保证 [ids] 非空。 */
    @Query("SELECT * FROM workout_set_logs WHERE sessionId IN (:ids) ORDER BY sessionId, setLogId")
    suspend fun setsOfSessions(ids: List<Long>): List<WorkoutSetLogEntity>

    /**
     * 没正常收尾的训练条数。
     *
     * 这是个对教练很有用的信号：频繁中途退出，多半不是时间不够，而是计划强度排错了。
     */
    @Query("SELECT COUNT(*) FROM workout_sessions WHERE endTime = 0")
    suspend fun unfinishedCount(): Int

    /**
     * 时间窗内「每个动作、每次训练」的表现，趋势分析的原料。
     *
     * 只多一个查询就把 7 天 / 30 天的训练日、组数、流派分布、表现趋势全部算完，
     * 不必让模型去读几百条原始组记录 —— 它数不准，也没那么多 token。
     *
     * 顺便把「达标 / 超额 / 实际休息」也算成聚合值：
     *  - trackedSets 只数有目标快照的组，老记录（targetReps 为 null）不算，
     *    否则升级当天的超额率会被历史数据摊成 0；
     *  - restSets 只数「前面确实排了休息」的组，保证计划秒数与实际秒数同分母，
     *    否则第一组（前面没休息）会把平均休息拉低。
     */
    @Query(
        """
        SELECT s.sessionId AS sessionId,
               s.exerciseId AS exerciseId,
               w.startTime AS startedAt,
               COUNT(*) AS sets,
               COALESCE(MAX(s.actualReps), 0) AS bestReps,
               COALESCE(MAX(s.actualDurationSec), 0) AS bestSeconds,
               SUM(CASE WHEN (s.targetReps IS NOT NULL AND s.actualReps > 0)
                          OR (s.targetDurationSec IS NOT NULL AND s.actualDurationSec > 0)
                        THEN 1 ELSE 0 END) AS trackedSets,
               SUM(CASE WHEN (s.targetReps IS NOT NULL AND s.actualReps >= s.targetReps)
                          OR (s.targetDurationSec IS NOT NULL AND s.actualDurationSec >= s.targetDurationSec)
                        THEN 1 ELSE 0 END) AS hitSets,
               SUM(CASE WHEN (s.targetReps IS NOT NULL AND s.actualReps > s.targetReps)
                          OR (s.targetDurationSec IS NOT NULL AND s.actualDurationSec > s.targetDurationSec)
                        THEN 1 ELSE 0 END) AS overSets,
               SUM(CASE WHEN s.restPlannedSec > 0 THEN 1 ELSE 0 END) AS restSets,
               SUM(CASE WHEN s.restPlannedSec > 0 THEN s.restPlannedSec ELSE 0 END) AS restPlannedSum,
               SUM(CASE WHEN s.restPlannedSec > 0 THEN s.restActualSec ELSE 0 END) AS restActualSum
        FROM workout_set_logs s
        JOIN workout_sessions w ON w.sessionId = s.sessionId
        WHERE w.startTime >= :since AND w.startTime <= :until
        GROUP BY s.exerciseId, s.sessionId
        ORDER BY w.startTime
        """
    )
    suspend fun performancesBetween(since: Long, until: Long): List<SessionPerformance>

    /**
     * 该动作「单场累计」的最高次数（不分单组），累计类节点用。
     *
     * 内层按 (动作, 训练) 求和，外层取最大值 —— 不能直接 `MAX(SUM())`，
     * SQLite 的聚合不能嵌套，必须靠子查询分成两层。
     */
    @Query(
        """
        SELECT exerciseId, MAX(total) AS reps, 0 AS seconds FROM (
            SELECT exerciseId, SUM(actualReps) AS total
            FROM workout_set_logs
            WHERE exerciseId IN (:ids)
            GROUP BY exerciseId, sessionId
        )
        GROUP BY exerciseId
        """
    )
    suspend fun bestSessionReps(ids: List<String>): List<ExerciseBest>

    // ------------------------------------------------------------ 历史 / 编辑

    /** 历史列表：每场训练 + 组数。按开始时间倒序，最近的在最上面。 */
    @Query(
        """
        SELECT w.sessionId AS sessionId,
               w.routineNameSnapshot AS routineNameSnapshot,
               w.startTime AS startTime,
               w.endTime AS endTime,
               w.totalDurationSec AS totalDurationSec,
               (SELECT COUNT(*) FROM workout_set_logs s WHERE s.sessionId = w.sessionId) AS setCount
        FROM workout_sessions w
        ORDER BY w.startTime DESC
        LIMIT :limit
        """
    )
    fun observeSessionHeaders(limit: Int = 200): Flow<List<SessionHeader>>

    /** 一场训练的所有组，带动作名和演示图。 */
    @Query(
        """
        SELECT s.setLogId AS setLogId,
               s.sessionId AS sessionId,
               s.exerciseId AS exerciseId,
               COALESCE(e.name, s.exerciseId) AS name,
               COALESCE(e.image, '') AS image,
               s.setIndex AS setIndex,
               s.actualReps AS actualReps,
               s.actualDurationSec AS actualDurationSec,
               s.rpe AS rpe
        FROM workout_set_logs s
        LEFT JOIN exercises e ON e.id = s.exerciseId
        WHERE s.sessionId = :sessionId
        ORDER BY s.setLogId
        """
    )
    suspend fun setsWithNames(sessionId: Long): List<SetLogRow>

    /** 手动修正一组：次数和秒数同时写，避免「改成计时」时残留旧值。 */
    @Query(
        """
        UPDATE workout_set_logs
        SET actualReps = :reps, actualDurationSec = :seconds
        WHERE setLogId = :setLogId
        """
    )
    suspend fun updateSet(setLogId: Long, reps: Int, seconds: Int): Int

    @Query("DELETE FROM workout_set_logs WHERE setLogId = :setLogId")
    suspend fun deleteSetById(setLogId: Long): Int

    /** 删除整场训练；明细靠外键 CASCADE 一起走。 */
    @Query("DELETE FROM workout_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: Long): Int

    /** 把中途退出、没正常收尾的训练补完。只对 endTime = 0 的记录生效。 */
    @Query(
        """
        UPDATE workout_sessions
        SET endTime = :endTime, totalDurationSec = :durationSec
        WHERE sessionId = :sessionId AND endTime = 0
        """
    )
    suspend fun markFinished(sessionId: Long, endTime: Long, durationSec: Int): Int

    // ------------------------------------------------------------ 备份 / 恢复

    @Query("SELECT * FROM workout_sessions ORDER BY startTime")
    suspend fun allSessionsForBackup(): List<WorkoutSessionLogEntity>

    @Query("SELECT * FROM workout_set_logs ORDER BY setLogId")
    suspend fun allSetsForBackup(): List<WorkoutSetLogEntity>

    /** 先清明细再清总表：反过来的话 CASCADE 会替我们把明细删掉，但显式更可控。 */
    @Query("DELETE FROM workout_set_logs")
    suspend fun clearSets()

    @Query("DELETE FROM workout_sessions")
    suspend fun clearSessions()

    /** 保留备份里的原始主键，组明细的 sessionId 才对得上。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessionsForRestore(sessions: List<WorkoutSessionLogEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSetsForRestore(sets: List<WorkoutSetLogEntity>)
}
