package io.github.kylin33.aifitgym.data.backup

import io.github.kylin33.aifitgym.data.local.BranchProgressEntity
import io.github.kylin33.aifitgym.data.local.RoutineEntity
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity
import io.github.kylin33.aifitgym.data.local.WorkoutSessionLogEntity
import io.github.kylin33.aifitgym.data.local.WorkoutSetLogEntity
import kotlinx.serialization.Serializable

/** 一套计划连同它的动作条目。 */
@Serializable
data class RoutineWithExercisesDto(
    val routine: RoutineEntity,
    val items: List<RoutineExerciseEntity> = emptyList(),
)

/** 一次训练连同它的组明细。 */
@Serializable
data class SessionWithSetsDto(
    val session: WorkoutSessionLogEntity,
    val sets: List<WorkoutSetLogEntity> = emptyList(),
)

/**
 * 全量备份文件（一个 JSON）。
 *
 * **只装用户产生的数据**：动作库那 2303 条是随 APK 发行的可再生资源，不进来。
 * 备份里只记「哪 48 个主干动作通关了」（[masteredExerciseIds]），恢复时按 id 回填 ——
 * 这样数据包更新换了动作、或者下次版本调整了条件，旧的通关记录也不会打架。
 *
 * ## 兼容性约定
 *  - [schemaVersion] 只增不减，读到比自己新的文件直接拒绝，避免把新字段悄悄丢掉；
 *  - 所有字段都有默认值，将来加字段后**老文件仍能读**（缺的走默认）；
 *  - 字段改名等于破坏兼容，那要配一次 [schemaVersion] 升级 + 迁移分支。
 *
 * [routines] 里包含内置模板，不是只装「自建」的：历史记录里的 `routineId` 指向计划表，
 * 只恢复自建计划会让那些引用变成悬空 id，历史列表就只剩下一个名字快照。
 */
@Serializable
data class WorkoutBackupPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** 导出时刻，UI 上用来告诉用户「这份备份有多旧」。 */
    val exportedAt: Long = System.currentTimeMillis(),
    val masteredExerciseIds: List<String> = emptyList(),
    val branchProgressList: List<BranchProgressEntity> = emptyList(),
    val routines: List<RoutineWithExercisesDto> = emptyList(),
    val workoutLogs: List<SessionWithSetsDto> = emptyList(),
) {

    val setCount: Int get() = workoutLogs.sumOf { it.sets.size }

    /** 一个动作都没通关、一套计划都没有、一次训练都没记 —— 空文件没理由覆盖本机数据。 */
    val isEmpty: Boolean
        get() = masteredExerciseIds.isEmpty() &&
            routines.isEmpty() &&
            workoutLogs.isEmpty() &&
            branchProgressList.all { it.totalXp == 0 && it.currentTier <= 1 }

    fun summary(): BackupSummary = BackupSummary(
        routines = routines.size,
        sessions = workoutLogs.size,
        sets = setCount,
        masteredExercises = masteredExerciseIds.size,
        exportedAt = exportedAt,
    )

    companion object {
        /** 当前格式版本。加字段不用升（有默认值兜底），改语义/删字段才升。 */
        const val SCHEMA_VERSION = 1
    }
}

/** 导出/恢复完之后给用户看的一行统计。 */
data class BackupSummary(
    val routines: Int,
    val sessions: Int,
    val sets: Int,
    val masteredExercises: Int,
    val exportedAt: Long,
) {
    /** 「3 套计划 · 12 次训练（128 组）· 通关 9 个」。 */
    fun describe(): String = buildString {
        append("$routines 套计划 · $sessions 次训练")
        if (sets > 0) append("（$sets 组）")
        if (masteredExercises > 0) append(" · 通关 $masteredExercises 个")
    }
}

/** 备份文件本身有问题（版本太新、内容为空、JSON 坏了），消息直接给用户看。 */
class BackupException(message: String) : Exception(message)
