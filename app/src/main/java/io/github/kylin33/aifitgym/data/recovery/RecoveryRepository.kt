package io.github.kylin33.aifitgym.data.recovery

import android.content.Context
import io.github.kylin33.aifitgym.data.anatomy.MuscleScoring
import io.github.kylin33.aifitgym.data.anatomy.MuscleTaxonomy
import io.github.kylin33.aifitgym.data.anatomy.MuscleTaxonomyAssets
import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.SetBurst
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao

/**
 * 恢复 / 训练量看板的数据来源。
 *
 * 两份快照的口径差别只有一条：**恢复要乘时间衰减，训练量不乘**。
 * 恢复回答「现在还能不能练它」，所以三天前的 3 组到今天已经基本归零；
 * 训练量回答「这段时间练得均不均衡」，所以 7 天内的组数要一视同仁地累加。
 *
 * 动作 → 肌群的权重统一走 [MuscleScoring.forExercise]（主 1.0 / 副 0.4），
 * 和动作详情页的热力图画的是同一套东西，不会出现「详情说练胸、看板说练肩」。
 */
class RecoveryRepository(
    private val appContext: Context,
    private val sessionDao: WorkoutSessionDao,
    private val exerciseDao: ExerciseDao,
) {

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS

        /**
         * 训练量看板关注的「主力肌群」。
         *
         * 词表里有 18 块，但前锯肌、胫骨前肌这类基本靠不上主动训练，
         * 天天报「没练到」只会变成噪音，所以只盯这 12 块。
         */
        val GAP_WATCHLIST = listOf(
            "chest", "upper-back", "deltoids", "biceps", "triceps",
            "abs", "obliques", "lower-back", "quadriceps", "hamstring", "gluteal", "calves",
        )

        /** 少于「一组主发力」就算没练到。 */
        const val GAP_LOAD = 1f
    }

    // ------------------------------------------------------------ 恢复

    /**
     * 过去 72 小时的肌肉疲劳。
     *
     * 同一动作今天练一次、前天练一次，是两条 [SetBurst]，各自乘自己的衰减系数再相加 ——
     * 这正是「不能在 SQL 里直接 SUM」的原因。
     */
    suspend fun recovery(now: Long = System.currentTimeMillis()): RecoverySnapshot {
        val taxonomy = MuscleTaxonomyAssets.load(appContext)
            ?: return RecoverySnapshot()

        val since = now - (RecoveryPolicy.WINDOW_HOURS * HOUR_MS).toLong()
        val bursts = sessionDao.burstsBetween(since, now)
        if (bursts.isEmpty()) {
            return RecoverySnapshot(muscleCount = taxonomy.muscles.size)
        }

        val exercises = loadExercises(bursts.map { it.exerciseId })

        val load = LinkedHashMap<String, Float>()
        val sets = HashMap<String, Int>()
        val lastTrained = HashMap<String, Long>()

        bursts.forEach { burst ->
            val exercise = exercises[burst.exerciseId] ?: return@forEach
            val hours = (now - burst.startedAt).coerceAtLeast(0L) / HOUR_MS.toFloat()
            val decay = RecoveryPolicy.decayAt(hours)
            // 超过 72 小时的批次 SQL 已经排除，这里只是兜底（时钟回拨等）
            if (decay <= 0f) return@forEach

            MuscleScoring.forExercise(taxonomy, exercise).forEach { (slug, weight) ->
                load[slug] = (load[slug] ?: 0f) + weight * burst.sets * decay
                sets[slug] = (sets[slug] ?: 0) + burst.sets
                lastTrained[slug] = maxOf(lastTrained[slug] ?: 0L, burst.startedAt)
            }
        }

        val loads = load.map { (slug, raw) ->
            val lastAt = lastTrained[slug] ?: now
            MuscleLoad(
                slug = slug,
                nameCn = taxonomy.nameCn(slug),
                nameEn = taxonomy.nameEn(slug),
                sets = sets[slug] ?: 0,
                load = raw,
                fatigue = RecoveryPolicy.fatigueOf(raw),
                lastTrainedAt = lastAt,
                hoursSince = (now - lastAt).coerceAtLeast(0L) / HOUR_MS.toFloat(),
            )
        }.sortedByDescending { it.fatigue }

        return RecoverySnapshot(
            loads = loads,
            scores = loads.associate { it.slug to it.fatigue },
            levels = loads.associate { it.slug to it.level },
            totalSets = bursts.sumOf { it.sets },
            muscleCount = taxonomy.muscles.size,
            lastTrainedAt = bursts.maxOfOrNull { it.startedAt },
        )
    }

    // ------------------------------------------------------------ 训练量

    /** [days] 天内的肌群训练量，7 天看周节奏，30 天看长期偏科。 */
    suspend fun volume(days: Int, now: Long = System.currentTimeMillis()): VolumeSnapshot {
        val taxonomy = MuscleTaxonomyAssets.load(appContext)
            ?: return VolumeSnapshot(days)

        val since = now - days.coerceAtLeast(1) * DAY_MS
        val bursts = sessionDao.burstsBetween(since, now)
        val summary = sessionDao.summaryBetween(since, now)

        if (bursts.isEmpty()) {
            return VolumeSnapshot(
                days = days,
                sessions = summary.sessions,
                durationSec = summary.durationSec,
                gaps = gapsOf(taxonomy, emptyMap()),
            )
        }

        val exercises = loadExercises(bursts.map { it.exerciseId })

        val load = HashMap<String, Float>()
        val sets = HashMap<String, Int>()
        bursts.forEach { burst ->
            val exercise = exercises[burst.exerciseId] ?: return@forEach
            MuscleScoring.forExercise(taxonomy, exercise).forEach { (slug, weight) ->
                load[slug] = (load[slug] ?: 0f) + weight * burst.sets
                sets[slug] = (sets[slug] ?: 0) + burst.sets
            }
        }

        val total = load.values.sum()
        val max = load.values.maxOrNull() ?: 0f

        val muscles = load.map { (slug, value) ->
            MuscleVolume(
                slug = slug,
                nameCn = taxonomy.nameCn(slug),
                nameEn = taxonomy.nameEn(slug),
                sets = sets[slug] ?: 0,
                load = value,
                share = if (total > 0f) value / total else 0f,
                normalized = if (max > 0f) (value / max).coerceIn(0f, 1f) else 0f,
            )
        }.sortedByDescending { it.load }

        return VolumeSnapshot(
            days = days,
            sessions = summary.sessions,
            totalSets = bursts.sumOf { it.sets },
            durationSec = summary.durationSec,
            muscles = muscles,
            gaps = gapsOf(taxonomy, load),
        )
    }

    // ------------------------------------------------------------ 内部

    private suspend fun loadExercises(ids: List<String>): Map<String, ExerciseEntity> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return exerciseDao.findByIds(distinct).associateBy { it.id }
    }

    private fun gapsOf(taxonomy: MuscleTaxonomy, load: Map<String, Float>): List<String> =
        GAP_WATCHLIST
            .filter { (load[it] ?: 0f) < GAP_LOAD }
            .map { taxonomy.nameCn(it) }
}
