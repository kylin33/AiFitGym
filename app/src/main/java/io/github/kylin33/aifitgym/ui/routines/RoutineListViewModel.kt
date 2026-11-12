package io.github.kylin33.aifitgym.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.checkpoint.ActiveSessionCheckpoint
import io.github.kylin33.aifitgym.data.local.RoutineSummary
import io.github.kylin33.aifitgym.data.repo.RoutineDetail
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 列表页需要「说一句」的结果，交给 Snackbar 呈现。 */
sealed interface RoutineListEvent {
    data class Deleted(val name: String) : RoutineListEvent
    data class Restored(val name: String) : RoutineListEvent
    data class Failed(val message: String) : RoutineListEvent
}

class RoutineListViewModel(container: AppContainer) : ViewModel() {

    private val repository = container.routineRepository
    private val checkpoints = container.checkpoints

    val routines: StateFlow<List<RoutineSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 上次没做完的训练（如果有）。
     *
     * 用 Flow 监听而不是进页面读一次：跟练页收尾时会清掉检查点，
     * 返回首页时横幅必须跟着消失 —— 而首页的 ViewModel 并没有被重建。
     */
    val checkpoint: StateFlow<ActiveSessionCheckpoint?> = checkpoints.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = MutableSharedFlow<RoutineListEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RoutineListEvent> = _events.asSharedFlow()

    /**
     * 撤销用的快照。只留最近一次删除 —— 连删两套时以最后一次为准，
     * 更早那份不再能撤（Snackbar 也早被替换掉了）。
     */
    private var lastDeleted: RoutineDetail? = null

    fun delete(routineId: Long) {
        viewModelScope.launch {
            // 先取完整快照再删：删完就查不到条目了，撤销要靠它
            val snapshot = runCatching { repository.loadDetail(routineId) }.getOrNull()
            if (snapshot == null) {
                _events.emit(RoutineListEvent.Failed("这套计划已经不在了"))
                return@launch
            }

            runCatching { repository.delete(routineId) }
                .onSuccess {
                    lastDeleted = snapshot
                    _events.emit(RoutineListEvent.Deleted(snapshot.routine.name))
                }
                .onFailure {
                    _events.emit(RoutineListEvent.Failed("删除失败：${it.message ?: "未知错误"}"))
                }
        }
    }

    fun undoDelete() {
        val snapshot = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch {
            runCatching { repository.restore(snapshot) }
                .onSuccess { _events.emit(RoutineListEvent.Restored(snapshot.routine.name)) }
                .onFailure {
                    _events.emit(RoutineListEvent.Failed("恢复失败：${it.message ?: "未知错误"}"))
                }
        }
    }

    /**
     * 放弃上次没做完的训练：只删检查点，不动数据库。
     *
     * 已经做完的那几组仍然留在历史里（可以点进去补完或删掉），
     * 这里只是让首页不再弹「继续训练」。假装帮用户「结束」掉这次训练才是越界 ——
     * 时长和成绩该怎么算，只有他自己知道。
     */
    fun discardCheckpoint() {
        viewModelScope.launch { checkpoints.clear() }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RoutineListViewModel(container) as T
    }
}
