package io.github.kylin33.aifitgym.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.local.WorkoutSessionDao
import io.github.kylin33.aifitgym.data.recovery.RecoveryRepository
import io.github.kylin33.aifitgym.data.recovery.RecoverySnapshot
import io.github.kylin33.aifitgym.data.recovery.VolumeSnapshot
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 看板的两种看法：现在能不能练（恢复）/ 最近练得均不均衡（训练量）。 */
enum class RecoveryMode { RECOVERY, VOLUME }

/** 训练量窗口。 */
enum class VolumeRange(val days: Int, val label: String) {
    WEEK(7, "近 7 天"),
    MONTH(30, "近 30 天"),
}

class RecoveryViewModel(container: AppContainer) : ViewModel() {

    private val repository: RecoveryRepository = container.recoveryRepository
    private val sessionDao: WorkoutSessionDao = container.database.workoutSessionDao()

    data class UiState(
        val mode: RecoveryMode = RecoveryMode.RECOVERY,
        val range: VolumeRange = VolumeRange.WEEK,
        val loading: Boolean = true,
        val recovery: RecoverySnapshot = RecoverySnapshot(),
        val week: VolumeSnapshot = VolumeSnapshot(VolumeRange.WEEK.days),
        val month: VolumeSnapshot = VolumeSnapshot(VolumeRange.MONTH.days),
        val error: String? = null,
    ) {
        val volume: VolumeSnapshot get() = if (range == VolumeRange.WEEK) week else month
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 三个窗口一次算完（一共三次小查询），切模式时不再等 IO。 */
    private val loadLock = Mutex()

    init {
        refresh()
        // 结算落库后自动重算，练完回到首页看到的就是最新的疲劳度。
        // drop(1) 丢掉首次的初始值，避免和上面的 refresh() 重复跑一遍。
        viewModelScope.launch {
            sessionDao.observeLastTrainedAt().drop(1).collect { refresh() }
        }
    }

    fun setMode(mode: RecoveryMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun setRange(range: VolumeRange) {
        // 30 天的数据是打开时就算好的，这里只切展示，不重新查库
        _state.update { it.copy(range = range) }
    }

    /**
     * 重新计算。每次进入看板都会调一次 —— 疲劳度是时间的函数，
     * 早上开着页面放到晚上，同一个「3 组」的疲劳度已经不一样了。
     */
    fun refresh() {
        viewModelScope.launch {
            loadLock.withLock {
                val now = System.currentTimeMillis()
                val result = runCatching {
                    Triple(
                        repository.recovery(now),
                        repository.volume(VolumeRange.WEEK.days, now),
                        repository.volume(VolumeRange.MONTH.days, now),
                    )
                }

                result.fold(
                    onSuccess = { (recovery, week, month) ->
                        _state.update {
                            it.copy(
                                loading = false,
                                recovery = recovery,
                                week = week,
                                month = month,
                                error = null,
                            )
                        }
                    },
                    onFailure = { error ->
                        _state.update {
                            it.copy(
                                loading = false,
                                error = error.message ?: "读取训练记录失败",
                            )
                        }
                    },
                )
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecoveryViewModel(container) as T
    }
}
