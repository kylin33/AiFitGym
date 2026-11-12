package io.github.kylin33.aifitgym.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppMetaDao {

    @Query("SELECT value FROM app_meta WHERE key = :key")
    suspend fun get(key: String): String?

    /**
     * 监听某个键。
     *
     * 给「未完成的训练」横幅用：检查点在跟练页被写入/清除，
     * 首页要能自动跟着变，而不是等用户下一次进页面才刷新。
     */
    @Query("SELECT value FROM app_meta WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: AppMetaEntity)

    /** 删掉某条标记，让对应的幂等初始化在下次调用时重新跑一遍（比如清空数据后重灌模板）。 */
    @Query("DELETE FROM app_meta WHERE key = :key")
    suspend fun remove(key: String)
}
