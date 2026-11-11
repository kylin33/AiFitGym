package io.github.kylin33.aifitgym.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.repo.ExerciseFilter
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 动作选择器的搜索状态。和动作库用同一套 DAO 查询，只是关键词各存各的，
 * 选动作时改的关键词不会污染动作库页。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExercisePickerViewModel(container: AppContainer) : ViewModel() {

    private val repository = container.exerciseRepository
    private val keyword = MutableStateFlow("")

    val query: StateFlow<String> = keyword

    val exercises: StateFlow<List<ExerciseEntity>> = keyword
        .flatMapLatest { repository.observeExercises(ExerciseFilter(keyword = it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        keyword.value = value
    }

    fun clearQuery() {
        keyword.value = ""
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExercisePickerViewModel(container) as T
    }
}
