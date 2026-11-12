package io.github.kylin33.aifitgym.data.repo

import io.github.kylin33.aifitgym.data.checkpoint.CheckpointEntry
import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineDao
import io.github.kylin33.aifitgym.data.local.RoutineEntity
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao
import io.github.kylin33.aifitgym.data.local.WorkoutSessionLogEntity
import io.github.kylin33.aifitgym.data.local.WorkoutSetLogEntity

/**
 * 跟练时的一个条目：计划里的编排参数 + 动作本体（可能还有平替动作）。
 *
 * [exercise] 一定非空 —— 动作库里查不到的动作在展开时就被剔掉了，
 * 否则跟练页会出现「有标题没图没肌肉」的空组。
 */
data class WorkoutEntry(
    val item: RoutineExerciseEntity,
    val exercise: ExerciseEntity,
    val backup: ExerciseEntity?,
) {
    val isTimed: Boolean get() = item.isTimed

    /** 组数至少 1，防止计划里存了 0 导致这一条永远做不完。 */
    val targetSets: Int get() = item.targetSets.coerceAtLeast(1)

    /** 计次动作的目标次数；计时动作用不上。 */
    val plannedReps: Int get() = (item.targetReps ?: DEFAULT_REPS).coerceAtLeast(1)

    /** 计时动作的目标秒数。 */
    val plannedSeconds: Int get() = (item.targetDurationSec ?: DEFAULT_SECONDS).coerceAtLeast(1)

    val restSeconds: Int get() = item.restDurationSec.coerceAtLeast(0)

    companion object {
        const val DEFAULT_REPS = 10
        const val DEFAULT_SECONDS = 30
    }
}

/** 一次训练要练的完整序列。 */
data class WorkoutPlan(
    val routine: RoutineEntity,
    val entries: List<WorkoutEntry>,
    /** 编排里有、但动作库里查不到而被跳过的条目数，UI 可以提示一句。 */
    val skipped: Int = 0,
)

/** 刚才做完的一组，用来判 PR（只需要「哪个动作、做多少」，不含展示字段）。 */
data class SetOutcome(
    val exerciseId: String,
    val reps: Int,
    val seconds: Int,
)

/** 成绩口径：计次动作比次数，计时动作比秒数。 */
enum class PrMetric { REPS, DURATION }

/**
 * 一条新纪录。
 *
 * [previousBest] 一定非空：第一次做某个动作不算「破纪录」，没有基准就没有可比性 ——
 * 结算页只喊「新纪录」，不喊「首次记录」，这样文案永远成立。
 */
data class PersonalRecord(
    val exerciseId: String,
    val metric: PrMetric,
    /** 本次最好成绩。 */
    val value: Int,
    /** 之前的历史最好成绩。 */
    val previousBest: Int,
) {
    val delta: Int get() = value - previousBest
}

/**
 * 跟练相关读写：把计划展开成可执行序列、按组写明细、收尾时判 PR。
 *
 * 展开放在这里而不是 ViewModel，是为了让「动作缺席跳过」「平替解析」这类规则只有一份实现。
 */
class WorkoutRepository(
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
    private val sessionDao: WorkoutSessionDao,
) {

    suspend fun loadPlan(routineId: Long): WorkoutPlan? {
        val routine = routineDao.find(routineId) ?: return null
        val items = routineDao.findItems(routineId)
        if (items.isEmpty()) return WorkoutPlan(routine, emptyList(), 0)

        // 主动作和平替动作一起查，省一次往返；空列表会让 Room 生成 `IN ()` 语法错误，必须判空
        val ids = buildList {
            items.forEach { item ->
                add(item.exerciseId)
                item.backupExerciseId?.let(::add)
            }
        }.distinct()

        val byId = if (ids.isEmpty()) {
            emptyMap()
        } else {
            exerciseDao.findByIds(ids).associateBy { it.id }
        }

        val entries = items.mapNotNull { item ->
            val exercise = byId[item.exerciseId] ?: return@mapNotNull null
            WorkoutEntry(
                item = item,
                exercise = exercise,
                backup = item.backupExerciseId?.let { byId[it] },
            )
        }

        return WorkoutPlan(routine, entries, skipped = items.size - entries.size)
    }

    /**
     * 从崩溃恢复检查点重建可执行序列。
     *
     * 不复用 [loadPlan]：检查点存的是开始训练那一刻的展开结果，如果这期间用户改过计划，
     * 用「现在这套」恢复会让指针错位（entryIndex 指向了别的动作）。
     * 唯一和 loadPlan 一致的行为是「动作库里没有了就跳过」——同样不能让跟练页出现空组。
     */
    suspend fun restoreEntries(items: List<CheckpointEntry>): List<WorkoutEntry> {
        if (items.isEmpty()) return emptyList()

        val ids = buildList {
            items.forEach { item ->
                add(item.exerciseId)
                item.backupExerciseId?.let(::add)
            }
        }.distinct()

        val byId = exerciseDao.findByIds(ids).associateBy { it.id }

        return items.mapIndexedNotNull { index, item ->
            val exercise = byId[item.exerciseId] ?: return@mapIndexedNotNull null
            WorkoutEntry(
                // id 只是占位：恢复出来的条目不会写回 routine_exercises
                item = RoutineExerciseEntity(
                    id = 0,
                    routineId = 0L,
                    exerciseId = item.exerciseId,
                    orderIndex = index,
                    targetSets = item.sets,
                    targetReps = item.reps,
                    targetDurationSec = item.seconds,
                    restDurationSec = item.restSeconds,
                    backupExerciseId = item.backupExerciseId,
                ),
                exercise = exercise,
                backup = item.backupExerciseId?.let { byId[it] },
            )
        }
    }

    /** 检查点指向的那次训练还在不在（用户可能已经把它从历史里删了）。 */
    suspend fun sessionAlive(sessionId: Long): Boolean =
        sessionDao.findSession(sessionId) != null

    /**
     * 训练开始：先落一条总记录拿到 sessionId。
     *
     * 组明细有外键指向它，所以必须先有总记录；同时也顺便记下计划名快照，
     * 之后用户改名或删计划都不影响这条历史。
     *
     * [routineId] 可以为 null —— 技能树上的「直接开始考级」没有计划，
     * 这时候总记录里的 `routineId` 留空，但仍按计划名快照写「考级 · 俯卧撑」。
     */
    suspend fun beginSession(routineId: Long?, routineName: String, startTime: Long): Long =
        sessionDao.insertSession(
            WorkoutSessionLogEntity(
                routineId = routineId,
                routineNameSnapshot = routineName,
                startTime = startTime,
            )
        )

    /**
     * 单个动作的临时计划（技能树的「直接开始考级」）。
     *
     * 不写库、也不建真计划：`routineId = 0` 只在内存里存在，落库时传 null。
     * 这样考级不会污染计划列表，也不会因为「临时计划」被删掉而丢历史。
     */
    fun singlePlan(
        exercise: ExerciseEntity,
        sets: Int,
        reps: Int?,
        seconds: Int?,
    ): WorkoutPlan {
        val item = RoutineExerciseEntity(
            routineId = 0L,
            exerciseId = exercise.id,
            orderIndex = 0,
            targetSets = sets.coerceIn(1, 10),
            targetReps = if (seconds != null) null else (reps ?: WorkoutEntry.DEFAULT_REPS),
            targetDurationSec = seconds,
            restDurationSec = if (seconds != null) 30 else 60,
        )
        return WorkoutPlan(
            routine = RoutineEntity(routineId = 0L, name = "考级 · ${exercise.name}"),
            entries = listOf(WorkoutEntry(item = item, exercise = exercise, backup = null)),
        )
    }

    /** 一组练完立刻落库：中途退出、进程被杀都不会丢掉已经做完的组。 */
    suspend fun logSet(
        sessionId: Long,
        exerciseId: String,
        setIndex: Int,
        reps: Int,
        seconds: Int,
        rpe: Int? = null,
    ): Long = sessionDao.insertSet(
        WorkoutSetLogEntity(
            sessionId = sessionId,
            exerciseId = exerciseId,
            setIndex = setIndex,
            actualReps = reps,
            actualDurationSec = seconds,
            rpe = rpe,
        )
    )

    /**
     * 收尾：写入时长，并逐动作判定是否破了历史纪录。
     *
     * 判据是「本次单组最好成绩 > 该动作历史单组最好成绩（不含本次）」。
     * 比较用的是本次的**最好一组**，因为 PR 本来就是「我做到过的最强表现」，
     * 不是「每一组都比以前强」—— 最后一组力竭掉次数不该抹掉前面的纪录。
     */
    suspend fun finishSession(
        sessionId: Long,
        endTime: Long,
        durationSec: Int,
        sets: List<SetOutcome>,
    ): List<PersonalRecord> {
        if (sessionId <= 0L) return emptyList()

        val records = mutableListOf<PersonalRecord>()

        sets.groupBy { it.exerciseId }.forEach { (exerciseId, group) ->
            val bestReps = group.maxOf { it.reps }
            if (bestReps > 0) {
                val previous = sessionDao.bestRepsBefore(exerciseId, sessionId)
                if (previous != null && bestReps > previous) {
                    records += PersonalRecord(exerciseId, PrMetric.REPS, bestReps, previous)
                }
            }

            val bestSeconds = group.maxOf { it.seconds }
            if (bestSeconds > 0) {
                val previous = sessionDao.bestSecondsBefore(exerciseId, sessionId)
                if (previous != null && bestSeconds > previous) {
                    records += PersonalRecord(exerciseId, PrMetric.DURATION, bestSeconds, previous)
                }
            }
        }

        sessionDao.finishSession(sessionId, endTime, durationSec.coerceAtLeast(0))
        // 一组没做就退出的空壳不留在历史里。只在本次确实没有组时才尝试删：
        // 有组的情况下最后一条明细可能还在写，此时删父行会反过来把明细卡在外键上。
        if (sets.isEmpty()) sessionDao.deleteIfEmpty(sessionId)

        return records
    }
}
