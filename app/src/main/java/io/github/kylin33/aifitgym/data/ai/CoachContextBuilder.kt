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

        return CoachContext(
            snapshot = buildSnapshot(preferences, progress, bosses, since30),
            poolText = pool.joinToString("\n") { it.toPoolLine() },
            poolSize = pool.size,
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
        sb.append("目标 ").append(profile.goal.ifBlank { "未填写（可以问他）" })
        sb.append("｜每周 ").append(profile.daysPerWeek).append(" 练")
        sb.append("｜单次约 ").append(profile.sessionMinutes).append(" 分钟")
        sb.append("｜水平 ").append(profile.level)
        sb.append('\n')
        if (profile.notes.isNotBlank()) {
            sb.append("【限制 / 自述】").append(profile.notes.trim()).append('\n')
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
