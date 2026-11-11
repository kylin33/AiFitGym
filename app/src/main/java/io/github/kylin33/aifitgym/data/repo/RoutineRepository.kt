package io.github.kylin33.aifitgym.data.repo

import io.github.kylin33.aifitgym.data.local.RoutineDao
import io.github.kylin33.aifitgym.data.local.RoutineEntity
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineSummary
import kotlinx.coroutines.flow.Flow

/** 计划编排页加载出来的一份完整计划。 */
data class RoutineDetail(
    val routine: RoutineEntity,
    val items: List<RoutineExerciseEntity>,
)

class RoutineRepository(private val dao: RoutineDao) {

    fun observeSummaries(): Flow<List<RoutineSummary>> = dao.observeSummaries()

    fun observeRoutine(routineId: Long): Flow<RoutineEntity?> = dao.observeById(routineId)

    fun observeItems(routineId: Long): Flow<List<RoutineExerciseEntity>> = dao.observeItems(routineId)

    suspend fun loadDetail(routineId: Long): RoutineDetail? {
        val routine = dao.find(routineId) ?: return null
        return RoutineDetail(routine, dao.findItems(routineId))
    }

    suspend fun save(routine: RoutineEntity, items: List<RoutineExerciseEntity>): Long =
        dao.save(routine, items)

    suspend fun delete(routineId: Long) = dao.deleteCascade(routineId)

    /** 撤销删除：把 [RoutineDetail] 这份快照按原 id 写回去。 */
    suspend fun restore(detail: RoutineDetail) = dao.restore(detail.routine, detail.items)

    suspend fun activeCount(): Int = dao.activeCount()
}
