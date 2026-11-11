package io.github.kylin33.aifitgym.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.backup.BackupRepository
import io.github.kylin33.aifitgym.data.backup.WorkoutBackupPayload
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 备份页顶部「本机有什么」的计数。 */
data class BackupOverview(
    val routines: Int = 0,
    val workouts: Int = 0,
    val totalSeconds: Int = 0,
    val mastered: Int = 0,
)

data class BackupUiState(
    /** 正在读写文件/跑事务，期间按钮置灰。 */
    val running: Boolean = false,
    /** 已经读进来、等用户确认覆盖的备份；非空时弹确认框。 */
    val pending: WorkoutBackupPayload? = null,
    /** 一次性提示，Snackbar 消费完清空。 */
    val message: String? = null,
)

/**
 * 备份与恢复。
 *
 * 文件读写走 SAF（`content://`），不需要任何存储权限 —— Android 10 之后
 * `WRITE_EXTERNAL_STORAGE` 基本作废，而用户主动选的 Uri 自带读写授权。
 *
 * 关键取舍：**导入分两步**。选中文件立刻解析并展示「里面有几套计划、几次训练」，
 * 用户点确认才真正覆盖。恢复是破坏性操作，一次误触就能把几个月的历史换成别的存档。
 */
class BackupViewModel(container: AppContainer) : ViewModel() {

    private val repository: BackupRepository = container.backupRepository
    private val resolver = container.contentResolver

    private val sessionDao = container.database.workoutSessionDao()

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    val overview: StateFlow<BackupOverview> = combine(
        container.database.routineDao().observeSummaries(),
        sessionDao.observeFinishedSessionCount(),
        sessionDao.observeTotalDurationSec(),
        container.database.exerciseDao().observeAllBosses(),
    ) { routines, workouts, seconds, bosses ->
        BackupOverview(
            routines = routines.size,
            workouts = workouts,
            totalSeconds = seconds,
            mastered = bosses.count { it.isMastered },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BackupOverview(),
    )

    // ------------------------------------------------------------ 导出

    fun export(uri: Uri) {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true) }
            val exported = runCatching { repository.export() }
            val payload = exported.getOrNull()
            if (payload == null) {
                finish("导出失败：${exported.exceptionOrNull()?.message ?: "读取本机数据出错"}")
                return@launch
            }
            val written = writeText(uri, repository.encode(payload))
            finish(
                if (written) "已导出 ${payload.summary().describe()}"
                else "写入文件失败，换一个目录再试试"
            )
        }
    }

    // ------------------------------------------------------------ 导入

    fun pickImport(uri: Uri) {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true) }
            val text = readText(uri)
            if (text == null) {
                finish("读不到这个文件，可能已经被移动或没有访问权限")
                return@launch
            }
            val decoded = repository.decode(text)
            val payload = decoded.getOrNull()
            if (payload == null) {
                finish(decoded.exceptionOrNull()?.message ?: "文件格式不匹配")
                return@launch
            }
            // 解析成功先不落库，等用户确认
            _state.update { it.copy(running = false, pending = payload) }
        }
    }

    fun confirmImport() {
        val payload = _state.value.pending ?: return
        viewModelScope.launch {
            _state.update { it.copy(running = true, pending = null) }
            val result = repository.restore(payload)
            finish(
                result.fold(
                    onSuccess = { "恢复完成：${it.describe()}" },
                    onFailure = { "恢复失败：${it.message ?: "数据库写入被中断"}，本机数据没有改动" },
                )
            )
        }
    }

    fun cancelImport() {
        _state.update { it.copy(pending = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    // ------------------------------------------------------------ 文件 IO

    private fun finish(message: String) {
        _state.update { it.copy(running = false, message = message) }
    }

    private suspend fun writeText(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val stream = resolver.openOutputStream(uri) ?: return@withContext false
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    private suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BackupViewModel(container) as T
    }
}
