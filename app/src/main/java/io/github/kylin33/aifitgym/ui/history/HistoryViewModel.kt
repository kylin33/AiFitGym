package io.github.kylin33.aifitgym.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.local.SessionHeader
import io.github.kylin33.aifitgym.data.repo.HistoryRepository
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 历史列表。
 *
 * 只读；改和删都在详情页做 —— 列表上直接给删除按钮太容易误触，
 * 而「删掉一次训练」是不可撤销的操作。
 */
class HistoryViewModel(container: AppContainer) : ViewModel() {

    private val repository: HistoryRepository = container.historyRepository

    val sessions: StateFlow<List<SessionHeader>> = repository.observeSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(container) as T
    }
}
