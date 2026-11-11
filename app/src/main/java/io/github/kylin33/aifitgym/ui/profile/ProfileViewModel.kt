package io.github.kylin33.aifitgym.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.repo.DataResetRepository
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 「我的」页顶部那排数字。 */
data class ProfileStats(
    val workouts: Int = 0,
    val totalSeconds: Int = 0,
    /** 已通关的主干动作数。 */
    val mastered: Int = 0,
    /** 主干动作总数（当前数据包是 48）。 */
    val totalBosses: Int = 0,
)

/**
 * 「我的」页。
 *
 * 只读统计：训练次数、累计时长、通关动作。全部来自 Room 的 Flow，
 * 练完一场回来、导入一份备份之后数字会自己变，不需要手动刷新。
 */
class ProfileViewModel(container: AppContainer) : ViewModel() {

    private val sessionDao = container.database.workoutSessionDao()
    private val exerciseDao = container.database.exerciseDao()
    private val resetRepository: DataResetRepository = container.dataResetRepository

    private val _clearing = MutableStateFlow(false)

    /** 清空进行中：按钮置灰，避免连点两下发两次事务。 */
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性回执，Snackbar 消费后清空。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    val stats: StateFlow<ProfileStats> = combine(
        sessionDao.observeFinishedSessionCount(),
        sessionDao.observeTotalDurationSec(),
        exerciseDao.observeAllBosses(),
    ) { workouts, seconds, bosses ->
        ProfileStats(
            workouts = workouts,
            totalSeconds = seconds,
            mastered = bosses.count { it.isMastered },
            totalBosses = bosses.size,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileStats(),
    )

    /**
     * 清空全部训练数据。
     *
     * 不做「删一部分」的选项：清空的价值就在于回到原点，留一半历史反而更困惑。
     * 清完立刻重灌 3 套模板 + 6 条 Lv.1 账本，所以这里只负责给一句回执。
     */
    fun clearAllData() {
        if (_clearing.value) return
        viewModelScope.launch {
            _clearing.value = true
            val result = resetRepository.clearAll()
            _clearing.value = false
            _message.value = result.fold(
                onSuccess = { "已清空：计划、历史、技能进度都回到初始状态" },
                onFailure = { "清空失败：${it.message ?: "数据库写入被中断"}，本机数据没有改动" },
            )
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProfileViewModel(container) as T
    }
}
