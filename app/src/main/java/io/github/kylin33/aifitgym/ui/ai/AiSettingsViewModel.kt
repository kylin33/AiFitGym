package io.github.kylin33.aifitgym.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.ai.AiProfile
import io.github.kylin33.aifitgym.data.ai.AiSettingsRepository
import io.github.kylin33.aifitgym.data.ai.AiSettingsState
import io.github.kylin33.aifitgym.data.ai.CoachRepository
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 连通性测试的三种状态，用一个对象表达，UI 不用组合两个 bool。 */
data class AiTestState(
    val testing: Boolean = false,
    val ok: Boolean = false,
    val message: String? = null,
)

/**
 * AI 设置页。
 *
 * 写入是「逐字段立即落盘」而不是「底部一个保存按钮」：
 * 用户改了 Base URL 就直接去聊天页试了，忘记点保存会以为是功能坏了。
 * 只有 API Key 例外 —— 加密写入有成本，改成失焦/点完成时提交。
 */
class AiSettingsViewModel(container: AppContainer) : ViewModel() {

    private val settings: AiSettingsRepository = container.aiSettings
    private val coach: CoachRepository = container.coachRepository

    val state: StateFlow<AiSettingsState> = settings.state

    private val _test = MutableStateFlow(AiTestState())
    val test: StateFlow<AiTestState> = _test.asStateFlow()

    fun setBaseUrl(value: String) {
        val current = state.value.preferences.endpoint
        settings.saveEndpoint(current.copy(baseUrl = value.trim()))
        resetTest()
    }

    fun setModel(value: String) {
        val current = state.value.preferences.endpoint
        settings.saveEndpoint(current.copy(model = value.trim()))
        resetTest()
    }

    fun applyPreset(baseUrl: String, model: String) {
        val current = state.value.preferences.endpoint
        // 「中转站」这类预设故意留空地址：只换模型名，别把用户已经填好的 URL 清掉
        settings.saveEndpoint(
            current.copy(
                baseUrl = baseUrl.ifBlank { current.baseUrl },
                model = model.ifBlank { current.model },
            ),
        )
        resetTest()
    }

    fun saveKey(value: String) {
        settings.saveApiKey(value)
        resetTest()
    }

    fun clearKey() {
        settings.saveApiKey(null)
        resetTest()
    }

    fun setGoal(value: String) = updateProfile { it.copy(goal = value) }

    fun setLevel(value: String) = updateProfile { it.copy(level = value) }

    fun setDays(value: Int) = updateProfile { it.copy(daysPerWeek = value) }

    fun setMinutes(value: Int) = updateProfile { it.copy(sessionMinutes = value) }

    fun setNotes(value: String) = updateProfile { it.copy(notes = value) }

    private fun updateProfile(transform: (AiProfile) -> AiProfile) {
        settings.saveProfile(transform(state.value.preferences.profile))
    }

    /**
     * 发一句「只回复收到」验证三件事：域名通、Key 有效、模型名存在。
     * 失败信息直接来自网关的错误码映射，比让用户自己去猜 401 是什么好得多。
     */
    fun testConnection() {
        if (_test.value.testing) return
        viewModelScope.launch {
            _test.value = AiTestState(testing = true)
            val result = coach.testConnection()
            _test.value = result.fold(
                onSuccess = { AiTestState(ok = true, message = it) },
                onFailure = { AiTestState(ok = false, message = it.message ?: "连接失败") },
            )
        }
    }

    private fun resetTest() {
        if (_test.value.message != null) _test.update { AiTestState() }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiSettingsViewModel(container) as T
    }
}
