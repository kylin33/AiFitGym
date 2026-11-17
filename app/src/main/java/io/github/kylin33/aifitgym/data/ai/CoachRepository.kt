package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.RoutineDao
import io.github.kylin33.aifitgym.data.local.RoutineEntity
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao
import io.github.kylin33.aifitgym.data.local.WorkoutSessionLogEntity
import io.github.kylin33.aifitgym.data.local.WorkoutSetLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 教练的业务出口：拼提示词 → 调网关 → 解析计划 → 落库。
 *
 * UI 只认这里的三件事：问一句、把回复里的计划解析出来、把计划存成 RoutineEntity。
 * 所有跟 token、JSON、校验相关的脏活都在这一层收口。
 */
class CoachRepository(
    private val exerciseDao: ExerciseDao,
    private val routineDao: RoutineDao,
    private val sessionDao: WorkoutSessionDao,
    private val settings: AiSettingsRepository,
    private val contextBuilder: CoachContextBuilder,
    private val client: OpenAiClient = OpenAiClient(),
) {

    /** 一次问答的结果：给用户看的正文 + 可导入的计划（可能没有）。 */
    data class Answer(
        val text: String,
        /** 原始回复（含 json 代码块），存历史用。 */
        val raw: String,
        val plan: PlanDraft?,
    )

    /** 复盘结果：给用户看的话 + 一份「应用才会生效」的改动建议。 */
    data class ReviewAnswer(
        val text: String,
        val raw: String,
        val proposal: PlanProposal?,
    )

    val isReady: Boolean get() = settings.state.value.ready

    /**
     * 发起一轮对话。
     *
     * [onDelta] 会在 IO 线程被高频调用，调用方负责切线程（UI 里是 StateFlow.update，本身原子）。
     */
    suspend fun ask(
        history: List<OpenAiClient.ChatMessage>,
        onDelta: (String) -> Unit,
    ): Answer {
        val state = settings.state.value
        val apiKey = settings.apiKey
            ?: throw OpenAiClient.AiHttpException(0, "还没填 API Key，点右上角去配置")
        if (!state.preferences.endpoint.isConfigured) {
            throw OpenAiClient.AiHttpException(0, "Base URL 或模型名还没填完")
        }

        val context = contextBuilder.build(state.preferences)
        // 专项规则按这一轮的话选：聊跑步才加载跑步，不聊就不花那几百字
        val modules = TrainingModuleSelector.pick(history.lastOrNull { it.role == "user" }?.content.orEmpty())
        val messages = buildList {
            add(
                OpenAiClient.ChatMessage(
                    "system",
                    CoachPrompts.system(context.poolText, context.poolSize, modules = modules),
                ),
            )
            add(OpenAiClient.ChatMessage("system", CoachPrompts.context(context.snapshot)))
            addAll(history.takeLast(MAX_HISTORY))
        }

        val raw = client.streamChat(
            endpoint = state.preferences.endpoint,
            apiKey = apiKey,
            messages = messages,
            onDelta = onDelta,
        )

        val split = PlanBlockParser.split(raw)
        val plan = resolveDraft(split, rosterOrEmpty())?.guarded(context, state.preferences)
        // 有模型只吐计划块、不写正文，这种情况给一句过渡话，别让气泡空着
        val text = split.text.ifBlank {
            if (plan != null) "计划排好了，你看看合不合适。" else "（这次没有输出内容，再问一次试试）"
        }
        return Answer(text = text, raw = raw, plan = plan)
    }

    /**
     * 练完复盘。
     *
     * 与 [ask] 的区别：不带聊天历史（复盘是一次性的，把上个月的闲聊塞进来只是烧 token），
     * 并且把「本次每组的成绩」单独拼成一条 user 消息 —— 简报里只有最近 5 场的概要，
     * 细到「第三组只做了 6 个」这种信息只在这一条里。
     */
    suspend fun reviewSession(
        sessionId: Long,
        feedback: String,
        onDelta: (String) -> Unit,
    ): ReviewAnswer {
        val state = settings.state.value
        val apiKey = settings.apiKey
            ?: throw OpenAiClient.AiHttpException(0, "还没填 API Key，点右上角去配置")
        if (!state.preferences.endpoint.isConfigured) {
            throw OpenAiClient.AiHttpException(0, "Base URL 或模型名还没填完")
        }

        val session = sessionDao.findSession(sessionId)
            ?: throw OpenAiClient.AiHttpException(0, "找不到这次训练的记录")
        val sets = sessionDao.setsOfSession(sessionId)
        val routines = rosterOrEmpty()
        val routine = session.routineId?.let { id -> routines.firstOrNull { it.routineId == id } }
        val targets = routine?.let { routineDao.findItems(it.routineId) }.orEmpty()

        val ids = (targets.map { it.exerciseId } + sets.map { it.exerciseId }).distinct()
        val names = exerciseDao.findByIds(ids).associate { it.id to it.name }

        val context = contextBuilder.build(state.preferences)
        // 复盘的专项从「计划名 + 他的自我感受」猜：练的是「有氧 + 核心」就该带上核心那套规则
        val modules = TrainingModuleSelector.pick("${routine?.name.orEmpty()} $feedback")
        val messages = listOf(
            OpenAiClient.ChatMessage(
                "system",
                CoachPrompts.system(
                    poolText = context.poolText,
                    poolSize = context.poolSize,
                    review = true,
                    routineNames = routines.map { it.name }.take(MAX_ROUTINE_NAMES),
                    modules = modules,
                ),
            ),
            OpenAiClient.ChatMessage("system", CoachPrompts.context(context.snapshot)),
            OpenAiClient.ChatMessage("user", reviewMessage(session, sets, targets, names, feedback)),
        )

        val raw = client.streamChat(
            endpoint = state.preferences.endpoint,
            apiKey = apiKey,
            messages = messages,
            onDelta = onDelta,
        )

        val split = PlanBlockParser.split(raw)
        val proposal = split.updates.firstNotNullOfOrNull { resolveUpdate(it, routines, names) }
            ?.let { it.copy(draft = it.draft.guarded(context, state.preferences)) }
            ?: split.blocks.firstNotNullOfOrNull { resolve(it) }?.let { draft ->
                // 复盘模式下吐了普通计划块：当成「新建一套」，全条都是新增
                PlanProposal(
                    draft = draft.guarded(context, state.preferences),
                    target = null,
                    targetName = "",
                    changes = PlanDiff.compare(emptyList(), draft) { id -> names[id] ?: id },
                )
            }

        val text = split.text.ifBlank {
            when {
                proposal != null -> "调整方案在下面，你看看要不要用。"
                else -> "（这次没有输出内容，再点一次试试）"
            }
        }
        return ReviewAnswer(text = text, raw = raw, proposal = proposal)
    }

    /**
     * 恢复历史消息时用：从一段原始回复里重新解析出计划。
     *
     * 也认 `json_plan_update` —— 聊天里改过计划的那条消息，重开 App 后
     * 卡片上还得是「更新某某计划」，不能退化成一张只能看的图。
     */
    suspend fun resolvePlan(raw: String): PlanDraft? =
        resolveDraft(PlanBlockParser.split(raw), rosterOrEmpty())

    /**
     * 从一份回复里取出「要落地的计划」。
     *
     * 先看改现有计划的块，再看新建的块：模型经常两样都写（先给完整列表再补一句
     * 改动），改动比新建更接近它的真实意图。
     */
    private suspend fun resolveDraft(
        split: PlanBlockParser.Split,
        routines: List<RoutineEntity>,
    ): PlanDraft? {
        val update = split.updates.firstNotNullOfOrNull { resolveUpdate(it, routines, emptyMap()) }
        if (update != null) {
            // resolveUpdate 已经按名字匹配过，把匹配结果写回草稿，导入时才能覆盖同一套
            return update.draft.copy(
                targetRoutineName = update.target?.name ?: update.targetName,
            )
        }
        return split.blocks.firstNotNullOfOrNull { resolve(it) }
    }

    /**
     * 应用前的体检。
     *
     * 只在真正拿到最新简报的路径上做（问一句 / 复盘）：历史回填时没有本轮统计，
     * 为了一条警告再查一遍库不值得，卡片上少一行提示比多等一秒好。
     */
    private fun PlanDraft.guarded(context: CoachContext, preferences: AiPreferences): PlanDraft {
        val advice = VolumePolicy.advise(context.stats, isBeginner())
        val warnings = PlanGuard.check(
            draft = this,
            limitations = preferences.profile.limitations,
            advice = advice,
            lastTrainedByBranch = context.stats.lastTrainedByBranch,
        )
        return if (warnings.isEmpty()) this else copy(warnings = warnings)
    }

    /** 计划块 → 可执行草稿。 */
    private suspend fun resolve(block: String): PlanDraft? {
        val parsed = PlanBlockParser.parse(block) ?: return null
        return draftOf(parsed)
    }

    /**
     * 把解析出来的 JSON 变成「每一条都对得上库里动作」的草稿。
     *
     * 严格到宁可丢条也不猜：模型给的 exerciseId 在库里找不到就直接剔除。
     * 一条对不上就整个作废更糟 —— 用户会以为 AI 完全不会排计划；
     * 但把编出来的动作留在计划里更糟，点开始会发现没有动图、肌群也算不出来。
     */
    private suspend fun draftOf(parsed: AiPlanJson, targetRoutineName: String = ""): PlanDraft? {
        val candidates = parsed.items.take(MAX_ITEMS)
        if (candidates.isEmpty()) return null

        val ids = candidates.map { it.exerciseId.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return null

        val found = runCatching { exerciseDao.findByIds(ids) }
            .getOrDefault(emptyList())
            .associateBy { it.id }

        val category = PlanBlockParser.categoryOf(parsed.category)
        val beginner = isBeginner()
        val maxSets = if (beginner) MAX_SETS_BEGINNER else MAX_SETS

        var dropped = 0
        val items = candidates.mapNotNull { item ->
            val exercise = found[item.exerciseId.trim()]
            if (exercise == null) {
                dropped++
                return@mapNotNull null
            }

            // 动作类型决定休息与默认强度，都在规则层算，提示词说错了也纠正得回来
            val cls = ExerciseClass.of(exercise)
            val sets = item.sets.coerceIn(1, maxSets)
            val rest = RestPolicy.coerce(item.restSec, cls, category)
            val duration = item.durationSec ?: 0
            val reps = item.reps ?: 0

            // 计时优先：模型两个都填了说明它想表达「撑到多少秒」
            val asTimed = duration > 0
            val targetDuration = if (asTimed) duration.coerceIn(5, 600) else null
            val targetReps = when {
                asTimed -> null
                reps > 0 -> reps.coerceIn(1, 100)
                // 两个都没填：按这个动作本身的达标口径猜一个，别给 0
                exercise.unlockType.contains("DURATION") -> null
                else -> DEFAULT_REPS
            }
            val finalDuration = if (targetReps == null && targetDuration == null) DEFAULT_TIMED_SEC else targetDuration

            PlanDraftItem(
                exercise = exercise,
                sets = sets,
                reps = targetReps,
                durationSec = finalDuration,
                restSec = rest,
                note = item.note?.trim()?.take(60)?.ifBlank { null },
                rir = RirPolicy.coerce(item.rir, cls, beginner),
            )
        }

        if (items.isEmpty()) return null

        val name = parsed.name.trim().ifBlank { "AI 计划" }.take(24)
        val description = parsed.description.trim().ifBlank { parsed.reason.trim() }.take(300)

        return PlanDraft(
            name = name,
            description = description,
            reason = parsed.reason.trim().take(120),
            category = category,
            items = items,
            dropped = dropped,
            targetRoutineName = targetRoutineName.trim().ifBlank { parsed.targetRoutine.trim() },
        )
    }

    /** 档案里自评「新手」就按新手兜底；空白或「有基础」都当成能上常规量。 */
    private fun isBeginner(): Boolean = settings.state.value.preferences.profile.isBeginner

    /**
     * 把草稿写成正式计划。
     *
     * 草稿带了 [PlanDraft.targetRoutineName] 就**改那一套**，否则新建 ——
     * 不然「给推日加一组」这种请求会在计划列表里堆出一排「推日」。
     * 改现有计划时按名字精确匹配：匹配不上宁可新建，也不要猜着改到别人身上。
     */
    suspend fun importPlan(draft: PlanDraft): Long {
        val target = draft.targetRoutineName.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { name -> rosterOrEmpty().firstOrNull { it.name.trim() == name } }
        if (target != null) return saveInto(target, draft)

        val routine = RoutineEntity(
            name = draft.name,
            description = draft.description,
            category = draft.category,
        )
        val items = draft.items.map { item ->
            RoutineExerciseEntity(
                routineId = 0,
                exerciseId = item.exercise.id,
                orderIndex = 0,
                targetSets = item.sets,
                targetReps = item.reps,
                targetDurationSec = item.durationSec,
                restDurationSec = item.restSec,
                targetRir = item.rir,
            )
        }
        return routineDao.save(routine, items)
    }

    /**
     * 把一份草稿整份写进已有计划（动作列表整体替换）。
     *
     * 平替动作按 exerciseId 沿用旧的：那是用户自己配的退阶，不该因为 AI 改一次计划就丢。
     */
    private suspend fun saveInto(target: RoutineEntity, draft: PlanDraft): Long {
        val existing = routineDao.findItems(target.routineId).associateBy { it.exerciseId }
        val items = draft.items.map { item ->
            RoutineExerciseEntity(
                routineId = target.routineId,
                exerciseId = item.exercise.id,
                orderIndex = 0,
                targetSets = item.sets,
                targetReps = item.reps,
                targetDurationSec = item.durationSec,
                restDurationSec = item.restSec,
                targetRir = item.rir,
                backupExerciseId = existing[item.exercise.id]?.backupExerciseId,
            )
        }
        // 计划名保持用户原来那个：改名会让「按名字找计划」这条链路在下次复盘时错位
        val updated = target.copy(
            description = draft.description.ifBlank { target.description },
        )
        return routineDao.save(updated, items)
    }

    suspend fun testConnection(): Result<String> {
        val state = settings.state.value
        val apiKey = settings.apiKey ?: return Result.failure(
            OpenAiClient.AiHttpException(0, "还没填 API Key"),
        )
        if (!state.preferences.endpoint.isConfigured) {
            return Result.failure(OpenAiClient.AiHttpException(0, "Base URL 或模型名还没填完"))
        }
        return runCatching {
            val reply = client.ping(state.preferences.endpoint, apiKey)
            "连接正常 · ${state.preferences.endpoint.model} 回了「${reply.trim().take(20)}」"
        }
    }

    // ------------------------------------------------------------ 复盘：改计划

    /**
     * `json_plan_update` → 可应用的改动建议。
     *
     * 目标计划靠**名字**匹配（模型不可能知道 routineId）。精确同名优先，
     * 找不到再做一次包含匹配，但只在唯一命中时才认 —— 匹配错了会改到另一套计划上，
     * 这比不匹配（当成新建）危险得多。
     */
    private suspend fun resolveUpdate(
        block: String,
        routines: List<RoutineEntity>,
        names: Map<String, String>,
    ): PlanProposal? {
        val parsed = PlanBlockParser.parse(block) ?: return null
        val draft = draftOf(parsed) ?: return null
        val wanted = parsed.targetRoutine.trim()

        val target = if (wanted.isEmpty()) {
            null
        } else {
            routines.firstOrNull { it.name.trim() == wanted }
                ?: routines.filter {
                    val name = it.name.trim()
                    name.isNotEmpty() && (name.contains(wanted) || wanted.contains(name))
                }.singleOrNull()
        }

        val old = target?.let { routineDao.findItems(it.routineId) }.orEmpty()
        val missing = old.map { it.exerciseId }.filter { it !in names }
        val extraNames = if (missing.isEmpty()) {
            emptyMap()
        } else {
            exerciseDao.findByIds(missing).associate { it.id to it.name }
        }

        return PlanProposal(
            draft = draft.copy(targetRoutineName = target?.name ?: wanted),
            target = target,
            targetName = wanted,
            changes = PlanDiff.compare(old, draft) { id ->
                names[id] ?: extraNames[id] ?: id
            },
        )
    }

    /**
     * 应用一份改动建议。
     *
     * 和 [importPlan] 走同一条写入路径（[saveInto]）：复盘改计划、聊天里改计划、
     * 导入一份新计划，落到库里都是「一套计划 + 整份动作列表」。
     */
    suspend fun applyProposal(proposal: PlanProposal): Long {
        val target = proposal.target ?: return importPlan(proposal.draft)
        return saveInto(target, proposal.draft)
    }

    /** 本次训练的实际成绩 vs 计划目标，拼成一条 user 消息。 */
    private fun reviewMessage(
        session: WorkoutSessionLogEntity,
        sets: List<WorkoutSetLogEntity>,
        targets: List<RoutineExerciseEntity>,
        names: Map<String, String>,
        feedback: String,
    ): String {
        val day = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("【本次训练】").append(day.format(Date(session.startTime))).append(' ')
        sb.append(session.routineNameSnapshot.ifBlank { "自由训练" })
        sb.append(" · ").append(session.totalDurationSec / 60).append(" 分钟")
        sb.append(" · ").append(sets.size).append(" 组\n")

        val byId = sets.groupBy { it.exerciseId }
        if (targets.isEmpty()) {
            sb.append("这次是自由训练，没有计划目标。\n")
        } else {
            sb.append("计划目标 vs 实际：\n")
            targets.forEach { target ->
                val rows = byId[target.exerciseId].orEmpty()
                sb.append("- ").append(names[target.exerciseId] ?: target.exerciseId)
                sb.append(" 目标 ")
                    .append(PlanDiff.targetText(target.targetSets, target.targetReps, target.targetDurationSec))
                target.targetRir?.let { sb.append("（").append(RirPolicy.describe(it)).append("）") }
                if (rows.isEmpty()) {
                    sb.append(" → 没做\n")
                } else {
                    val reps = rows.map { it.actualReps }.filter { it > 0 }
                    val seconds = rows.map { it.actualDurationSec }.filter { it > 0 }
                    sb.append(" → 实际 ").append(rows.size).append(" 组")
                    when {
                        seconds.isNotEmpty() -> sb.append("，").append(seconds.joinToString("/")).append(" 秒")
                        reps.isNotEmpty() -> sb.append("，").append(reps.joinToString("/")).append(" 次")
                    }
                    sb.append('\n')
                }
            }
            val planned = targets.map { it.exerciseId }.toSet()
            val extra = byId.keys.filter { it !in planned }
            if (extra.isNotEmpty()) {
                sb.append("- 计划外：")
                    .append(extra.joinToString("、") { names[it] ?: it })
                    .append('\n')
            }
        }

        sb.append("【我的反馈】")
            .append(feedback.trim().ifBlank { "（他没写，自己判断）" })
            .append('\n')
        sb.append("按复盘模式回答。")
        return sb.toString()
    }

    private suspend fun rosterOrEmpty(): List<RoutineEntity> =
        runCatching { routineDao.activeRoutines() }.getOrDefault(emptyList())

    private companion object {
        /** 计划名太多反而挤掉动作池的额度，20 套足够覆盖「我正在练的」。 */
        const val MAX_ROUTINE_NAMES = 20

        /** 只带最近 5 轮（10 条）进请求：再往前对当前决策几乎没影响，纯烧 token。 */
        const val MAX_HISTORY = 10

        /** 一套计划最多 8 条，留 2 条冗余，多的直接砍。 */
        const val MAX_ITEMS = 10

        /** 单条动作的组数上限；新手压到 [MAX_SETS_BEGINNER]，先建动作质量。 */
        const val MAX_SETS = 6
        const val MAX_SETS_BEGINNER = 5

        const val DEFAULT_REPS = 10
        const val DEFAULT_TIMED_SEC = 30
    }
}
