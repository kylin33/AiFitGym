package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.BranchProgressDao
import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao
import io.github.kylin33.aifitgym.data.skill.SkillBranch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每次请求前临时拼出来的「教练简报」。
 *
 * [snapshot] 进第二个 system 消息，[poolText] 进主 system 消息。两者都不进对话历史，
 * 这样用户看到的气泡始终是干净的教练话术，而每轮请求又都能拿到最新数据。
 */
data class CoachContext(
    val snapshot: String,
    val poolText: String,
    val poolSize: Int,
    val stats: TrainingStats = TrainingStats(
        last7 = WindowStats(days = 7),
        prev7 = WindowStats(days = 7),
        last30 = WindowStats(days = 30),
    ),
)

/**
 * 上下文与候选池构建。
 *
 * ## 为什么要有「候选池」这一层
 *
 * 库里 2303 个动作，全塞进 Prompt 是十万级 token，一次对话几毛钱起步；
 * 但如果不给动作列表，模型会编出「钻石俯卧撑」这种库里没有的名字，
 * 解析时就只能丢掉，用户看到的计划卡片会缺图、肌群热力图也对不上。
 *
 * 折中方案是把每轮候选压到 ~80 条（id + 名字 + 计次/计时），约 1.2k token：
 *  - 必选：48 个主干动作，保证 AI 能接上技能树的进阶路线；
 *  - 偏好：最近 30 天练过的动作（沿用他会做的）；
 *  - 补充：当前主攻流派的支线动作（同肌群、徒手、排除拉伸）。
 *
 * 只给 id 不给英文名，是因为英文名对中文模型的取舍没有帮助，白占 token。
 */
class CoachContextBuilder(
    private val exerciseDao: ExerciseDao,
    private val sessionDao: WorkoutSessionDao,
    private val progressDao: BranchProgressDao,
    private val statsBuilder: TrainingStatsBuilder,
) {

    private val dayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

    suspend fun build(preferences: AiPreferences): CoachContext {
        val now = System.currentTimeMillis()
        val since30 = now - 30L * 24 * 3600 * 1000

        val bosses = runCatching { exerciseDao.allBosses() }.getOrDefault(emptyList())
        val progress = runCatching { progressDao.all() }.getOrDefault(emptyList())

        // 主攻流派 = 累计经验最猛的那条；没有记录就按推力
        val focus = progress.maxByOrNull { it.totalXp }?.branch ?: SkillBranch.PUSH.key
        val focusTier = progress.firstOrNull { it.branch == focus }?.currentTier ?: 1
        val focusTarget = bosses
            .firstOrNull { it.branch == focus && it.skillLevel == focusTier }
            ?.target
            .orEmpty()

        val recent = runCatching { exerciseDao.recentlyTrained(since30, 20) }.getOrDefault(emptyList())
        val accessory = runCatching { exerciseDao.accessoryPool(focus, focusTarget, 18) }
            .getOrDefault(emptyList())

        val pool = (bosses + recent + accessory)
            .distinctBy { it.id }
            .take(MAX_POOL)

        // 统计与简报分开算：统计只依赖 sessionDao，简报还要动作库和进度
        val stats = statsBuilder.build(now)

        return CoachContext(
            snapshot = buildSnapshot(preferences, progress, bosses, since30) +
                "\n\n" + stats.toPromptText(),
            poolText = pool.joinToString("\n") { it.toPoolLine() },
            poolSize = pool.size,
            stats = stats,
        )
    }

    // ------------------------------------------------------------ 简报

    private suspend fun buildSnapshot(
        preferences: AiPreferences,
        progress: List<io.github.kylin33.aifitgym.data.local.BranchProgressEntity>,
        bosses: List<ExerciseEntity>,
        since30: Long,
    ): String {
        val profile = preferences.profile
        val sb = StringBuilder()

        sb.append("【用户】")
        sb.append("目标 ").append(profile.goal.trim().ifBlank { "未填（排计划前先问）" })
        sb.append("｜水平 ").append(sourceMark(profile, ProfileField.LEVEL, profile.level))
        if (profile.experience.isNotBlank()) {
            sb.append("｜经验 ").append(profile.experience)
        }
        sb.append("｜每周 ")
            .append(sourceMark(profile, ProfileField.DAYS_PER_WEEK, "${profile.daysPerWeek} 次"))
        sb.append("｜单次 ")
            .append(sourceMark(profile, ProfileField.SESSION_MINUTES, "${profile.sessionMinutes} 分钟"))
        sb.append("｜能练 ").append(
            if (profile.trainingDays.isEmpty()) {
                "未指定"
            } else {
                profile.trainingDays.joinToString("/") { weekLabel(it) }
            },
        )
        sb.append('\n')

        // 伤病单独一行并且放最前面：它是提示词里的红线，混在器械备注里会被漏掉
        sb.append("【伤病 / 疼痛 / 禁忌】")
            .append(profile.limitations.trim().ifBlank { "未填" })
            .append('\n')
        sb.append("【可用器械】")
            .append(profile.equipment.joinToString("、").ifBlank { "未填" })
            .append('\n')
        if (profile.preferredFocus.isNotEmpty()) {
            sb.append("【偏好】").append(profile.preferredFocus.joinToString("、")).append('\n')
        }
        if (profile.availableSpace.isNotBlank()) {
            sb.append("【训练空间】").append(profile.availableSpace.trim()).append('\n')
        }
        if (profile.notes.isNotBlank()) {
            sb.append("【其他备注】").append(profile.notes.trim()).append('\n')
        }
        // 关键项缺口直接点名，省得模型自己扫一遍还漏 —— 这是「档案完整度」唯一的用处
        val missing = profile.missingCritical()
        if (missing.isNotEmpty()) {
            sb.append("【档案缺口】")
                .append(missing.joinToString("、") { ProfileField.label(it) })
                .append(" 还没填，上面的值是默认值不是他说的\n")
        }

        // 流派进度：只写有数据的，6 条全写上占地方
        if (progress.isNotEmpty()) {
            val text = SkillBranch.entries.mapNotNull { branch ->
                val row = progress.firstOrNull { it.branch == branch.key } ?: return@mapNotNull null
                "${branch.label} Lv.${row.currentTier}"
            }.joinToString("｜")
            val mastered = bosses.count { it.isMastered }
            sb.append("【流派进度】").append(text)
            sb.append("｜已通关主干 ").append(mastered).append("/").append(bosses.size)
            sb.append('\n')
        }

        // 最近训练：5 场、每场最多 5 个动作
        val sessions = runCatching { sessionDao.recentFinishedSessions(5) }.getOrDefault(emptyList())
        if (sessions.isNotEmpty()) {
            val sets = runCatching {
                sessionDao.setsOfSessions(sessions.map { it.sessionId })
            }.getOrDefault(emptyList())
            val byId = sets.groupBy { it.sessionId }

            sb.append("【最近 ").append(sessions.size).append(" 次训练】\n")
            sessions.forEach { session ->
                val rows = byId[session.sessionId].orEmpty()
                val minutes = session.totalDurationSec / 60
                sb.append(dayFormat.format(Date(session.startTime)))
                sb.append(' ').append(session.routineNameSnapshot.ifBlank { "自由训练" })
                sb.append(" · ").append(minutes).append(" 分钟")
                sb.append(" · ").append(rows.size).append(" 组\n")

                rows.groupBy { it.exerciseId }
                    .entries.take(5)
                    .forEach { (exerciseId, group) ->
                        val name = bossName(bosses, exerciseId)
                        val reps = group.maxOfOrNull { it.actualReps } ?: 0
                        val seconds = group.maxOfOrNull { it.actualDurationSec } ?: 0
                        sb.append("  · ").append(name).append(' ')
                        sb.append(group.size).append(" 组")
                        if (seconds > 0) {
                            sb.append(" × ").append(seconds).append(" 秒")
                        } else if (reps > 0) {
                            sb.append(" × ").append(reps).append(" 次")
                        }
                        sb.append('\n')
                    }
            }
        } else {
            sb.append("【最近训练】没有记录，这是新手或刚清过数据\n")
        }

        // 近 30 天总量 top5
        val volume = runCatching { sessionDao.volumeSince(since30) }.getOrDefault(emptyList())
        if (volume.isNotEmpty()) {
            val text = volume.sortedByDescending { it.sets }.take(5).joinToString("；") { row ->
                val name = bossName(bosses, row.exerciseId)
                "${name} ${row.sets} 组 / ${row.reps} 次"
            }
            sb.append("【近 30 天练得最多】").append(text).append('\n')
        }

        val unfinished = runCatching { sessionDao.unfinishedCount() }.getOrDefault(0)
        if (unfinished > 0) {
            sb.append("【未完成的训练】").append(unfinished)
                .append(" 次中途退出（排计划时注意强度是不是超了）\n")
        }

        return sb.toString().trimEnd()
    }

    /**
     * 给「有默认值」的项标来源。
     *
     * 不标的话，「水平 新手」会被模型当成用户自述 —— 一个练了三年的人
     * 因为没动过设置里的默认值，就被按新手排一整套，这比不填还糟。
     * 用「未确认」而不是「默认值」：老数据里升级前填过的值同样无法确认是谁填的。
     */
    private fun sourceMark(profile: AiProfile, field: String, value: String): String =
        if (profile.isConfirmed(field) || value.isBlank()) value else "$value（未确认）"

    private fun weekLabel(day: Int): String = when (day) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }

    /** 摘要里的动作名。优先主干动作表，其次直接查库比较贵，退化成 id。 */
    private fun bossName(bosses: List<ExerciseEntity>, exerciseId: String): String =
        bosses.firstOrNull { it.id == exerciseId }?.name ?: exerciseId

    private fun ExerciseEntity.toPoolLine(): String {
        val mode = if (unlockType.contains("DURATION")) "计时" else "计次"
        return "$id|$name|$mode"
    }

    private companion object {
        /** 上限 90 条，约 1.2k token；再多对排计划没有边际收益。 */
        const val MAX_POOL = 90
    }
}
