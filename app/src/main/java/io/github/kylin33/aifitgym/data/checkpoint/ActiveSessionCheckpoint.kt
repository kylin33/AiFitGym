package io.github.kylin33.aifitgym.data.checkpoint

import io.github.kylin33.aifitgym.data.local.AppMetaDao
import io.github.kylin33.aifitgym.data.local.AppMetaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * 一组已完成动作的检查点副本。
 *
 * 这些数据在 `workout_set_logs` 里其实也有（一组一落库），但仍然写进检查点：
 * 万一用户把那条历史删了、或者恢复备份时表被清空重建，
 * 结算页至少还能把这次训练的过程画出来。
 */
@Serializable
data class CheckpointLog(
    val exerciseId: String,
    val name: String,
    val setIndex: Int,
    val reps: Int = 0,
    val seconds: Int = 0,
    val usedBackup: Boolean = false,
)

/**
 * 检查点里的一个动作条目。
 *
 * 存的是**展开后的执行参数**而不是 routineId 引用：万一用户在中途改了计划、
 * 甚至把计划删了，恢复出来的还是开始训练那一刻的安排，而不是「现在这套」。
 * 这也让考级模式（没有计划、只有单个动作）能走同一条恢复路径。
 */
@Serializable
data class CheckpointEntry(
    val exerciseId: String,
    val backupExerciseId: String? = null,
    val sets: Int = 3,
    /** 计次动作的目标次数；计时动作为 null。 */
    val reps: Int? = null,
    /** 计时动作的目标秒数；计次动作为 null。 */
    val seconds: Int? = null,
    val restSeconds: Int = 60,
)

/**
 * 「还没结束的训练」的完整快照。
 *
 * 写这个文件解决的是一个很具体的事故：练到第 40 分钟，手机没电 / 系统把进程杀了 /
 * 手滑从最近任务划掉。已完成的那几组不会丢（早就在 `workout_set_logs` 里），
 * 但「练到第几个动作的第几组」只活在 ViewModel 内存里，进程一死就没了 ——
 * 重开 App 只能看着一条没头没尾的历史记录。
 *
 * 所以这里存的是**指针**：练到哪、做到第几组、用没用平替。恢复时接着这个位置往下走。
 *
 * [version] 是为了以后改结构时能识别旧格式：字段改名后旧 JSON 解不出来，
 * 与其让用户看到「继续训练」点进去崩溃，不如直接丢掉这份检查点。
 */
@Serializable
data class ActiveSessionCheckpoint(
    val version: Int = VERSION,
    val sessionId: Long,
    /** 原计划 id；考级模式（单动作）为 0。 */
    val routineId: Long = 0L,
    val routineName: String = "",
    /** 开始时刻（墙上时间），只用于横幅上显示「几点开始的」。 */
    val startedAtWall: Long = 0L,
    /** 已经练了多久（毫秒）。恢复时用它接着算，不会把关机的那段时间算进去。 */
    val elapsedMs: Long = 0L,
    val entryIndex: Int = 0,
    val setIndex: Int = 1,
    val useBackup: Boolean = false,
    /** 当前动作临时改过的次数（用户 +/- 调过）。 */
    val reps: Int = 0,
    val entries: List<CheckpointEntry> = emptyList(),
    val logs: List<CheckpointLog> = emptyList(),
    /** 最后一次写入时间，用于「N 分钟前」这类文案。 */
    val savedAtWall: Long = 0L,
) {
    val totalSets: Int get() = entries.sumOf { it.sets.coerceAtLeast(1) }

    val completedSets: Int get() = logs.size

    val progress: Float
        get() = if (totalSets == 0) 0f else (completedSets.toFloat() / totalSets).coerceIn(0f, 1f)

    val currentEntry: CheckpointEntry? get() = entries.getOrNull(entryIndex)

    /** 横幅上那句「练到哪了」。 */
    val positionLabel: String
        get() {
            val entry = currentEntry ?: return "还没开始"
            return "第 ${entryIndex + 1}/${entries.size} 个动作 · 第 $setIndex/${entry.sets.coerceAtLeast(1)} 组"
        }

    companion object {
        const val VERSION = 1
    }
}

/**
 * 检查点的读写。
 *
 * 落在 `app_meta` 而不是新建一张表：一张只有一行、还可能随时被覆盖的表，
 * 不值得为它写一次数据库迁移（Room 的版本号一涨就得维护迁移链）。
 * 键值表本来就擅长存这种「一次性状态」，AI 对话记录也是同样的存法。
 *
 * 为什么不做防抖：一次训练只会写 20 次左右（每组一次），
 * 每次几百字节的一个 UPSERT，比任何节流逻辑都便宜。
 */
class CheckpointStore(private val dao: AppMetaDao) {

    /**
     * 让首页横幅能自己出现、自己消失。
     *
     * 用 Flow 监听而不是每次进首页读一遍：训练结束清掉检查点后，
     * 横幅必须在**不重建 ViewModel 的情况下**消失，否则返回首页还会看到
     * 「有一场训练没做完」——而它刚刚才做完。
     */
    fun observe(): Flow<ActiveSessionCheckpoint?> =
        dao.observe(KEY).map { decode(it) }

    suspend fun load(): ActiveSessionCheckpoint? = decode(dao.get(KEY))

    suspend fun save(checkpoint: ActiveSessionCheckpoint) {
        runCatching {
            dao.put(AppMetaEntity(KEY, Json.encodeToString(ActiveSessionCheckpoint.serializer(), checkpoint)))
        }
    }

    suspend fun clear() {
        runCatching { dao.remove(KEY) }
    }

    /**
     * 解析失败一律当成「没有检查点」。
     *
     * 宁可少一次「继续训练」的机会（已完成组还在历史里，可以手动补完），
     * 也不能让首页因为一条坏 JSON 直接崩掉。版本号对不上同理。
     */
    private fun decode(raw: String?): ActiveSessionCheckpoint? {
        if (raw.isNullOrBlank()) return null
        val parsed = runCatching {
            Json.decodeFromString(ActiveSessionCheckpoint.serializer(), raw)
        }.getOrNull() ?: return null
        return parsed.takeIf { it.version == ActiveSessionCheckpoint.VERSION && it.sessionId > 0L }
    }

    companion object {
        /** 与 DataResetRepository / 备份恢复里清理用的键保持一致。 */
        const val KEY = "active_session_checkpoint"

        private val Json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
