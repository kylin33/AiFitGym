package io.github.kylin33.aifitgym.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.repo.ExerciseRepository
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DetailViewModel(
    repository: ExerciseRepository,
    exerciseId: String,
) : ViewModel() {

    val exercise: StateFlow<ExerciseEntity?> = repository.observeExercise(exerciseId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    class Factory(
        private val container: AppContainer,
        private val exerciseId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailViewModel(container.exerciseRepository, exerciseId) as T
    }
}
