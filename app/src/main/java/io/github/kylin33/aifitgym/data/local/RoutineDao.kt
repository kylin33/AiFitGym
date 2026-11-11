package io.github.kylin33.aifitgym.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {

    /**
     * 列表页用：计划本体 + 条目数 / 总组数 / 封面动作。
     * 三个子查询都比 JOIN + GROUP BY 好读，2300 条动作的库上也就是毫秒级。
     */
    @Query(
        """
        SELECT r.*,
            (SELECT COUNT(*) FROM routine_exercises e WHERE e.routineId = r.routineId) AS exerciseCount,
            (SELECT COALESCE(SUM(e.targetSets), 0) FROM routine_exercises e WHERE e.routineId = r.routineId) AS totalSets,
            (SELECT e.exerciseId FROM routine_exercises e
                WHERE e.routineId = r.routineId ORDER BY e.orderIndex LIMIT 1) AS coverExerciseId,
            (SELECT x.image FROM routine_exercises e
                LEFT JOIN exercises x ON x.id = e.exerciseId
                WHERE e.routineId = r.routineId ORDER BY e.orderIndex LIMIT 1) AS coverImage
        FROM routines r
        WHERE r.archived = 0
        ORDER BY r.updatedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<RoutineSummary>>

    @Query("SELECT * FROM routines WHERE routineId = :routineId")
    fun observeById(routineId: Long): Flow<RoutineEntity?>

    @Query("SELECT * FROM routines WHERE routineId = :routineId")
    suspend fun find(routineId: Long): RoutineEntity?

    @Query("SELECT * FROM routine_exercises WHERE routineId = :routineId ORDER BY orderIndex")
    fun observeItems(routineId: Long): Flow<List<RoutineExerciseEntity>>

    @Query("SELECT * FROM routine_exercises WHERE routineId = :routineId ORDER BY orderIndex")
    suspend fun findItems(routineId: Long): List<RoutineExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoutine(routine: RoutineEntity): Long

    /**
     * 用 @Update 而不是 REPLACE：SQLite 的 REPLACE 是「删了再插」，
     * 会触发外键 CASCADE 把该计划下的条目一起删掉，之后还得靠重新插入兜底，很容易出意外。
     */
    @Update
    suspend fun updateRoutine(routine: RoutineEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<RoutineExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: RoutineExerciseEntity): Long

    /** 追加到末尾用的下一个 orderIndex；空计划是 0。 */
    @Query("SELECT COALESCE(MAX(orderIndex), -1) + 1 FROM routine_exercises WHERE routineId = :routineId")
    suspend fun nextOrderIndex(routineId: Long): Int

    @Query("DELETE FROM routine_exercises WHERE routineId = :routineId")
    suspend fun deleteItems(routineId: Long)

    @Query("DELETE FROM routines WHERE routineId = :routineId")
    suspend fun deleteRoutine(routineId: Long)

    /**
     * 整条计划连同条目一起删。
     *
     * `routine_exercises` 上本来就有 ON DELETE CASCADE，但显式先删条目更稳妥：
     * 一旦哪天外键开关被关掉（或表被重建丢了约束），不会留下一堆孤儿条目。
     */
    @Transaction
    suspend fun deleteCascade(routineId: Long) {
        deleteItems(routineId)
        deleteRoutine(routineId)
    }

    /**
     * 撤销删除：按删除前的主键原样写回。
     *
     * Room 的 insert 适配器对自增主键的处理是「值为 0 才绑 NULL 走自增」，
     * 这里传的是删除前的真实 id，所以会原样插回同一行；条目 id 归零重新分配。
     */
    @Transaction
    suspend fun restore(routine: RoutineEntity, items: List<RoutineExerciseEntity>): Long {
        insertRoutine(routine)
        if (items.isNotEmpty()) {
            insertItems(items.map { it.copy(id = 0, routineId = routine.routineId) })
        }
        return routine.routineId
    }

    @Query("UPDATE routines SET archived = 1, updatedAt = :now WHERE routineId = :routineId")
    suspend fun archive(routineId: Long, now: Long = System.currentTimeMillis())

    /**
     * 技能进阶后把计划里的旧动作换成新动作。
     *
     * 只改 exerciseId，不动 orderIndex / 组数 / 休息 —— 等价于「同一个位置换个动作」，
     * 也就不会撞 (routineId, orderIndex) 的唯一索引。主动作和平替各查一次：
     * 旧动作可能只出现在平替位上。
     *
     * 如果这套计划里**已经有**新动作（内置拉力日就同时有「屈膝反向划船」和「反向划船」），
     * 直接返回 -1 不替换：换成重复动作比不换更糟。
     */
    @Query(
        """
        UPDATE routine_exercises SET exerciseId = :newId
        WHERE routineId = :routineId AND exerciseId = :oldId
        """
    )
    suspend fun swapExercise(routineId: Long, oldId: String, newId: String): Int

    @Query(
        """
        UPDATE routine_exercises SET backupExerciseId = :newId
        WHERE routineId = :routineId AND backupExerciseId = :oldId
        """
    )
    suspend fun swapBackup(routineId: Long, oldId: String, newId: String): Int

    @Query(
        "SELECT COUNT(*) FROM routine_exercises WHERE routineId = :routineId AND exerciseId = :exerciseId"
    )
    suspend fun countOfExercise(routineId: Long, exerciseId: String): Int

    /** 返回改了几行；0 表示没有那个旧动作，-1 表示计划里已经有新动作。 */
    @Transaction
    suspend fun replaceSkill(routineId: Long, oldId: String, newId: String): Int {
        if (oldId == newId) return 0
        if (countOfExercise(routineId, newId) > 0) return -1
        return swapExercise(routineId, oldId, newId) + swapBackup(routineId, oldId, newId)
    }

    @Query("SELECT COUNT(*) FROM routines WHERE presetKey IS NOT NULL AND archived = 0")
    suspend fun presetCount(): Int

    @Query("SELECT COUNT(*) FROM routines WHERE archived = 0")
    suspend fun activeCount(): Int

    // ------------------------------------------------------------ 备份 / 恢复

    /** 导出用：连归档的计划一起给，否则历史里的 routineId 恢复后会指空。 */
    @Query("SELECT * FROM routines")
    suspend fun allForBackup(): List<RoutineEntity>

    @Query("SELECT * FROM routine_exercises ORDER BY routineId, orderIndex")
    suspend fun allItemsForBackup(): List<RoutineExerciseEntity>

    /** 恢复前的清场；先条目后本体，配合外键的 CASCADE 不会留下孤儿行。 */
    @Query("DELETE FROM routine_exercises")
    suspend fun clearItems()

    @Query("DELETE FROM routines")
    suspend fun clearRoutines()

    /**
     * 写回备份里的计划。
     *
     * 这里**保留备份中的原始主键**（`routineId` 不为 0 时 Room 会原样插入，不走自增），
     * 因为历史的 `routineId` 要对得上；清场之后也不存在主键冲突。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoutineForRestore(routine: RoutineEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItemsForRestore(items: List<RoutineExerciseEntity>)

    /**
     * 保存整套计划：条目先清后写，`orderIndex` 在这里统一按列表下标重排，
     * 调用方不需要自己维护顺序。返回计划 id（新建时是自增主键）。
     */
    @Transaction
    suspend fun save(routine: RoutineEntity, items: List<RoutineExerciseEntity>): Long {
        val now = System.currentTimeMillis()
        val routineId = if (routine.routineId == 0L) {
            insertRoutine(routine.copy(createdAt = now, updatedAt = now))
        } else {
            updateRoutine(routine.copy(updatedAt = now))
            routine.routineId
        }
        deleteItems(routineId)
        if (items.isNotEmpty()) {
            insertItems(
                items.mapIndexed { index, item ->
                    item.copy(id = 0, routineId = routineId, orderIndex = index)
                },
            )
        }
        return routineId
    }
}
