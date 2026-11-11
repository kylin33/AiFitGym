package io.github.kylin33.aifitgym.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.repo.ExerciseFilter
import io.github.kylin33.aifitgym.data.seeding.ImportState
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 动作库列表页的状态。
 *
 * 数据全部来自 Room 的 Flow：首启导入是一批批写进去的，写多少列表就刷新多少，
 * 所以「秒开」不是靠预加载，而是靠数据库变更通知。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val seeder = container.catalogueSeeder
    private val repository = container.exerciseRepository

    /** 首启导入进度 / 结果，由数据库建库回调驱动。 */
    val importState: StateFlow<ImportState> = seeder.state

    private val keyword = MutableStateFlow("")

    /** true = 显示健身房器械；默认关，只列徒手/居家动作。 */
    private val showGymEquipment = MutableStateFlow(false)

    val query: StateFlow<String> = keyword

    val gymEquipmentVisible: StateFlow<Boolean> = showGymEquipment.asStateFlow()

    val exercises: StateFlow<List<ExerciseEntity>> = combine(keyword, showGymEquipment) { key, gym ->
        ExerciseFilter(keyword = key, bodyweightOnly = !gym)
    }
        .flatMapLatest { repository.observeExercises(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount: StateFlow<Int> = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun onQueryChange(value: String) {
        keyword.value = value
    }

    fun toggleGymEquipment() {
        showGymEquipment.value = !showGymEquipment.value
    }

    /** 导入失败后从 UI 重试；force = true 会清空重灌。 */
    fun retryImport() {
        viewModelScope.launch { seeder.importInto(container.database, force = true) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(container) as T
    }
}
