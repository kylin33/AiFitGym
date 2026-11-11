package io.github.kylin33.aifitgym.data.seeding

import android.util.Log
import io.github.kylin33.aifitgym.data.local.AppMetaDao
import io.github.kylin33.aifitgym.data.local.AppMetaEntity
import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.RoutineDao
import io.github.kylin33.aifitgym.data.local.RoutineEntity
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把内置计划模板灌进库，让首页不至于空白。
 *
 * 三个保证：
 *  1. 幂等：库里已有任何内置计划（`presetKey` 非空）就整体跳过，用互斥锁挡住并发启动；
 *  2. 不灌空计划：先用动作库里的真实 ID 校验，缺动作的条目直接跳过；
 *  3. 不覆盖用户数据：只新增，不动用户自建的计划。
 */
class PresetRoutineSeeder(
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
    private val appMetaDao: AppMetaDao,
) {

    private val mutex = Mutex()

    suspend fun seedIfNeeded(): Int = mutex.withLock {
        // 用版本号做标记而不是「数一下有没有内置计划」：
        // 后者会导致用户删掉模板后每次启动又被塞回来。
        if (appMetaDao.get(KEY_VERSION) == VERSION) return@withLock 0

        if (routineDao.presetCount() > 0) {
            // 计划已经在（比如从旧版本升级上来但标记丢了），补标记就好，别重复插
            appMetaDao.put(AppMetaEntity(KEY_VERSION, VERSION))
            return@withLock 0
        }

        val knownIds = exerciseDao.allIds().toSet()
        if (knownIds.isEmpty()) {
            Log.w(TAG, "动作库还是空的，跳过内置计划模板")
            return@withLock 0
        }

        var inserted = 0
        RoutinePresets.all.forEach { preset ->
            val items = preset.items.filter { it.exerciseId in knownIds }
            if (items.isEmpty()) {
                Log.w(TAG, "模板 ${preset.key} 的动作一个都不在库里，跳过")
                return@forEach
            }
            val missing = preset.items.size - items.size
            if (missing > 0) Log.w(TAG, "模板 ${preset.name} 有 $missing 个动作缺失，已跳过对应条目")

            routineDao.save(
                routine = RoutineEntity(
                    name = preset.name,
                    description = preset.description,
                    category = preset.category,
                    presetKey = preset.key,
                ),
                items = items.map { item ->
                    RoutineExerciseEntity(
                        routineId = 0,
                        exerciseId = item.exerciseId,
                        orderIndex = 0,
                        targetSets = item.sets,
                        targetReps = item.reps,
                        targetDurationSec = item.durationSec,
                        restDurationSec = item.restSec,
                        backupExerciseId = item.backupExerciseId?.takeIf { it in knownIds },
                    )
                },
            )
            inserted++
        }
        appMetaDao.put(AppMetaEntity(KEY_VERSION, VERSION))
        Log.i(TAG, "内置计划模板灌入完成：$inserted 套")
        inserted
    }

    companion object {
        private const val TAG = "PresetRoutineSeeder"

        /** 模板内容变了就 +1，下次启动会重新补一遍（已有的内置计划仍在，不会重名覆盖）。 */
        private const val VERSION = "1"

        /**
         * 标记键。
         *
         * `internal` 是给「清空数据」用的：清空后删掉这条标记，
         * 下次 [seedIfNeeded] 就会把三套模板重新灌回来，等于回到刚装好的状态。
         */
        internal const val KEY_VERSION = "preset_routines_version"
    }
}
