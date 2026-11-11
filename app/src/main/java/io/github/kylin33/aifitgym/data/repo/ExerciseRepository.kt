package io.github.kylin33.aifitgym.data.repo

import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.GroupCount
import io.github.kylin33.aifitgym.data.skill.SkillBranch
import kotlinx.coroutines.flow.Flow

/**
 * 列表页当前的筛选条件；null 表示不限。
 *
 * [bodyweightOnly] 默认 **开**：2303 条里绝大多数是徒手动作，一上来就全列出来没法用。
 * 用户想找器械动作时关掉开关即可。
 */
data class ExerciseFilter(
    val bodyPart: String? = null,
    val category: String? = null,
    val branch: SkillBranch? = null,
    val bodyweightOnly: Boolean = true,
    val keyword: String = "",
)

class ExerciseRepository(private val dao: ExerciseDao) {

    fun observeExercises(filter: ExerciseFilter): Flow<List<ExerciseEntity>> =
        dao.observeFiltered(
            bodyPart = filter.bodyPart,
            category = filter.category,
            branch = filter.branch?.key,
            bodyweightOnly = filter.bodyweightOnly,
            keyword = filter.keyword.trim().lowercase(),
        )

    fun observeExercise(id: String): Flow<ExerciseEntity?> = dao.observeById(id)

    suspend fun find(id: String): ExerciseEntity? = dao.findById(id)

    fun observeCategories(): Flow<List<GroupCount>> = dao.observeCategoryCounts()

    fun observeBodyParts(): Flow<List<GroupCount>> = dao.observeBodyPartCounts()

    fun observeBranches(): Flow<List<GroupCount>> = dao.observeBranchCounts()

    fun observeCardioCount(): Flow<Int> = dao.observeCardioCount()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun count(): Int = dao.count()
}
