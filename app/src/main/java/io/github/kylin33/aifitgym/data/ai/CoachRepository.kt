package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.ExerciseDao
import io.github.kylin33.aifitgym.data.local.RoutineDao
import io.github.kylin33.aifitgym.data.local.RoutineEntity
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity

/**
 * AI 教练的业务出口：拼提示词 → 调网关 → 解析计划 → 落库。
 *
 * UI 只认这里的三件事：问一句、把回复里的计划解析出来、把计划存成 RoutineEntity。
 * 所有跟 token、JSON、校验相关的脏活都在这一层收口。
 */
class CoachRepository(
    private val exerciseDao: ExerciseDao,
    private val routineDao: RoutineDao,
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
        val messages = buildList {
            add(OpenAiClient.ChatMessage("system", CoachPrompts.system(context.poolText, context.poolSize)))
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
        val plan = split.blocks.firstNotNullOfOrNull { resolve(it) }
        // 有模型只吐计划块、不写正文，这种情况给一句过渡话，别让气泡空着
        val text = split.text.ifBlank {
            if (plan != null) "计划排好了，你看看合不合适。" else "（这次没有输出内容，再问一次试试）"
        }
        return Answer(text = text, raw = raw, plan = plan)
    }

    /** 恢复历史消息时用：从一段原始回复里重新解析出计划。 */
    suspend fun resolvePlan(raw: String): PlanDraft? =
        PlanBlockParser.split(raw).blocks.firstNotNullOfOrNull { resolve(it) }

    /**
     * 计划块 → 可执行草稿。
     *
     * 宁可丢条也不猜：模型给的 exerciseId 在库里找不到就直接剔除。
     * 一条对不上就整个作废更糟 —— 用户会以为 AI 完全不会排计划；
     * 但把编出来的动作留在计划里更糟，点开始会发现没有动图、肌群也算不出来。
     */
    private suspend fun resolve(block: String): PlanDraft? {
        val parsed = PlanBlockParser.parse(block) ?: return null
        val candidates = parsed.items.take(MAX_ITEMS)
        if (candidates.isEmpty()) return null

        val ids = candidates.map { it.exerciseId.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return null

        val found = runCatching { exerciseDao.findByIds(ids) }
            .getOrDefault(emptyList())
            .associateBy { it.id }

        var dropped = 0
        val items = candidates.mapNotNull { item ->
            val exercise = found[item.exerciseId.trim()]
            if (exercise == null) {
                dropped++
                return@mapNotNull null
            }

            val sets = item.sets.coerceIn(1, 6)
            val rest = (item.restSec ?: DEFAULT_REST).coerceIn(15, 180)
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
            )
        }

        if (items.isEmpty()) return null

        val name = parsed.name.trim().ifBlank { "AI 计划" }.take(24)
        val description = parsed.description.trim().ifBlank { parsed.reason.trim() }.take(300)

        return PlanDraft(
            name = name,
            description = description,
            reason = parsed.reason.trim().take(120),
            category = PlanBlockParser.categoryOf(parsed.category),
            items = items,
            dropped = dropped,
        )
    }

    /**
     * 把草稿写成正式计划。
     *
     * 走 [RoutineDao.save]，它会统一重排 orderIndex 并处理自增主键，
     * 所以这里把 routineId 和 id 都留 0。
     */
    suspend fun importPlan(draft: PlanDraft): Long {
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
            )
        }
        return routineDao.save(routine, items)
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

    private companion object {
        /** 只带最近 5 轮（10 条）进请求：再往前对当前决策几乎没影响，纯烧 token。 */
        const val MAX_HISTORY = 10

        /** 一套计划最多 8 条，留 2 条冗余，多的直接砍。 */
        const val MAX_ITEMS = 10

        const val DEFAULT_REST = 60
        const val DEFAULT_REPS = 10
        const val DEFAULT_TIMED_SEC = 30
    }
}
