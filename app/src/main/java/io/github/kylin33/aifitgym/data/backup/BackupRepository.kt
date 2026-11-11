package io.github.kylin33.aifitgym.data.backup

import androidx.room.withTransaction
import io.github.kylin33.aifitgym.data.local.BranchProgressDao
import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.FitGymDatabase
import io.github.kylin33.aifitgym.data.local.RoutineDao
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao
import io.github.kylin33.aifitgym.data.skill.SkillBranch
import io.github.kylin33.aifitgym.data.skill.SkillRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 全量备份的读写。
 *
 * ## 为什么是一整个 JSON 而不是数据库文件
 * 直接把 `fitgym.db` 拷走更快，但那个文件里 99% 是 2303 条内置动作（约 20 MB），
 * 而动作库随版本更新会整体重灌 —— 把旧库搬回来等于把动作库回滚了一遍，
 * 之后 `isMastered` / `skillLevel` 这些打标字段全对不上。所以只导出「用户的账」。
 *
 * ## 恢复的一致性
 * 所有写操作都在 [withTransaction] 里：中途抛错（外键、主键冲突、磁盘满）整批回滚，
 * 不会出现「计划清空了、历史还在」这种半截状态。
 */
class BackupRepository(
    private val db: FitGymDatabase,
    private val exerciseDao: ExerciseDao,
    private val routineDao: RoutineDao,
    private val sessionDao: WorkoutSessionDao,
    private val progressDao: BranchProgressDao,
) {

    /**
     * `encodeDefaults = true` 不是为了好看：如果哪天真把某个字段的默认值改了
     * （比如休息时长 60 → 90），旧备份里缺这个字段的话，反序列化会拿**新默认值**填，
     * 等于悄悄改了用户的数据。全量写出就永远读回当时的原值。
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // ------------------------------------------------------------ 导出

    suspend fun export(): WorkoutBackupPayload = withContext(Dispatchers.IO) {
        val routines = routineDao.allForBackup()
        val itemsByRoutine = routineDao.allItemsForBackup().groupBy { it.routineId }

        val sessions = sessionDao.allSessionsForBackup()
        val setsBySession = sessionDao.allSetsForBackup().groupBy { it.sessionId }

        WorkoutBackupPayload(
            schemaVersion = WorkoutBackupPayload.SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            // 排序只是为了让同一份数据每次导出字节一致，方便用户自己 diff
            masteredExerciseIds = exerciseDao.masteredIds().sorted(),
            branchProgressList = progressDao.all().sortedBy { it.branch },
            routines = routines.map { routine ->
                RoutineWithExercisesDto(
                    routine = routine,
                    items = itemsByRoutine[routine.routineId].orEmpty(),
                )
            },
            workoutLogs = sessions.map { session ->
                SessionWithSetsDto(
                    session = session,
                    sets = setsBySession[session.sessionId].orEmpty(),
                )
            },
        )
    }

    suspend fun exportJson(): String = encode(export())

    /** 拆出来是为了让调用方先拿到 payload 里的统计（导完提示「3 套计划 · 12 次训练」）。 */
    fun encode(payload: WorkoutBackupPayload): String =
        json.encodeToString(WorkoutBackupPayload.serializer(), payload)

    // ------------------------------------------------------------ 导入

    /**
     * 只解析、校验、不落库 —— 让界面先把「这份备份里有什么」给用户看，
     * 确认之后才覆盖本机数据。导入是不可撤销的操作，值得多一次点击。
     */
    fun decode(text: String): Result<WorkoutBackupPayload> = runCatching {
        val payload = try {
            json.decodeFromString(WorkoutBackupPayload.serializer(), text)
        } catch (e: SerializationException) {
            // 用户选错文件（比如随便一个 txt）走的就是这条路
            throw BackupException("这不是 FitGym 的备份文件，或者文件已经损坏")
        } catch (e: IllegalArgumentException) {
            throw BackupException("这不是 FitGym 的备份文件，或者文件已经损坏")
        }

        when {
            payload.schemaVersion > WorkoutBackupPayload.SCHEMA_VERSION ->
                throw BackupException(
                    "备份来自更新版本的 App（v${payload.schemaVersion}），" +
                        "请升级后再恢复，否则新数据会丢"
                )

            payload.schemaVersion < 1 ->
                throw BackupException("备份版本号异常（v${payload.schemaVersion}）")

            payload.isEmpty ->
                throw BackupException("这份备份里没有任何训练数据")
        }
        payload
    }

    /**
     * 覆盖式恢复：清空 6 张用户表后按备份写回，全程一个事务。
     *
     * 动的是「用户数据」四类：技能树进度、流派账本、计划、训练历史。
     * 动作库（`exercises`）除了 48 个通关标记外一个字都不碰。
     */
    suspend fun restore(payload: WorkoutBackupPayload): Result<BackupSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                db.withTransaction {
                    restoreSkillTree(payload)
                    val restored = restoreRoutinesAndLogs(payload)
                    restored
                }
            }
        }

    private suspend fun restoreSkillTree(payload: WorkoutBackupPayload) {
        // isMastered 是派生状态：先全清再按备份回填，避免「本机通关、备份没通关」的残留
        exerciseDao.clearMastery()
        val mastered = payload.masteredExerciseIds.filter { it.isNotBlank() }
        if (mastered.isNotEmpty()) exerciseDao.markMastered(mastered)

        // 只认 6 条已知流派；没人保证手改过的文件里是什么 key
        val rows = payload.branchProgressList
            .filter { row -> SkillBranch.entries.any { it.key == row.branch } }
            .map { row ->
                val total = row.totalXp.coerceAtLeast(0)
                row.copy(
                    currentTier = row.currentTier.coerceIn(1, SkillRules.MAX_TIER),
                    totalXp = total,
                    xpSpent = row.xpSpent.coerceIn(0, total),
                    discountPercent = row.discountPercent.coerceIn(0, SkillRules.MAX_DISCOUNT),
                )
            }
        if (rows.isNotEmpty()) progressDao.upsertAll(rows)
    }

    /**
     * 计划与历史：先清后写。
     *
     * 清理顺序被外键约束绑死：组明细 → 训练总表 → 计划条目 → 计划本体。
     * 反过来删计划的话，`workout_sessions.routineId` 会先被 SET_NULL，
     * 虽然结果也对，但中间态没有意义，不如按依赖顺序来。
     */
    private suspend fun restoreRoutinesAndLogs(payload: WorkoutBackupPayload): BackupSummary {
        sessionDao.clearSets()
        sessionDao.clearSessions()
        routineDao.clearItems()
        routineDao.clearRoutines()

        // 主键为 0 的行在备份里不该存在（导出时都是自增过的真实 id），
        // 真有的话插进去会拿到新 id，而它的子行还指向旧 id —— 直接丢弃，保证引用不断。
        val routines = payload.routines.filter { it.routine.routineId > 0L }
        routines.forEach { dto ->
            val routine = dto.routine
            routineDao.insertRoutineForRestore(routine)
            val items = dto.items.filter { it.exerciseId.isNotBlank() }
            if (items.isNotEmpty()) {
                routineDao.insertItemsForRestore(items.map { it.copy(routineId = routine.routineId) })
            }
        }

        val restoredRoutineIds = routines.map { it.routine.routineId }.toSet()
        val sessions = payload.workoutLogs
            .map { it.session }
            .filter { it.sessionId > 0L }
            // 备份里的计划可能已被用户删掉（那时 routineId 本来就是 null），
            // 但从旧版本或手改文件进来的悬空 id 会让外键报错，这里统一收敛成 null
            .map { if (it.routineId != null && it.routineId !in restoredRoutineIds) it.copy(routineId = null) else it }

        sessions.chunked(INSERT_CHUNK).forEach { sessionDao.insertSessionsForRestore(it) }

        val restoredSessionIds = sessions.map { it.sessionId }.toSet()
        val sets = payload.workoutLogs
            .flatMap { it.sets }
            .filter { it.sessionId in restoredSessionIds && it.exerciseId.isNotBlank() }
        sets.chunked(INSERT_CHUNK).forEach { sessionDao.insertSetsForRestore(it) }

        return BackupSummary(
            routines = routines.size,
            sessions = sessions.size,
            sets = sets.size,
            masteredExercises = payload.masteredExerciseIds.size,
            exportedAt = payload.exportedAt,
        )
    }

    private companion object {
        /**
         * 一条 INSERT 里塞多少行。
         *
         * SQLite 的绑定变量上限是 999，训练总表一行 6 列、组明细一行 7 列，
         * 500 行也就 3500 个变量 —— 超了。所以按 200 行切，既安全又不至于一条一条慢慢插。
         */
        const val INSERT_CHUNK = 200
    }
}
