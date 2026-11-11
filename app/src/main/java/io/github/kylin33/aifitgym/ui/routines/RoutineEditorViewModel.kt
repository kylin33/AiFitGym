package io.github.kylin33.aifitgym.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.local.RoutineCategory
import io.github.kylin33.aifitgym.data.local.RoutineEntity
import io.github.kylin33.aifitgym.data.local.RoutineExerciseEntity
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 编排页里的一个动作条目（未落库，带自增的本地 id 供列表 key 和定位用）。 */
data class EditorItem(
    val draftId: Long,
    val exercise: ExerciseEntity,
    val timed: Boolean,
    val sets: Int,
    val reps: Int,
    val durationSec: Int,
    val restSec: Int,
    val backupId: String? = null,
    val backupName: String? = null,
) {
    val amountText: String
        get() = if (timed) "× ${durationSec}s" else "× $reps"

    val mainText: String
        get() = "$sets 组 ${amountText}"
}

/** 选择器打开时的用途：批量添加，或者给某个条目挑平替。 */
sealed interface PickerMode {
    data object Add : PickerMode
    data class Backup(val draftId: Long) : PickerMode
}

data class RoutineEditorState(
    val loading: Boolean = true,
    val routineId: Long = 0,
    val name: String = "",
    val description: String = "",
    val category: RoutineCategory = RoutineCategory.CALISTHENICS,
    /** 编辑内置模板时保留，避免被当成用户计划重复导入。 */
    val presetKey: String? = null,
    val items: List<EditorItem> = emptyList(),
    val pickerMode: PickerMode = PickerMode.Add,
    val selectedIds: Set<String> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean get() = routineId == 0L

    val canSave: Boolean get() = name.isNotBlank() && items.isNotEmpty() && !saving

    val totalSets: Int get() = items.sumOf { it.sets }

    val pickerMultiSelect: Boolean get() = pickerMode is PickerMode.Add
}

/**
 * 编排页状态机。
 *
 * 草稿全部留在内存里，只有点「保存」才落库；选择器是独立路由，所以这个 ViewModel
 * 在 NavHost 外层创建（Activity 作用域），编排页和选择器共用同一份草稿。
 */
class RoutineEditorViewModel(private val container: AppContainer) : ViewModel() {

    private val routineRepository = container.routineRepository
    private val exerciseRepository = container.exerciseRepository

    private val _state = MutableStateFlow(RoutineEditorState())
    val state: StateFlow<RoutineEditorState> = _state.asStateFlow()

    /** 已经为哪个计划初始化过，避免每次重组都重新加载覆盖用户输入。 */
    private var startedFor: Long? = null
    private var nextDraftId = 0L

    fun start(routineId: Long) {
        if (startedFor == routineId) return
        startedFor = routineId

        if (routineId <= 0L) {
            _state.value = RoutineEditorState(loading = false)
            return
        }

        _state.value = RoutineEditorState(loading = true, routineId = routineId)
        viewModelScope.launch {
            val detail = routineRepository.loadDetail(routineId)
            if (detail == null) {
                _state.update { it.copy(loading = false, error = "计划不存在或已被删除") }
                return@launch
            }
            val items = detail.items.mapNotNull { item ->
                val exercise = exerciseRepository.find(item.exerciseId) ?: return@mapNotNull null
                val backup = item.backupExerciseId?.let { exerciseRepository.find(it) }
                EditorItem(
                    draftId = nextDraftId++,
                    exercise = exercise,
                    timed = item.isTimed,
                    sets = item.targetSets,
                    reps = item.targetReps ?: 10,
                    durationSec = item.targetDurationSec ?: 30,
                    restSec = item.restDurationSec,
                    backupId = backup?.id,
                    backupName = backup?.name,
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    name = detail.routine.name,
                    description = detail.routine.description,
                    category = detail.routine.category,
                    presetKey = detail.routine.presetKey,
                    items = items,
                )
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }

    fun onDescriptionChange(value: String) = _state.update { it.copy(description = value) }

    fun onCategoryChange(value: RoutineCategory) = _state.update { it.copy(category = value) }

    // ---- 选择器 ----

    fun openPicker() = _state.update { it.copy(pickerMode = PickerMode.Add, selectedIds = emptySet()) }

    fun openBackupPicker(draftId: Long) = _state.update {
        it.copy(pickerMode = PickerMode.Backup(draftId), selectedIds = emptySet())
    }

    fun toggleSelection(exerciseId: String) = _state.update { s ->
        val next = if (exerciseId in s.selectedIds) s.selectedIds - exerciseId else s.selectedIds + exerciseId
        s.copy(selectedIds = next)
    }

    fun selectOnly(exerciseId: String) = _state.update { it.copy(selectedIds = setOf(exerciseId)) }

    /** 选择器确认：批量添加，或给条目设平替。动作详情从库里查，跨多次搜索选中的也能补全。 */
    fun confirmPicker() {
        val current = _state.value
        val ids = current.selectedIds.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            val exercises = ids.mapNotNull { exerciseRepository.find(it) }
            if (exercises.isEmpty()) return@launch

            when (val mode = current.pickerMode) {
                is PickerMode.Add -> _state.update { s ->
                    s.copy(items = s.items + exercises.map { newItem(it, s.category) }, selectedIds = emptySet())
                }

                is PickerMode.Backup -> {
                    val backup = exercises.first()
                    updateItem(mode.draftId) {
                        it.copy(backupId = backup.id, backupName = backup.name)
                    }
                    _state.update { it.copy(selectedIds = emptySet()) }
                }
            }
        }
    }

    // ---- 条目操作 ----

    fun remove(draftId: Long) = _state.update { s ->
        s.copy(items = s.items.filterNot { it.draftId == draftId })
    }

    /** @param delta -1 上移，+1 下移 */
    fun move(draftId: Long, delta: Int) = _state.update { s ->
        val index = s.items.indexOfFirst { it.draftId == draftId }
        val target = index + delta
        if (index < 0 || target !in s.items.indices) {
            s
        } else {
            val list = s.items.toMutableList()
            val moved = list.removeAt(index)
            list.add(target, moved)
            s.copy(items = list)
        }
    }

    fun setSets(draftId: Long, value: Int) = updateItem(draftId) {
        it.copy(sets = value.coerceIn(1, 20))
    }

    fun setReps(draftId: Long, value: Int) = updateItem(draftId) {
        it.copy(reps = value.coerceIn(1, 100))
    }

    fun setDuration(draftId: Long, value: Int) = updateItem(draftId) {
        it.copy(durationSec = value.coerceIn(5, 600))
    }

    fun setRest(draftId: Long, value: Int) = updateItem(draftId) {
        it.copy(restSec = value.coerceIn(0, 600))
    }

    fun toggleTimed(draftId: Long) = updateItem(draftId) { it.copy(timed = !it.timed) }

    fun clearBackup(draftId: Long) = updateItem(draftId) { it.copy(backupId = null, backupName = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeSaved() = _state.update { it.copy(saved = false) }

    /** 退出编排页时调一次：下次进来重新从库里读，避免拿到上一次的草稿。 */
    fun endSession() {
        startedFor = null
    }

    // ---- 保存 ----

    fun save() {
        val s = _state.value
        if (!s.canSave) return

        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val routine = RoutineEntity(
                routineId = s.routineId,
                name = s.name.trim(),
                description = s.description.trim(),
                category = s.category,
                presetKey = s.presetKey,
            )
            val items = s.items.mapIndexed { index, item ->
                RoutineExerciseEntity(
                    routineId = s.routineId,
                    exerciseId = item.exercise.id,
                    orderIndex = index,
                    targetSets = item.sets,
                    targetReps = if (item.timed) null else item.reps,
                    targetDurationSec = if (item.timed) item.durationSec else null,
                    restDurationSec = item.restSec,
                    backupExerciseId = item.backupId,
                )
            }
            runCatching { routineRepository.save(routine, items) }
                .onSuccess { id ->
                    startedFor = id
                    _state.update { it.copy(saving = false, saved = true, routineId = id) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(saving = false, error = "保存失败：${e.message ?: "未知错误"}")
                    }
                }
        }
    }

    private fun updateItem(draftId: Long, transform: (EditorItem) -> EditorItem) = _state.update { s ->
        s.copy(items = s.items.map { if (it.draftId == draftId) transform(it) else it })
    }

    /** 新加条目的默认组次：有氧间歇按 8 组 20 秒 / 歇 10 秒，力量按 3 组 10 次 / 歇 60 秒。 */
    private fun newItem(exercise: ExerciseEntity, category: RoutineCategory): EditorItem {
        val tabata = category == RoutineCategory.HIIT_CARDIO
        val timed = tabata || exercise.category in TIMED_CATEGORIES
        return EditorItem(
            draftId = nextDraftId++,
            exercise = exercise,
            timed = timed,
            sets = if (tabata) 8 else 3,
            reps = 10,
            durationSec = if (tabata) 20 else 30,
            restSec = if (tabata) 10 else 60,
        )
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RoutineEditorViewModel(container) as T
    }

    private companion object {
        /** 这几类动作天生按时间计：有氧、等长、爆发力。 */
        val TIMED_CATEGORIES = setOf("cardio", "isometric", "plyometrics")
    }
}
