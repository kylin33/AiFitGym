package io.github.kylin33.aifitgym.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 筛选栏用的分组计数。 */
data class GroupCount(val groupKey: String, val label: String, val count: Int)

@Dao
interface ExerciseDao {

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM exercises")
    fun observeCount(): Flow<Int>

    @Query("SELECT id FROM exercises")
    suspend fun allIds(): List<String>

    /** 验收页/默认列表用：一次全量给出，按部位 + 名称排序。 */
    @Query("SELECT * FROM exercises ORDER BY bodyPartCn, name")
    fun observeAll(): Flow<List<ExerciseEntity>>

    @Query(
        """
        SELECT * FROM exercises
        WHERE (:bodyPart IS NULL OR bodyPart = :bodyPart)
          AND (:category IS NULL OR category = :category)
          AND (:branch IS NULL OR branch = :branch)
          AND (:bodyweightOnly = 0 OR isBodyweightFriendly = 1)
          AND (:keyword = '' OR searchKey LIKE '%' || :keyword || '%')
        ORDER BY bodyPart, name
        """
    )
    fun observeFiltered(
        bodyPart: String?,
        category: String?,
        branch: String?,
        bodyweightOnly: Boolean,
        keyword: String,
    ): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    fun observeById(id: String): Flow<ExerciseEntity?>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun findById(id: String): ExerciseEntity?

    /**
     * 批量取动作，给「计划展开成跟练序列」用。
     *
     * 注意：传空列表会生成 `IN ()`，SQLite 会直接语法报错，调用方必须先判空。
     */
    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<ExerciseEntity>

    // ------------------------------------------------------------ 技能树 / 支线池

    /** 某个流派的 8 个主干动作，按阶排列。 */
    @Query("SELECT * FROM exercises WHERE branch = :branch AND skillLevel > 0 ORDER BY skillLevel")
    fun observeBosses(branch: String): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE branch = :branch AND skillLevel > 0 ORDER BY skillLevel")
    suspend fun bossesOf(branch: String): List<ExerciseEntity>

    /** 全部 48 个主干动作；技能树页面一次性取回，6 条链都在里面。 */
    @Query("SELECT * FROM exercises WHERE skillLevel > 0 ORDER BY branch, skillLevel")
    fun observeAllBosses(): Flow<List<ExerciseEntity>>

    /** 同上的挂起版，AI 教练拼候选池时用（一次请求只读一次，不需要 Flow）。 */
    @Query("SELECT * FROM exercises WHERE skillLevel > 0 ORDER BY branch, skillLevel")
    suspend fun allBosses(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE branch = :branch AND skillLevel = :tier LIMIT 1")
    suspend fun findBoss(branch: String, tier: Int): ExerciseEntity?

    /**
     * 同流派强化动作池：这个节点「突破瓶颈」时可以拿来练的支线动作。
     *
     * 排序就是推荐逻辑，不用硬编码任何连线：
     *  1. 主发力肌群相同的最优先（`target = :target` 命中排前面）；
     *  2. 拉伸 / 瑜伽 / 康复类靠后 —— 它们算「放松」，不算「强化」；
     *  3. 剩下按名称稳定排序，保证每次打开看到的顺序一致。
     *
     * `GROUP BY name` 是因为上游有几条同名不同 id 的动作（「交叉开合跳」有三份），
     * 不去重的话卡片横排里会出现三个一模一样的名字，看着像 bug。
     *
     * `skillLevel = 0` 把 48 个主干动作本身排除掉，避免推荐「再练一遍考级动作」。
     */
    @Query(
        """
        SELECT * FROM exercises
        WHERE branch = :branch
          AND skillLevel = 0
          AND isBodyweightFriendly = 1
          AND category NOT IN ('stretching', 'mobility', 'yoga', 'rehab')
        GROUP BY name
        ORDER BY (target = :target) DESC, name
        LIMIT :limit
        """
    )
    suspend fun accessoryPool(branch: String, target: String, limit: Int): List<ExerciseEntity>

    /**
     * 用户最近练过的动作（AI 教练的「偏好池」）。
     *
     * 传最近练过的动作进去，AI 排计划时才有机会沿用「他已经会做的动作」，
     * 而不是每次都从零推荐陌生动作。DISTINCT 是因为一个动作会出现在多场训练里。
     */
    @Query(
        """
        SELECT DISTINCT e.* FROM exercises e
        JOIN workout_set_logs s ON s.exerciseId = e.id
        JOIN workout_sessions w ON w.sessionId = s.sessionId
        WHERE w.startTime >= :since
        LIMIT :limit
        """
    )
    suspend fun recentlyTrained(since: Long, limit: Int): List<ExerciseEntity>

    @Query("SELECT id FROM exercises WHERE isMastered = 1")
    suspend fun masteredIds(): List<String>
    @Query("UPDATE exercises SET isMastered = 1 WHERE id IN (:ids)")
    suspend fun markMastered(ids: List<String>)

    @Query("UPDATE exercises SET isMastered = 0")
    suspend fun clearMastery()

    // ------------------------------------------------------------ 筛选计数

    @Query(
        """
        SELECT category AS groupKey, categoryCn AS label, COUNT(*) AS count
        FROM exercises GROUP BY category ORDER BY count DESC
        """
    )
    fun observeCategoryCounts(): Flow<List<GroupCount>>

    @Query(
        """
        SELECT bodyPart AS groupKey, bodyPartCn AS label, COUNT(*) AS count
        FROM exercises GROUP BY bodyPart ORDER BY count DESC
        """
    )
    fun observeBodyPartCounts(): Flow<List<GroupCount>>

    @Query(
        """
        SELECT branch AS groupKey, branch AS label, COUNT(*) AS count
        FROM exercises GROUP BY branch ORDER BY count DESC
        """
    )
    fun observeBranchCounts(): Flow<List<GroupCount>>

    @Query("SELECT COUNT(*) FROM exercises WHERE category = 'cardio'")
    fun observeCardioCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ExerciseEntity>)

    @Query("DELETE FROM exercises")
    suspend fun clear()

    /**
     * 整库替换。放在一个事务里，中途被打断（进程被杀）也不会留下半套数据，
     * 下次启动 count() 为 0 会重新导入。
     *
     * `isMastered` 是**派生状态**：动作库整表重灌会把它一起冲掉，而技能树上的
     * 「已通关」不应该因为数据包更新而消失。所以在清表前记一份通关 id，写完再补回去。
     */
    @Transaction
    suspend fun replaceAll(items: List<ExerciseEntity>, chunkSize: Int = 200) {
        val mastered = masteredIds().toSet()
        clear()
        items.chunked(chunkSize).forEach { insertAll(it) }
        if (mastered.isNotEmpty()) markMastered(mastered.toList())
    }
}
