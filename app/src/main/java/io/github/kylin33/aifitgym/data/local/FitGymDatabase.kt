package io.github.kylin33.aifitgym.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 本地库。四张业务表 —— 动作库、训练计划、计划条目、训练记录/明细 —— 加两张小表
 * （流派熟练度、键值元信息）。
 *
 * 首次建库时通过 [RoomDatabase.Callback.onCreate] 把内置动作库灌进去，见 [create]；
 * 内置计划模板由 [io.github.kylin33.aifitgym.data.seeding.PresetRoutineSeeder] 在启动时幂等补齐。
 *
 * 版本历史：
 *  - v1 计划只有 position/sets/reps/restSeconds
 *  - v2 计划拆成 routines + routine_exercises，补 targetReps / targetDurationSec / backupExerciseId
 *  - v3 打卡记录改成「workout_sessions 总表 + workout_set_logs 明细」
 *  - v4 加技能树解锁状态 `skill_progress`
 *  - v5 技能树从 4 链 × 5 阶扩到 6 链 × 8 阶，进度表主键换成 nodeId
 *  - v6 **技能树模型重做**：不再有节点表。48 个主干动作直接在 `exercises` 上用
 *    `branch` + `skillLevel` 标记，条件（组数/目标值/口径）也存在动作行上；
 *    进度收成 `branch_progress` 六行（当前阶 + 经验）。见 [MIGRATION_5_6]。
 */
@Database(
    entities = [
        ExerciseEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        WorkoutSessionLogEntity::class,
        WorkoutSetLogEntity::class,
        BranchProgressEntity::class,
        AppMetaEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(
    StringListConverter::class,
    RoutineCategoryConverter::class,
)
abstract class FitGymDatabase : RoomDatabase() {

    abstract fun exerciseDao(): ExerciseDao

    abstract fun routineDao(): RoutineDao

    abstract fun workoutSessionDao(): WorkoutSessionDao

    abstract fun branchProgressDao(): BranchProgressDao

    abstract fun appMetaDao(): AppMetaDao

    companion object {
        private const val TAG = "FitGymDatabase"
        private const val NAME = "fitgym.db"

        /**
         * @param scope 应用级协程作用域（IO），用于在后台完成首启导入。
         * @param onFirstCreate 建库回调：数据库文件第一次创建时触发，参数是刚建好的库。
         *
         * 这里有个必须注意的点：`onCreate` 是在「建库的写事务」里同步回调的，
         * 在里面做几百毫秒的解析会直接卡住首次查询，所以只把任务丢给 IO 协程就立刻返回。
         */
        fun create(
            context: Context,
            scope: CoroutineScope,
            onFirstCreate: suspend (FitGymDatabase) -> Unit,
        ): FitGymDatabase {
            val appContext = context.applicationContext

            lateinit var database: FitGymDatabase

            val bootstrap = object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    Log.i(TAG, "首次创建数据库，开始导入内置动作库")
                    scope.launch {
                        runCatching { onFirstCreate(database) }
                            .onFailure { Log.e(TAG, "首启导入内置动作库失败", it) }
                    }
                }
            }

            database = Room.databaseBuilder(appContext, FitGymDatabase::class.java, NAME)
                .addCallback(bootstrap)
                .addMigrations(MIGRATION_5_6)
                // 动作库是 assets 里的可再生数据，改结构时直接重建比写迁移划算；
                // v5 之前的库进来会走这条（训练历史会丢，开发期可接受）
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

            return database
        }

        /**
         * v5 → v6：技能树模型重做。
         *
         * 三件事：
         * 1. 每组的成绩数据不动，训练历史、计划、条目全部保留 —— 这次改的是「怎么组织动作」，
         *    不该牵连用户记录。
         * 2. `skill_progress` 里的通关进度尽量换算成 `branch_progress.currentTier`：
         *    老节点 id 是 `push-3` 这种格式，取出最大阶 + 1 就是新的当前阶。
         *    换算不出来（格式不对）就从 Lv.1 开始，反正 XP 是新的维度，本来也没有历史。
         * 3. `exercises` 直接重建：新增 8 列且全部需要脚本重新打标，
         *    原地 ALTER 加一堆带默认值的空列毫无意义，重建后由 `CatalogueSeeder`
         *    在启动时从 assets 重灌（2303 条，约 2.6 秒）。
         *
         * 建表语句必须与 Room 生成的 schema 逐字一致，否则启动会抛 IllegalStateException。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()

                // ---- 1. 老进度换算成流派当前阶
                val carried = mutableMapOf<String, Int>()
                db.query(
                    "SELECT nodeId, state FROM skill_progress WHERE state = 'MASTERED'"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val nodeId = cursor.getString(0) ?: continue
                        val dash = nodeId.lastIndexOf('-')
                        if (dash <= 0) continue
                        val branch = branchKeyOf(nodeId.substring(0, dash))
                        val tier = nodeId.substring(dash + 1).toIntOrNull() ?: continue
                        carried[branch] = maxOf(carried[branch] ?: 1, (tier + 1).coerceAtMost(8))
                    }
                }

                // ---- 2. 动作库重建（新增 8 列，需要脚本重新打标）
                db.execSQL("DROP TABLE IF EXISTS `exercises`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exercises` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`nameEn` TEXT NOT NULL, " +
                        "`bodyPart` TEXT NOT NULL, " +
                        "`bodyPartCn` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`categoryCn` TEXT NOT NULL, " +
                        "`equipment` TEXT NOT NULL, " +
                        "`equipmentCn` TEXT NOT NULL, " +
                        "`target` TEXT NOT NULL, " +
                        "`targetCn` TEXT NOT NULL, " +
                        "`secondaryMuscles` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`steps` TEXT NOT NULL, " +
                        "`stepsInChinese` INTEGER NOT NULL, " +
                        "`image` TEXT, " +
                        "`searchKey` TEXT NOT NULL, " +
                        "`branch` TEXT NOT NULL, " +
                        "`skillLevel` INTEGER NOT NULL, " +
                        "`unlockType` TEXT NOT NULL, " +
                        "`unlockSets` INTEGER NOT NULL, " +
                        "`unlockValue` INTEGER NOT NULL, " +
                        "`unlockText` TEXT NOT NULL, " +
                        "`isMastered` INTEGER NOT NULL, " +
                        "`isBodyweightFriendly` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercises_bodyPart` ON `exercises` (`bodyPart`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercises_category` ON `exercises` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercises_name` ON `exercises` (`name`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exercises_branch_skillLevel` " +
                        "ON `exercises` (`branch`, `skillLevel`)"
                )

                // ---- 3. 流派熟练度：六行，缺的按 Lv.1 起步
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `branch_progress` (" +
                        "`branch` TEXT NOT NULL, " +
                        "`currentTier` INTEGER NOT NULL, " +
                        "`totalXp` INTEGER NOT NULL, " +
                        "`xpSpent` INTEGER NOT NULL, " +
                        "`discountPercent` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`branch`))"
                )
                listOf("PUSH", "PULL", "LEGS", "CORE", "HIIT", "CARDIO").forEach { branch ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO `branch_progress` " +
                            "(branch, currentTier, totalXp, xpSpent, discountPercent, updatedAt) " +
                            "VALUES (?, ?, 0, 0, 0, ?)",
                        arrayOf<Any>(branch, carried[branch] ?: 1, now),
                    )
                }

                // ---- 4. 老节点表退休
                db.execSQL("DROP TABLE IF EXISTS `skill_progress`")
            }

            /** 老节点 id 的前缀是链的英文名，新表用全大写。 */
            private fun branchKeyOf(legacy: String): String = when (legacy) {
                "push" -> "PUSH"
                "pull" -> "PULL"
                "squat" -> "LEGS"
                "core" -> "CORE"
                "cardio" -> "HIIT"
                "endurance" -> "CARDIO"
                else -> "PUSH"
            }
        }
    }
}
