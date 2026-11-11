package io.github.kylin33.aifitgym.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppMetaDao {

    @Query("SELECT value FROM app_meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: AppMetaEntity)

    /** 删掉某条标记，让对应的幂等初始化在下次调用时重新跑一遍（比如清空数据后重灌模板）。 */
    @Query("DELETE FROM app_meta WHERE key = :key")
    suspend fun remove(key: String)
}
