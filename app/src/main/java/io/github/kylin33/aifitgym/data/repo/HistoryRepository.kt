package io.github.kylin33.aifitgym.data.repo

import io.github.kylin33.aifitgym.data.local.SessionHeader
import io.github.kylin33.aifitgym.data.local.SetLogRow
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao
import io.github.kylin33.aifitgym.data.local.WorkoutSetLogEntity
import kotlinx.coroutines.flow.Flow

/** 一次训练里同一个动作的所有组。 */
data class HistoryGroup(
    val exerciseId: String,
    val name: String,
    val image: String,
    val sets: List<SetLogRow>,
) {
    /** 一组都没记次数、也没记秒数时按计次处理。 */
    val isTimed: Boolean get() = sets.any { it.actualDurationSec > 0 }

    val totalReps: Int get() = sets.sumOf { it.actualReps }

    val totalSeconds: Int get() = sets.sumOf { it.actualDurationSec }
}

/**
 * 这一组按哪个口径编辑：有秒数就是计时；全空（补记的 0 组）时跟随整组的口径。
 *
 * 判到「单组」而不是「整组」是因为同一动作可能这次计次、那次计时，
 * 按整组口径改会把另一类的成绩清零。
 */
fun SetLogRow.isTimedIn(group: HistoryGroup): Boolean =
    actualDurationSec > 0 || (actualReps == 0 && group.isTimed)

/** 历史详情页需要的全部数据。 */
data class SessionDetail(
    val session: SessionHeader,
    val groups: List<HistoryGroup>,
) {
    val setCount: Int get() = groups.sumOf { it.sets.size }

    val totalReps: Int get() = groups.sumOf { it.totalReps }
}

/**
 * 训练历史的读取与手动修正。
 *
 * 历史页和跟练页读的是同一批表，但历史页多了一件跟练页不需要的事：**改**。
 * 用户做完 12 次却只按到 10 次、或者中途退出导致某组没记，这里补上就行 ——
 * 补的是「当天实际发生了什么」，所以直接改明细，不重算 PR 和技能进度
 * （已经发出去的纪念章不收回，避免改一个数字把整条链的解锁状态改乱）。
 */
class HistoryRepository(
    private val sessionDao: WorkoutSessionDao,
) {

    fun observeSessions(): Flow<List<SessionHeader>> = sessionDao.observeSessionHeaders()

    suspend fun loadDetail(sessionId: Long): SessionDetail? {
        val session = sessionDao.findSession(sessionId)
            ?: return null
        val header = SessionHeader(
            sessionId = session.sessionId,
            routineNameSnapshot = session.routineNameSnapshot,
            startTime = session.startTime,
            endTime = session.endTime,
            totalDurationSec = session.totalDurationSec,
            setCount = 0,
        )
        val rows = sessionDao.setsWithNames(sessionId)
        // 用 LinkedHashMap 保序：动作顺序按库里 setLogId 的先后，也就是当时练的顺序
        val groups = LinkedHashMap<String, MutableList<SetLogRow>>()
        rows.forEach { row -> groups.getOrPut(row.exerciseId) { mutableListOf() }.add(row) }

        return SessionDetail(
            session = header.copy(setCount = rows.size),
            groups = groups.map { (exerciseId, sets) ->
                HistoryGroup(
                    exerciseId = exerciseId,
                    name = sets.first().name,
                    image = sets.first().image,
                    sets = sets,
                )
            },
        )
    }

    /** 修正一组的成绩；次数和秒数一起写，避免切换口径时留下旧值。 */
    suspend fun updateSet(setLogId: Long, reps: Int, seconds: Int) {
        sessionDao.updateSet(setLogId, reps.coerceAtLeast(0), seconds.coerceAtLeast(0))
    }

    /**
     * 补一组。setIndex 接着这一组往后排，值默认沿用最后一组
     * （用户补记时通常就是「再来一组差不多的」）。
     */
    suspend fun addSet(group: HistoryGroup, sessionId: Long): Long {
        val last = group.sets.lastOrNull()
        val nextIndex = (group.sets.maxOfOrNull { it.setIndex } ?: 0) + 1
        return sessionDao.insertSet(
            WorkoutSetLogEntity(
                sessionId = sessionId,
                exerciseId = group.exerciseId,
                setIndex = nextIndex,
                actualReps = last?.actualReps ?: if (group.isTimed) 0 else 10,
                actualDurationSec = last?.actualDurationSec ?: if (group.isTimed) 30 else 0,
            )
        )
    }

    suspend fun deleteSet(setLogId: Long) {
        sessionDao.deleteSetById(setLogId)
    }

    suspend fun deleteSession(sessionId: Long) {
        sessionDao.deleteSessionById(sessionId)
    }

    /**
     * 把中途退出、没有收尾的训练标记为已完成。
     *
     * 时长按「开始到现在」补，上限 6 小时 —— 隔了三天再点，不能算成 72 小时训练。
     */
    suspend fun finishSession(sessionId: Long, now: Long = System.currentTimeMillis()): Boolean {
        val session = sessionDao.findSession(sessionId) ?: return false
        if (session.endTime > 0) return false
        val elapsed = ((now - session.startTime) / 1000L).coerceIn(0L, 6 * 3600L).toInt()
        return sessionDao.markFinished(sessionId, now, elapsed) > 0
    }
}
