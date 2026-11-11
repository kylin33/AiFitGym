package io.github.kylin33.aifitgym.data.repo

import androidx.room.withTransaction
import io.github.kylin33.aifitgym.data.local.AppMetaDao
import io.github.kylin33.aifitgym.data.local.BranchProgressDao
import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.FitGymDatabase
import io.github.kylin33.aifitgym.data.local.RoutineDao
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao
import io.github.kylin33.aifitgym.data.seeding.PresetRoutineSeeder
import io.github.kylin33.aifitgym.data.skill.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 清空本地数据，把 App 还原成「刚装好」的状态。
 *
 * 两个真实场景：
 *  1. 停了半年想重新开始 —— 旧记录、旧计划、半截的技能进度都是负担，
 *     从头来一遍比对着 Lv.5 的考级要求更有动力；
 *  2. 手机要交给别人 —— 训练历史和身体数据不该跟着手机走。
 *
 * ## 保留什么
 * 动作库（`exercises` 那 2303 条）一条都不删：它是随 APK 发行的资源，
 * 删了下次启动还得从 assets 重灌 2.6 秒，没有任何好处。只把 48 个通关标记清掉。
 *
 * ## 清完要「补回初始态」而不是「留个空壳」
 * 清空后自动重放一次首启逻辑：6 条流派账本补到 Lv.1、3 套内置模板重新灌回。
 * 否则用户面对的是一个连计划都没有的空首页，还得等下次启动才恢复。
 */
class DataResetRepository(
    private val db: FitGymDatabase,
    private val appMetaDao: AppMetaDao,
    private val exerciseDao: ExerciseDao,
    private val routineDao: RoutineDao,
    private val sessionDao: WorkoutSessionDao,
    private val progressDao: BranchProgressDao,
    private val presetSeeder: PresetRoutineSeeder,
    private val skillRepository: SkillRepository,
) {

    suspend fun clearAll(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            db.withTransaction {
                // 顺序被外键绑死：明细 → 总表 → 条目 → 计划
                sessionDao.clearSets()
                sessionDao.clearSessions()
                routineDao.clearItems()
                routineDao.clearRoutines()
                progressDao.clearAll()
                exerciseDao.clearMastery()
                // 删掉模板标记，seedIfNeeded 才会认为「还没灌过」
                appMetaDao.remove(PresetRoutineSeeder.KEY_VERSION)
                // AI 对话是基于旧训练数据聊出来的，清完数据再留着它只会误导下一位用户；
                // 模型/密钥属于「设置」不是「数据」，保留（谁都不想重填一次 Key）
                appMetaDao.remove(AI_CHAT_KEY)
            }

            // 事务外重放首启的那两步：它们本身幂等，正是为这种场合准备的。
            // 用内层 runCatching 兜住：补初始态失败不该让「已清空」这个既成事实报成失败
            // （用户会以为数据还在，其实已经没了）。缺的模板和账本下次启动 bootstrap 会补齐。
            runCatching {
                skillRepository.ensureInitialized()
                presetSeeder.seedIfNeeded()
            }
            Unit
        }
    }

    private companion object {
        /** 与 CoachChatStore 里的键保持一致；重复一个字面量比让两个包互相依赖更省事。 */
        const val AI_CHAT_KEY = "ai_coach_chat"
    }
}
