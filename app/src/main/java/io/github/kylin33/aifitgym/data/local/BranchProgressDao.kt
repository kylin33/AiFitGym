package io.github.kylin33.aifitgym.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 六大流派的熟练度账本，只有 6 行。 */
@Dao
interface BranchProgressDao {

    @Query("SELECT * FROM branch_progress")
    fun observeAll(): Flow<List<BranchProgressEntity>>

    @Query("SELECT * FROM branch_progress")
    suspend fun all(): List<BranchProgressEntity>

    @Query("SELECT * FROM branch_progress WHERE branch = :branch")
    suspend fun find(branch: String): BranchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BranchProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BranchProgressEntity>)

    @Query("SELECT COUNT(*) FROM branch_progress")
    suspend fun count(): Int

    /** 清空 6 条账本；清空数据后由 `SkillRepository.ensureInitialized()` 重新补齐到 Lv.1。 */
    @Query("DELETE FROM branch_progress")
    suspend fun clearAll()
}
