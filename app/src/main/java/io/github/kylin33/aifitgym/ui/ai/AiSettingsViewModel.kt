package io.github.kylin33.aifitgym.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.ai.AiProfile
import io.github.kylin33.aifitgym.data.ai.AiSettingsRepository
import io.github.kylin33.aifitgym.data.ai.AiSettingsState
import io.github.kylin33.aifitgym.data.ai.CoachRepository
import io.github.kylin33.aifitgym.data.ai.EQUIPMENT_NONE
import io.github.kylin33.aifitgym.data.ai.ProfileField
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

    fun setGoal(value: String) = updateProfile(ProfileField.GOAL) { it.copy(goal = value) }

    fun setLevel(value: String) = updateProfile(ProfileField.LEVEL) { it.copy(level = value) }

    /** 再点一次已选中的那一项表示「不想说」，值清空但记下他表过态。 */
    fun setExperience(value: String) = updateProfile(ProfileField.EXPERIENCE) { profile ->
        profile.copy(experience = if (profile.experience == value) "" else value)
    }

    fun setDays(value: Int) = updateProfile(ProfileField.DAYS_PER_WEEK) {
        it.copy(daysPerWeek = value)
    }

    fun setMinutes(value: Int) = updateProfile(ProfileField.SESSION_MINUTES) {
        it.copy(sessionMinutes = value)
    }

    fun setLimitations(value: String) = updateProfile(ProfileField.LIMITATIONS) {
        it.copy(limitations = value)
    }

    fun setSpace(value: String) = updateProfile(ProfileField.AVAILABLE_SPACE) {
        it.copy(availableSpace = value)
    }

    fun setNotes(value: String) = updateProfile(ProfileField.NOTES) { it.copy(notes = value) }

    /**
     * 勾选器械。
     *
     * 「无器械」和其余项互斥：同时存在「无器械」和「单杠」时，模型只能猜哪个是真的，
     * 十次里有几次会按有单杠排。选它就等于重新声明「我什么都没有」。
     */
    fun toggleEquipment(item: String) = updateProfile(ProfileField.EQUIPMENT) { profile ->
        val next = when {
            item in profile.equipment -> profile.equipment - item
            item == EQUIPMENT_NONE -> listOf(EQUIPMENT_NONE)
            else -> (profile.equipment - EQUIPMENT_NONE) + item
        }
        profile.copy(equipment = next)
    }

    fun toggleFocus(item: String) = updateProfile(ProfileField.PREFERRED_FOCUS) { profile ->
        val next = if (item in profile.preferredFocus) {
            profile.preferredFocus - item
        } else {
            profile.preferredFocus + item
        }
        profile.copy(preferredFocus = next)
    }

    /**
     * 勾选「哪几天能练」。
     *
     * 顺手把「每周练几次」同步成勾中的天数：两个字段说法不一致时，
     * 提示词里会同时出现「每周 3 练」和「周一/周三/周五/周日」，模型只能猜一个。
     */
    fun toggleTrainingDay(day: Int) = updateProfile(ProfileField.TRAINING_DAYS) { profile ->
        val next = if (day in profile.trainingDays) {
            profile.trainingDays - day
        } else {
            profile.trainingDays + day
        }.sorted()
        profile.copy(
            trainingDays = next,
            daysPerWeek = if (next.isEmpty()) profile.daysPerWeek else next.size,
            // 次数是从日子推出来的，一起标记，免得简报里写着「每周 3 练（默认值）」
            confirmed = profile.confirmed + ProfileField.DAYS_PER_WEEK,
        )
    }

    fun setAutoCoach(enabled: Boolean) = settings.saveAutoCoach(enabled)

    /**
     * 改档案的统一入口：**顺手记下这一项是用户自己填的**。
     *
     * 不记的话，简报里分不出「他说他练了三年」和「我们默认他是新手」，
     * 模型会把默认值当成事实，一路排错下去。
     */
    private fun updateProfile(field: String, transform: (AiProfile) -> AiProfile) {
        val next = transform(state.value.preferences.profile)
        settings.saveProfile(next.copy(confirmed = next.confirmed + field))
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
