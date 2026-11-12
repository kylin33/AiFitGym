package io.github.kylin33.aifitgym.di

import android.content.ContentResolver
import android.content.Context
import io.github.kylin33.aifitgym.audio.WorkoutAudioManager
import io.github.kylin33.aifitgym.data.ai.AiSettingsRepository
import io.github.kylin33.aifitgym.data.ai.CoachChatStore
import io.github.kylin33.aifitgym.data.ai.CoachContextBuilder
import io.github.kylin33.aifitgym.data.ai.CoachRepository
import io.github.kylin33.aifitgym.data.backup.BackupRepository
import io.github.kylin33.aifitgym.data.checkpoint.CheckpointStore
import io.github.kylin33.aifitgym.data.local.FitGymDatabase
import io.github.kylin33.aifitgym.data.recovery.RecoveryRepository
import io.github.kylin33.aifitgym.data.repo.DataResetRepository
import io.github.kylin33.aifitgym.data.repo.ExerciseRepository
import io.github.kylin33.aifitgym.data.repo.HistoryRepository
import io.github.kylin33.aifitgym.data.repo.RoutineRepository
import io.github.kylin33.aifitgym.data.repo.WorkoutRepository
import io.github.kylin33.aifitgym.data.seeding.CatalogueSeeder
import io.github.kylin33.aifitgym.data.seeding.PresetRoutineSeeder
import io.github.kylin33.aifitgym.data.skill.SkillRepository
import io.github.kylin33.aifitgym.service.WorkoutTimerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手动依赖容器：依赖不多，没必要上 Hilt。
 * 生命周期跟随 Application，数据库与 Seeder 都是单例。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** 应用级作用域：建库回调、内置数据导入都跑在这里，不随任何页面销毁。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Seeder 故意不持有数据库：数据库的建库回调里要反过来调 Seeder，
     * 两边互相持有就成环了，这里用「方法传库」的方式打断。
     */
    val catalogueSeeder: CatalogueSeeder by lazy { CatalogueSeeder(appContext) }

    val database: FitGymDatabase by lazy {
        FitGymDatabase.create(appContext, appScope) { db ->
            // 第一次建库时自动导入；失败会在 ImportState 里体现，UI 可以重试
            catalogueSeeder.importInto(db)
        }
    }

    val exerciseRepository: ExerciseRepository by lazy {
        ExerciseRepository(database.exerciseDao())
    }

    val routineRepository: RoutineRepository by lazy {
        RoutineRepository(database.routineDao())
    }

    val workoutRepository: WorkoutRepository by lazy {
        WorkoutRepository(database.routineDao(), database.exerciseDao(), database.workoutSessionDao())
    }

    /**
     * 未完成训练的检查点。
     *
     * 每组打卡时写一份，正常收尾时删掉；进程被杀之后首页靠它弹「继续训练」。
     * 落 app_meta（键值表）而不是新建表，就省掉一次 Room 迁移。
     */
    val checkpoints: CheckpointStore by lazy {
        CheckpointStore(database.appMetaDao())
    }

    val skillRepository: SkillRepository by lazy {
        SkillRepository(
            progressDao = database.branchProgressDao(),
            exerciseDao = database.exerciseDao(),
            sessionDao = database.workoutSessionDao(),
            routineDao = database.routineDao(),
        )
    }

    /** 恢复 / 训练量看板：要读 assets 里的肌群词表，所以带上 Context。 */
    val recoveryRepository: RecoveryRepository by lazy {
        RecoveryRepository(
            appContext = appContext,
            sessionDao = database.workoutSessionDao(),
            exerciseDao = database.exerciseDao(),
        )
    }

    /** 训练历史：列表读取 + 手动修正组明细。 */
    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(database.workoutSessionDao())
    }

    /**
     * 全量备份：把用户数据打包成一个 JSON，或者反过来覆盖恢复。
     *
     * 备份不碰动作库 —— 那 2303 条随 APK 发行、会随版本重灌，装进备份只会让恢复变脏。
     */
    val backupRepository: BackupRepository by lazy {
        BackupRepository(
            db = database,
            exerciseDao = database.exerciseDao(),
            routineDao = database.routineDao(),
            sessionDao = database.workoutSessionDao(),
            progressDao = database.branchProgressDao(),
            appMetaDao = database.appMetaDao(),
        )
    }

    /**
     * 读写「用户选中的那个文件」用。
     *
     * ContentResolver 不是 Context，可以放心交给 ViewModel —— SAF 的 Uri 只在这一层能打开，
     * 而文件 IO 放在 ViewModel 里才能扛住页面重组（Compose 的 rememberCoroutineScope 会被配置变更取消）。
     */
    val contentResolver: ContentResolver get() = appContext.contentResolver

    /**
     * 跟练提示音：SoundPool + 音频闪避（ducking）+ 震动。
     *
     * 做成 Application 级单例：wav 只解码一次，跟练页反复进出不重复加载；
     * 焦点状态也不会因为换了个 ViewModel 就被半路丢掉。
     */
    val audio: WorkoutAudioManager by lazy {
        WorkoutAudioManager(appContext)
    }

    /**
     * 跟练计时的前台服务通道。
     *
     * ViewModel 只管往里投快照，由它负责拉服务、撤通知 —— ViewModel 里不出现 Context。
     */
    val workoutTimer: WorkoutTimerController by lazy {
        WorkoutTimerController(appContext)
    }

    /**
     * AI 教练的配置：Base URL / 模型 / 训练目标走普通 prefs，
     * API Key 走 Keystore 加密存储（见 AiSecretStore）。
     */
    val aiSettings: AiSettingsRepository by lazy {
        AiSettingsRepository(appContext)
    }

    /**
     * AI 教练：拼候选池 → 调 OpenAI 兼容网关 → 解析计划。
     *
     * 单例是必要的：客户端持有连接超时等状态，而每轮请求都要重新读一遍数据库，
     * 所以它本身不缓存数据，只做编排。
     */
    val coachRepository: CoachRepository by lazy {
        CoachRepository(
            exerciseDao = database.exerciseDao(),
            routineDao = database.routineDao(),
            settings = aiSettings,
            contextBuilder = CoachContextBuilder(
                exerciseDao = database.exerciseDao(),
                sessionDao = database.workoutSessionDao(),
                progressDao = database.branchProgressDao(),
            ),
        )
    }

    /** 对话记录（存 app_meta，清空数据时一并消失）。 */
    val coachChatStore: CoachChatStore by lazy {
        CoachChatStore(database.appMetaDao())
    }

    /** 内置计划模板：动作库灌好之后才有得校验，所以放在 bootstrap 里串行执行。 */
    private val presetRoutineSeeder: PresetRoutineSeeder by lazy {
        PresetRoutineSeeder(database.routineDao(), database.exerciseDao(), database.appMetaDao())
    }

    /**
     * 清空所有训练数据，回到刚装好的状态。
     *
     * 依赖 [presetRoutineSeeder] 和 [skillRepository]，是因为清完要立刻把
     * 「3 套模板 + 6 条 Lv.1 流派账本」补回来，而不是留一个空首页。
     */
    val dataResetRepository: DataResetRepository by lazy {
        DataResetRepository(
            db = database,
            appMetaDao = database.appMetaDao(),
            exerciseDao = database.exerciseDao(),
            routineDao = database.routineDao(),
            sessionDao = database.workoutSessionDao(),
            progressDao = database.branchProgressDao(),
            presetSeeder = presetRoutineSeeder,
            skillRepository = skillRepository,
        )
    }

    /**
     * 启动时调一次：打开数据库（首启会触发 onCreate 导入），
     * 并给「库已存在、不会触发 onCreate」的情况补一个幂等检查。
     */
    fun bootstrap() {
        appScope.launch {
            catalogueSeeder.importInto(database)
            presetRoutineSeeder.seedIfNeeded()
            // 技能树的起点解锁：纯本地、无网络，放在最后补齐
            skillRepository.ensureInitialized()
        }
    }
}
