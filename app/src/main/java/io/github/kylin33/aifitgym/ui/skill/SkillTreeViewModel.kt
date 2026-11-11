package io.github.kylin33.aifitgym.ui.skill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineSummary
import io.github.kylin33.aifitgym.data.skill.AddToRoutineResult
import io.github.kylin33.aifitgym.data.skill.BranchBoard
import io.github.kylin33.aifitgym.data.skill.BranchBoss
import io.github.kylin33.aifitgym.data.skill.SkillBranch
import io.github.kylin33.aifitgym.data.skill.SkillRepository
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 节点详情弹窗的数据：主干动作 + 同流派强化动作池。 */
data class BossSheet(
    val boss: BranchBoss,
    /** 支线动作，正在加载时为空。 */
    val accessories: List<ExerciseEntity> = emptyList(),
    val loading: Boolean = true,
)

data class SkillTreeState(
    val boards: List<BranchBoard> = emptyList(),
    /** 当前选中的流派 Tab。 */
    val selected: SkillBranch = SkillBranch.PUSH,
    /** 打开的节点详情；null 表示没开。 */
    val sheet: BossSheet? = null,
    /** 「加入计划」的目标计划列表。 */
    val routines: List<RoutineSummary> = emptyList(),
    /** 一句操作回执（加入成功、已有等），Snackbar 用。 */
    val message: String? = null,
) {
    val board: BranchBoard? get() = boards.firstOrNull { it.branch == selected }

    val current: BranchBoss? get() = board?.current
}

/**
 * 技能树页。
 *
 * 看板数据由仓库的 Flow 驱动：练完一场、或者去历史页改了某组成绩，
 * 进度、XP、最好成绩都会自己刷新，页面不需要手动 refresh。
 */
class SkillTreeViewModel(container: AppContainer) : ViewModel() {

    private val repository: SkillRepository = container.skillRepository
    private val routines = container.routineRepository

    private val selected = MutableStateFlow(SkillBranch.PUSH)
    private val sheet = MutableStateFlow<BossSheet?>(null)
    private val routineList = MutableStateFlow<List<RoutineSummary>>(emptyList())
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<SkillTreeState> = combine(
        repository.observeBoards(),
        selected,
        sheet,
        routineList,
        message,
    ) { boards, branch, openSheet, allRoutines, tip ->
        SkillTreeState(
            boards = boards,
            selected = branch,
            sheet = openSheet,
            routines = allRoutines,
            message = tip,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SkillTreeState(),
    )

    fun select(branch: SkillBranch) {
        selected.value = branch
    }

    /** 点开一个节点：先出骨架，再把 15 个支线动作异步补上。 */
    fun openBoss(boss: BranchBoss) {
        sheet.value = BossSheet(boss = boss, loading = true)
        viewModelScope.launch {
            val exercise = boss.exercise
            val pool = if (exercise == null) {
                emptyList()
            } else {
                repository.accessoryPool(boss.branch, exercise.target)
            }
            // 期间用户可能已经点了别的节点，别把旧结果盖上去
            if (sheet.value?.boss?.tier == boss.tier && sheet.value?.boss?.branch == boss.branch) {
                sheet.value = BossSheet(boss = boss, accessories = pool, loading = false)
            }
            // 每次都刷一遍：用户可能刚在「计划」页新建了计划，缓存住会让他选不到
            routineList.value = runCatching { routines.observeSummaries().first() }
                .getOrDefault(routineList.value)
        }
    }

    fun closeBoss() {
        sheet.value = null
    }

    /**
     * 把一个动作追加到计划。
     *
     * 用户可能有多套计划，所以调用方会先把 [SkillTreeState.routines] 摆出来让他选；
     * 只有一套时直接加，少点一次。
     */
    fun addToRoutine(routineId: Long, exercise: ExerciseEntity) {
        viewModelScope.launch {
            val result = repository.addToRoutine(routineId, exercise)
            message.value = when (result) {
                AddToRoutineResult.ADDED -> "已把「${exercise.name}」加进计划"
                AddToRoutineResult.ALREADY_THERE -> "这套计划里已经有「${exercise.name}」了"
                AddToRoutineResult.FAILED -> "加入失败，检查一下计划是否还在"
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SkillTreeViewModel(container) as T
    }
}
