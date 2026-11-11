package io.github.kylin33.aifitgym.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.repo.HistoryGroup
import io.github.kylin33.aifitgym.data.repo.HistoryRepository
import io.github.kylin33.aifitgym.data.repo.SessionDetail
import io.github.kylin33.aifitgym.data.repo.isTimedIn
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一次训练的历史详情 + 手动修正。
 *
 * 每次改完都整页重读，而不是在本地把数字改掉：组明细、汇总、时长是一体的，
 * 本地改完忘了同步汇总，就会出现「改了 12 次但总次数还是 10」。
 */
class SessionDetailViewModel(
    container: AppContainer,
    private val sessionId: Long,
) : ViewModel() {

    private val repository: HistoryRepository = container.historyRepository

    data class UiState(
        val loading: Boolean = true,
        val detail: SessionDetail? = null,
        val busy: Boolean = false,
        /** 删除完成，页面该退出了。 */
        val closed: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        reload()
    }

    fun updateSet(setLogId: Long, value: Int) {
        val group = _state.value.detail?.groups
            ?.firstOrNull { g -> g.sets.any { it.setLogId == setLogId } }
            ?: return
        val row = group.sets.firstOrNull { it.setLogId == setLogId } ?: return
        val timed = row.isTimedIn(group)
        run(busy = false) {
            repository.updateSet(
                setLogId = setLogId,
                reps = if (timed) 0 else value,
                seconds = if (timed) value else 0,
            )
        }
    }

    fun addSet(group: HistoryGroup) {
        run { repository.addSet(group, sessionId) }
    }

    fun deleteSet(setLogId: Long) {
        run { repository.deleteSet(setLogId) }
    }

    /** 补完中途退出的训练：只补结束时间和时长，组明细不动。 */
    fun finishSession() {
        run { repository.finishSession(sessionId) }
    }

    fun deleteSession() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { repository.deleteSession(sessionId) }
                .onSuccess { _state.update { it.copy(busy = false, closed = true) } }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.message ?: "删除失败") }
                }
        }
    }

    fun reload() {
        viewModelScope.launch {
            val detail = runCatching { repository.loadDetail(sessionId) }
            _state.update {
                it.copy(
                    loading = false,
                    detail = detail.getOrNull(),
                    error = detail.exceptionOrNull()?.message,
                )
            }
        }
        // 列表页也要跟着刷新：改了组数，历史卡片上的「N 组」应该立刻变
    }

    private fun run(busy: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (busy) _state.update { it.copy(busy = true) }
            runCatching { block() }
                .onFailure { error ->
                    _state.update { it.copy(busy = false, error = error.message ?: "操作失败") }
                    return@launch
                }
            val detail = runCatching { repository.loadDetail(sessionId) }.getOrNull()
            _state.update { it.copy(busy = false, detail = detail, error = null) }
        }
    }

    class Factory(
        private val container: AppContainer,
        private val sessionId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SessionDetailViewModel(container, sessionId) as T
    }
}
