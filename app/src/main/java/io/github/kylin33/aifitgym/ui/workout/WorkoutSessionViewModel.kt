package io.github.kylin33.aifitgym.ui.workout

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.repo.ExerciseRepository
import io.github.kylin33.aifitgym.data.repo.PrMetric
import io.github.kylin33.aifitgym.data.repo.SetOutcome
import io.github.kylin33.aifitgym.data.repo.WorkoutEntry
import io.github.kylin33.aifitgym.data.repo.WorkoutPlan
import io.github.kylin33.aifitgym.data.skill.BranchBreakthrough
import io.github.kylin33.aifitgym.data.skill.BranchPromotion
import io.github.kylin33.aifitgym.data.skill.BranchXp
import io.github.kylin33.aifitgym.data.skill.Settlement
import io.github.kylin33.aifitgym.data.skill.requirement
import io.github.kylin33.aifitgym.di.AppContainer
import io.github.kylin33.aifitgym.service.WorkoutTimerBridge
import io.github.kylin33.aifitgym.service.WorkoutTimerBridge.NO_DEADLINE
import io.github.kylin33.aifitgym.service.WorkoutTimerBridge.TimerSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** 跟练阶段。IDLE 同时也是「加载失败」的停留态。 */
enum class SessionPhase {
    IDLE,
    LOADING,

    /** 计时动作开始前的 3 秒准备。 */
    PREPARE,

    /** 正在做一组。计次动作等用户点完成，计时动作倒计时归零自动完成。 */
    ACTIVE_SET,

    /** 组间休息。 */
    RESTING,

    /** 结算页。 */
    COMPLETED,
}

/** 已完成的一组，结算页按这个列表展示，也用来算训练量分布。 */
data class SetLog(
    val exerciseId: String,
    val name: String,
    val setIndex: Int,
    val reps: Int,
    val seconds: Int,
    val usedBackup: Boolean,
)

/**
 * 结算页的一条新纪录。
 *
 * 只带展示需要的字段：动作名从本次的 [SetLog] 里补，metric 决定量词（次 / 秒）。
 */
data class PrRecord(
    val exerciseId: String,
    val name: String,
    val metric: PrMetric,
    val value: Int,
    val previousBest: Int,
) {
    val delta: Int get() = value - previousBest
}

/**
 * 跟练页的单一数据源。
 *
 * 计时相关的所有可变状态都在这里，Compose 只负责渲染 —— 这样旋转屏幕、退到后台
 * 都不会把倒计时打断（ViewModel 活得比界面久）。
 */
data class WorkoutSessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val routineId: Long = -1L,
    val routineName: String = "",
    val error: String? = null,
    val entries: List<WorkoutEntry> = emptyList(),
    /** 当前是第几个动作（entries 下标）。 */
    val entryIndex: Int = 0,
    /** 当前动作做到第几组，1 起。 */
    val setIndex: Int = 1,
    /** 当前阶段剩余毫秒，圆环用。计次动作固定 0。 */
    val remainingMs: Long = 0L,
    /** 当前阶段总毫秒，圆环分母。 */
    val phaseTotalMs: Long = 0L,
    /** 计次动作的当前计数，用户 +/- 调整。 */
    val reps: Int = 0,
    /** 本动作是否已切到平替/退阶动作。 */
    val useBackup: Boolean = false,
    val elapsedMs: Long = 0L,
    val logs: List<SetLog> = emptyList(),
    /** 实际动作（含平替）→ 完成组数，结算页算肌肉负荷用。 */
    val volume: Map<String, Int> = emptyMap(),
    /** 本次训练落库的总记录 id；0 表示还没建起来。 */
    val sessionId: Long = 0L,
    /** 结算页的破纪录清单。 */
    val prs: List<PrRecord> = emptyList(),
    /** 本次训练晋升的流派节点，结算页弹「晋升 Lv.N」。 */
    val promotions: List<BranchPromotion> = emptyList(),
    /** 本次用 XP 换来的熟练度突破（要求打折）。 */
    val breakthroughs: List<BranchBreakthrough> = emptyList(),
    /** 本次各流派刷到的 XP，结算页显示「推力 +40 XP」。 */
    val xpGains: List<BranchXp> = emptyList(),
    /** 替换计划后的一句回执。 */
    val swapNote: String? = null,
    /** 正在写收尾数据（时长 + PR 判定）。 */
    val saving: Boolean = false,
    /** 写库失败时的提示，结算页会显示一小行红字。 */
    val saveError: String? = null,
) {
    val current: WorkoutEntry? get() = entries.getOrNull(entryIndex)

    val currentExercise: ExerciseEntity? get() = current?.let {
        if (useBackup) it.backup ?: it.exercise else it.exercise
    }

    val isTimed: Boolean get() = current?.isTimed == true

    val completedSets: Int get() = logs.size

    /** 破纪录的动作 id，结算页动作明细打 🏆 用。 */
    val prIds: Set<String> get() = prs.mapTo(HashSet()) { it.exerciseId }

    val totalSets: Int get() = entries.sumOf { it.targetSets }

    /** 顶部整体进度 0f…1f。 */
    val progress: Float get() =
        if (totalSets == 0) 0f else (completedSets.toFloat() / totalSets).coerceIn(0f, 1f)

    /** 倒计时显示用秒数，向上取整，3 → 2.2s 时还显示 3。 */
    val remainingSec: Int get() = ceil(remainingMs / 1000.0).toInt()

    /** 当前阶段走完的比例，圆环用。 */
    val phaseProgress: Float get() =
        if (phaseTotalMs <= 0L) 0f
        else (1f - remainingMs.toFloat() / phaseTotalMs).coerceIn(0f, 1f)

    val elapsedSec: Int get() = (elapsedMs / 1000L).toInt()

    /** 休息时提示「下一组是什么」。 */
    val nextUpLabel: String?
        get() {
            val entry = current ?: return null
            return if (setIndex < entry.targetSets) {
                "${entry.exercise.name} · 第 ${setIndex + 1}/${entry.targetSets} 组"
            } else {
                entries.getOrNull(entryIndex + 1)?.let {
                    "${it.exercise.name} · 第 1/${it.targetSets} 组"
                }
            }
        }
}

/**
 * 跟练状态机。
 *
 * 计时不靠 `delay(1000)` 累加，而是每个阶段记一个 [SystemClock.elapsedRealtime] 截止点，
 * 由统一的 ticker 每 [TICK_MS] 对比一次。这样界面卡顿、主线程繁忙都不会让倒计时走慢。
 */
class WorkoutSessionViewModel(container: AppContainer) : ViewModel() {

    private val repository = container.workoutRepository
    private val skills = container.skillRepository
    private val exercises = container.exerciseRepository
    private val timer = container.workoutTimer
    private val audio = container.audio

    private val _state = MutableStateFlow(WorkoutSessionState())
    val state: StateFlow<WorkoutSessionState> = _state.asStateFlow()

    /** 本次训练的总记录 id；组明细都挂在它下面。 */
    private var sessionId = 0L
    private var startedAt = 0L

    /** 当前阶段的结束时刻（elapsedRealtime）；0 表示当前阶段不计时。 */
    private var phaseDeadline = 0L
    private var lastBeepSec = -1
    private var ticker: Job? = null

    /** 上一次推给通知栏的快照指纹，用来把 80ms 的 tick 压成「有变化才推」。 */
    private var timerKey: String? = null
    private var timerAttached = false

    init {
        // 通知栏的「+15 秒 / 跳过休息 / 结束训练」回到状态机里执行。
        // 这几个入口自带阶段守卫，多个 ViewModel 同时存活时也只有正在跟练的那个会响应。
        viewModelScope.launch {
            timer.commands.collect { command ->
                when (command) {
                    WorkoutTimerBridge.Command.EXTEND_REST -> extendRest()
                    WorkoutTimerBridge.Command.SKIP_REST -> skipRest()
                    WorkoutTimerBridge.Command.FINISH -> finishEarly()
                }
            }
        }
    }

    /** LaunchedEffect 在重组时可能再喊一次 start，用它防止训练被重开。 */
    private var startedKey: String? = null

    fun start(routineId: Long) {
        val key = "routine-$routineId"
        if (startedKey == key) return
        startedKey = key
        _state.value = WorkoutSessionState(phase = SessionPhase.LOADING, routineId = routineId)

        viewModelScope.launch {
            val plan = runCatching { repository.loadPlan(routineId) }.getOrNull()
            when {
                plan == null -> fail("这套计划已经不在了")

                plan.entries.isEmpty() -> fail(
                    if (plan.skipped > 0) "计划里的动作在动作库里都找不到了（${plan.skipped} 个）"
                    else "这套计划还没有动作，先去编排页加几个"
                )

                else -> begin(plan, routineId)
            }
        }
    }

    /**
     * 技能树上的「直接开始考级」。
     *
     * 只练一个动作，组数和目标值直接用该节点的达标条件 —— 点进来就是一份现成的考级安排。
     * 不建真计划：落库时 `routineId` 传 null，历史里靠「考级 · 动作名」这个快照名认。
     */
    fun startSingle(exerciseId: String) {
        val key = "exercise-$exerciseId"
        if (startedKey == key) return
        startedKey = key
        _state.value = WorkoutSessionState(phase = SessionPhase.LOADING)

        viewModelScope.launch {
            val exercise = runCatching { exercises.find(exerciseId) }.getOrNull()
                ?: return@launch fail("这个动作已经不在动作库里了")

            val requirement = exercise.requirement()
            // 计时动作既可能标在口径上，也可能是 isometric 分类，两边都认
            val timed = requirement.isTimed || exercise.category == "isometric"
            val plan = repository.singlePlan(
                exercise = exercise,
                sets = requirement.sets.coerceIn(1, 10),
                reps = requirement.value.takeIf { !timed },
                seconds = requirement.value.takeIf { timed },
            )
            begin(plan, routineId = 0L)
        }
    }

    /** 建总记录 → 切到第一组。计划 / 考级两条入口共用。 */
    private suspend fun begin(plan: WorkoutPlan, routineId: Long) {
        // 先建总记录：组明细有外键指向它，没有 sessionId 后面一组都存不进去
        val now = System.currentTimeMillis()
        val newSessionId = runCatching {
            repository.beginSession(routineId.takeIf { it > 0L }, plan.routine.name, now)
        }.getOrNull()
        if (newSessionId == null || newSessionId <= 0L) {
            fail("训练记录写不进本地库，先确认一下存储空间")
            return
        }

        sessionId = newSessionId
        startedAt = SystemClock.elapsedRealtime()
        _state.value = WorkoutSessionState(
            phase = SessionPhase.ACTIVE_SET,
            routineId = routineId,
            routineName = plan.routine.name,
            entries = plan.entries,
            entryIndex = 0,
            setIndex = 1,
            sessionId = newSessionId,
        )
        startTicker()
        enterCurrentSet()
        syncTimer()
    }

    // ---------------------------------------------------------------- 阶段推进

    private fun enterCurrentSet() {
        val entry = _state.value.current ?: return finishSession()
        if (entry.isTimed) {
            beginPhase(SessionPhase.PREPARE, PREPARE_MS)
        } else {
            // 计次动作不倒计时，直接把目标次数摆出来让用户改
            phaseDeadline = 0L
            lastBeepSec = -1
            _state.update {
                it.copy(
                    phase = SessionPhase.ACTIVE_SET,
                    reps = entry.plannedReps,
                    remainingMs = 0L,
                    phaseTotalMs = 0L,
                )
            }
        }
    }

    private fun beginPhase(phase: SessionPhase, durationMs: Long) {
        phaseDeadline = SystemClock.elapsedRealtime() + durationMs
        lastBeepSec = -1
        _state.update { it.copy(phase = phase, remainingMs = durationMs, phaseTotalMs = durationMs) }
    }

    private fun onPhaseElapsed() {
        when (_state.value.phase) {
            SessionPhase.PREPARE -> {
                audio.go()
                val entry = _state.value.current ?: return finishSession()
                beginPhase(SessionPhase.ACTIVE_SET, entry.plannedSeconds * 1000L)
            }

            SessionPhase.ACTIVE_SET -> {
                audio.done()
                completeCurrentSet()
            }

            SessionPhase.RESTING -> {
                audio.go()
                advanceToNextSet()
            }

            else -> Unit
        }
    }

    private fun completeCurrentSet() {
        val snapshot = _state.value
        val entry = snapshot.current ?: return finishSession()
        val exercise = snapshot.currentExercise ?: entry.exercise
        val timed = entry.isTimed
        val reps = if (timed) 0 else snapshot.reps

        // 计时动作记「实际坚持」而不是计划秒数：提前收工只算真正撑住的那段，
        // 否则技能树的「保持 30 秒」和 PR 的时长纪录都会是假的
        val seconds = if (timed) {
            if (snapshot.phaseTotalMs <= 0L) {
                entry.plannedSeconds
            } else {
                val spentMs = (snapshot.phaseTotalMs - snapshot.remainingMs)
                    .coerceIn(0L, snapshot.phaseTotalMs)
                val whole = (spentMs / 1000L).toInt()
                if (spentMs > 0L) whole.coerceAtLeast(1) else whole
            }
        } else {
            0
        }

        val log = SetLog(
            exerciseId = exercise.id,
            name = exercise.name,
            setIndex = snapshot.setIndex,
            reps = reps,
            seconds = seconds,
            usedBackup = snapshot.useBackup,
        )
        val volume = LinkedHashMap(snapshot.volume).apply {
            this[exercise.id] = (this[exercise.id] ?: 0) + 1
        }

        phaseDeadline = 0L
        _state.update {
            it.copy(logs = it.logs + log, volume = volume, remainingMs = 0L, phaseTotalMs = 0L)
        }

        // 一组一落库：中途退出/切后台被杀，已经做完的组不会丢
        viewModelScope.launch {
            runCatching {
                repository.logSet(
                    sessionId = sessionId,
                    exerciseId = exercise.id,
                    setIndex = snapshot.setIndex,
                    reps = reps,
                    seconds = seconds,
                )
            }.onFailure {
                _state.update { it.copy(saveError = "有一组没能存进训练记录，历史里可能少一组") }
            }
        }

        advanceAfterSet()
    }

    private fun advanceAfterSet() {
        val snapshot = _state.value
        val entry = snapshot.current ?: return finishSession()
        val lastSetOfEntry = snapshot.setIndex >= entry.targetSets
        val lastEntry = snapshot.entryIndex + 1 >= snapshot.entries.size

        if (lastSetOfEntry && lastEntry) {
            finishSession()
            return
        }
        startRest(entry.restSeconds)
    }

    private fun startRest(seconds: Int) {
        if (seconds <= 0) {
            advanceToNextSet()
            return
        }
        beginPhase(SessionPhase.RESTING, seconds * 1000L)
    }

    private fun advanceToNextSet() {
        val snapshot = _state.value
        val entry = snapshot.current ?: return finishSession()

        phaseDeadline = 0L
        if (snapshot.setIndex < entry.targetSets) {
            _state.update { it.copy(setIndex = it.setIndex + 1) }
        } else {
            if (snapshot.entryIndex + 1 >= snapshot.entries.size) {
                finishSession()
                return
            }
            // 换动作时平替状态重置，下一个动作还是按它自己的主/替来
            _state.update { it.copy(entryIndex = it.entryIndex + 1, setIndex = 1, useBackup = false) }
        }
        enterCurrentSet()
    }

    /**
     * 收尾并落库。
     *
     * 界面先立刻切到结算页（有组数据就已经能画出热力图了），
     * 时长和 PR 判定放后台写，写完再补进 state —— 不让用户对着「保存中」干等。
     */
    private fun finishSession() {
        val snapshot = _state.value
        if (snapshot.phase == SessionPhase.COMPLETED) return

        phaseDeadline = 0L
        lastBeepSec = -1
        val elapsed = if (startedAt > 0) SystemClock.elapsedRealtime() - startedAt else snapshot.elapsedMs
        audio.done()

        _state.update {
            it.copy(
                phase = SessionPhase.COMPLETED,
                remainingMs = 0L,
                phaseTotalMs = 0L,
                elapsedMs = elapsed,
                saving = snapshot.completedSets > 0,
            )
        }
        ticker?.cancel()
        // 结算页不需要常驻通知了，撤掉服务顺手释放唤醒锁
        detachTimer()

        if (snapshot.completedSets == 0) {
            // 一组没做就退出：把空壳总记录删掉，别在历史里留一条 0 组的训练
            viewModelScope.launch {
                runCatching { repository.finishSession(sessionId, System.currentTimeMillis(), 0, emptyList()) }
            }
            return
        }

        val outcomes = snapshot.logs.map { SetOutcome(it.exerciseId, it.reps, it.seconds) }
        val names = snapshot.logs.associate { it.exerciseId to it.name }

        viewModelScope.launch {
            val result = runCatching {
                repository.finishSession(
                    sessionId = sessionId,
                    endTime = System.currentTimeMillis(),
                    durationSec = (elapsed / 1000L).toInt(),
                    sets = outcomes,
                )
            }

            // 训练记录落盘之后立刻做双轨结算：两件事共用这一场的内存数据，
            // 放同一条协程里顺序执行，保证「结算页弹的晋升」和「库里记的成绩」是同一次训练。
            val settlement = runCatching { skills.settle(outcomes) }.getOrDefault(Settlement.EMPTY)

            _state.update { current ->
                current.copy(
                    saving = false,
                    prs = result.getOrNull().orEmpty().map { record ->
                        PrRecord(
                            exerciseId = record.exerciseId,
                            name = names[record.exerciseId] ?: record.exerciseId,
                            metric = record.metric,
                            value = record.value,
                            previousBest = record.previousBest,
                        )
                    },
                    promotions = settlement.promotions,
                    breakthroughs = settlement.breakthroughs,
                    xpGains = settlement.xp,
                    saveError = result.exceptionOrNull()?.let { "训练记录保存失败，这次的成绩可能没留在历史里" }
                        ?: current.saveError,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 用户操作

    /** 「完成本组」（计次动作）。 */
    fun completeSet() {
        if (_state.value.phase != SessionPhase.ACTIVE_SET) return
        completeCurrentSet()
    }

    /** 计时动作提前收工，不用等倒计时。 */
    fun finishTimedSetEarly() {
        val snapshot = _state.value
        if (snapshot.phase != SessionPhase.ACTIVE_SET || !snapshot.isTimed) return
        completeCurrentSet()
    }

    fun adjustReps(delta: Int) {
        val snapshot = _state.value
        if (snapshot.phase != SessionPhase.ACTIVE_SET) return
        val entry = snapshot.current ?: return
        if (entry.isTimed) return
        _state.update { it.copy(reps = (it.reps + delta).coerceIn(1, 999)) }
    }

    fun skipRest() {
        if (_state.value.phase != SessionPhase.RESTING) return
        phaseDeadline = 0L
        advanceToNextSet()
    }

    fun extendRest(seconds: Int = EXTEND_REST_SEC) {
        if (_state.value.phase != SessionPhase.RESTING) return
        val now = SystemClock.elapsedRealtime()
        val base = phaseDeadline.coerceAtLeast(now)
        phaseDeadline = base + seconds * 1000L
        _state.update {
            it.copy(
                remainingMs = (phaseDeadline - now).coerceAtLeast(0L),
                phaseTotalMs = it.phaseTotalMs + seconds * 1000L,
            )
        }
    }

    /** 力竭时切换到平替/退阶动作，本动作剩下的组都按平替走。 */
    fun toggleBackup() {
        val snapshot = _state.value
        val entry = snapshot.current ?: return
        if (entry.backup == null) return
        _state.update { it.copy(useBackup = !it.useBackup) }
    }

    /** 提前结束（退出确认后调用）。已完成的组已经落库了。 */
    fun finishEarly() {
        if (_state.value.phase == SessionPhase.COMPLETED) return
        finishSession()
    }

    // ---------------------------------------------------------------- 双轨升级

    /** 关掉晋升弹窗，按兵不动（计划不动，但留一句痕迹）。 */
    fun dismissPromotions() {
        val snapshot = _state.value
        val note = buildString {
            snapshot.promotions.forEach { promotion ->
                if (length > 0) append("；")
                append(promotion.branch.label)
                append(" 晋升 Lv.").append(promotion.toTier)
            }
            snapshot.breakthroughs.forEachIndexed { index, breakthrough ->
                if (length > 0) append("；")
                append(breakthrough.branch.label).append(" 熟练度突破 -")
                    .append(breakthrough.discountPercent).append("%")
            }
            if (length == 0) append("可在技能树里查看进度")
        }
        _state.update { it.copy(promotions = emptyList(), breakthroughs = emptyList(), swapNote = note) }
    }

    /**
     * 一键替换：把**刚练的这套计划**里通关的旧动作换成刚解锁的下一阶动作。
     *
     * 只动这一套计划，不去扫用户其他计划 —— 「我今天练的这套」是用户当下唯一确定的上下文，
     * 顺手改掉别的计划属于惊吓而不是惊喜。考级模式（没有计划）下按钮直接不显示。
     */
    fun swapPromotedToNext() {
        val snapshot = _state.value
        val swaps = snapshot.promotions.filter { it.nextId != null && it.nextName != null }
        if (swaps.isEmpty() || snapshot.routineId <= 0L) return

        viewModelScope.launch {
            var applied = 0
            var duplicated = 0
            swaps.forEach { promotion ->
                promotion.nextId?.let { nextId ->
                    when (skills.swapInRoutine(snapshot.routineId, promotion.clearedId, nextId)) {
                        in 1..Int.MAX_VALUE -> applied++
                        -1 -> duplicated++
                    }
                }
            }

            val first = swaps.first()
            val note = when {
                applied > 0 -> "已把《${snapshot.routineName}》里的 ${first.clearedName} 换成 ${first.nextName}"
                duplicated > 0 -> "《${snapshot.routineName}》里本来就有 ${first.nextName}，没有重复替换"
                else -> "这套计划里没找到 ${first.clearedName}，可以到编排页手动换"
            }
            _state.update {
                it.copy(promotions = emptyList(), breakthroughs = emptyList(), swapNote = note)
            }
        }
    }

    // ---------------------------------------------------------------- 计时器

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val snapshot = _state.value
                if (snapshot.phase == SessionPhase.COMPLETED || snapshot.phase == SessionPhase.IDLE) break

                val now = SystemClock.elapsedRealtime()
                val deadline = phaseDeadline
                val remaining = if (deadline > 0L) (deadline - now).coerceAtLeast(0L) else 0L

                _state.update { it.copy(elapsedMs = now - startedAt, remainingMs = remaining) }
                if (deadline > 0L) countdownBeep(remaining)
                syncTimer()

                // 再比一次 deadline：期间用户点了「+15 秒 / 跳过」的话，这里就不该触发
                if (deadline > 0L && remaining == 0L && phaseDeadline == deadline) {
                    onPhaseElapsed()
                }
                delay(TICK_MS)
            }
        }
    }

    private fun countdownBeep(remaining: Long) {
        val sec = ceil(remaining / 1000.0).toInt()
        if (sec == lastBeepSec) return
        if (sec in 1..LAST_BEEP_SEC) {
            audio.tick()
            lastBeepSec = sec
        }
    }

    // ---------------------------------------------------------------- 通知栏

    /**
     * 把当前阶段推给前台服务。
     *
     * 推过去的是**截止时刻**而不是「还剩几秒」：服务和界面各自拿 SystemClock 去减，
     * 不管哪一边被系统挂起，另一边都不会跟着走慢。80ms 一轮 tick 里只在指纹变化时写入，
     * 免得每秒十几次去刷通知。
     */
    private fun syncTimer() {
        val snapshot = buildTimerSnapshot()
        val key = snapshot?.let {
            "${it.title}|${it.subtitle}|${it.subText}|${it.deadline}|${it.totalMs}|${it.allowExtend}|${it.allowSkip}"
        }
        if (key == timerKey) return
        timerKey = key

        // 第一个有效快照顺手把服务拉起来。跟练页此时必然在前台，
        // 满足 Android 12+ 对「后台不得启动前台服务」的限制。
        if (snapshot != null && !timerAttached) {
            timerAttached = true
            timer.attach(snapshot)
        } else {
            timer.publish(snapshot)
        }
    }

    private fun detachTimer() {
        timerKey = null
        timerAttached = false
        timer.detach()
    }

    /** 当前阶段要显示在通知栏的样子；返回 null 表示不该有通知。 */
    private fun buildTimerSnapshot(): TimerSnapshot? {
        val snapshot = _state.value
        val entry = snapshot.current ?: return null
        val exercise = snapshot.currentExercise ?: entry.exercise
        val subText = "已完成 ${snapshot.completedSets}/${snapshot.totalSets} 组"
        val position = "第 ${snapshot.setIndex}/${entry.targetSets} 组"

        return when (snapshot.phase) {
            SessionPhase.IDLE, SessionPhase.LOADING, SessionPhase.COMPLETED -> null

            SessionPhase.PREPARE -> TimerSnapshot(
                title = "准备开始 · ${exercise.name}",
                subtitle = "$position · 保持 ${entry.plannedSeconds} 秒",
                subText = subText,
                deadline = phaseDeadline,
                totalMs = snapshot.phaseTotalMs,
            )

            SessionPhase.ACTIVE_SET -> TimerSnapshot(
                title = exercise.name,
                subtitle = if (entry.isTimed) {
                    "$position · 目标 ${entry.plannedSeconds} 秒"
                } else {
                    "$position · 目标 ${snapshot.reps} 次"
                },
                subText = subText,
                // 计次动作等人点「完成」，没有截止时刻，通知也就不该倒数
                deadline = if (entry.isTimed) phaseDeadline else NO_DEADLINE,
                totalMs = if (entry.isTimed) snapshot.phaseTotalMs else 0L,
            )

            SessionPhase.RESTING -> TimerSnapshot(
                title = "组间休息",
                subtitle = snapshot.nextUpLabel?.let { "接下来 $it" } ?: "马上结束",
                subText = subText,
                deadline = phaseDeadline,
                totalMs = snapshot.phaseTotalMs,
                allowExtend = true,
                allowSkip = true,
            )
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(phase = SessionPhase.IDLE, error = message) }
        detachTimer()
    }

    override fun onCleared() {
        ticker?.cancel()
        detachTimer()
        // 音效管理器是 Application 级的，这里不能 release，否则换个页面就哑了
        super.onCleared()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WorkoutSessionViewModel(container) as T
    }

    private companion object {
        /** 计时动作开始前的准备时间。 */
        const val PREPARE_MS = 3_000L

        /** ticker 周期：80ms 够圆环顺滑，又不至于把主线程压死。 */
        const val TICK_MS = 80L

        const val LAST_BEEP_SEC = 3
        const val EXTEND_REST_SEC = 15
    }
}
