package io.github.kylin33.aifitgym.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.ai.AiSettingsState
import io.github.kylin33.aifitgym.data.ai.CoachRepository
import io.github.kylin33.aifitgym.data.ai.PlanBlockParser
import io.github.kylin33.aifitgym.data.ai.PlanProposal
import io.github.kylin33.aifitgym.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 复盘页顶部的「这次练了什么」，全部来自数据库，不花钱。 */
data class SessionBrief(
    val routineName: String,
    val whenText: String,
    val minutes: Int,
    val setCount: Int,
    val plannedSets: Int,
    val exerciseCount: Int,
    val plannedExercises: Int,
) {
    val isFreeTraining: Boolean get() = plannedSets == 0
}

data class ReviewUiState(
    val brief: SessionBrief? = null,
    val loading: Boolean = true,
    val feedback: String = "",
    val streaming: Boolean = false,
    /** 已经把本次成绩发出去过：再点一次就是重来，所以按钮文案要变。 */
    val asked: Boolean = false,
    val reply: String = "",
    val proposal: PlanProposal? = null,
    val applying: Boolean = false,
    val appliedRoutineId: Long? = null,
    val error: String? = null,
)

/**
 * 「练完让教练复盘」。
 *
 * 一个页面只做一件事：把这次成绩发出去、看回复、决定要不要应用改动。
 * 所以这里没有聊天历史 —— 复盘的上下文是「这一场训练」，不是「我们上个月聊过什么」。
 */
class SessionReviewViewModel(
    private val container: AppContainer,
    private val sessionId: Long,
) : ViewModel() {

    private val repository: CoachRepository = container.coachRepository
    private val sessionDao = container.database.workoutSessionDao()
    private val routineDao = container.database.routineDao()

    /** 开关与 Key 是否就绪。没开全程教练就不该从结算页点进来，这里仍然要兜一层。 */
    val settings: StateFlow<AiSettingsState> = container.aiSettings.state

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private val buffer = StringBuilder()

    init {
        loadBrief()
    }

    fun setFeedback(value: String) = _state.update { it.copy(feedback = value) }

    private fun loadBrief() {
        viewModelScope.launch {
            val brief = runCatching {
                val session = sessionDao.findSession(sessionId) ?: return@runCatching null
                val sets = sessionDao.setsOfSession(sessionId)
                val routine = session.routineId?.let { routineDao.find(it) }
                val planned = routine?.let { routineDao.findItems(it.routineId) }.orEmpty()
                SessionBrief(
                    routineName = session.routineNameSnapshot.ifBlank { "自由训练" },
                    whenText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        .format(Date(session.startTime)),
                    minutes = session.totalDurationSec / 60,
                    setCount = sets.size,
                    plannedSets = planned.sumOf { it.targetSets },
                    exerciseCount = sets.map { it.exerciseId }.distinct().size,
                    plannedExercises = planned.size,
                )
            }.getOrNull()

            _state.update {
                it.copy(brief = brief, loading = false, error = if (brief == null) "找不到这次训练的记录" else null)
            }
        }
    }

    fun send() {
        val current = _state.value
        if (current.streaming || current.brief == null) return

        buffer.setLength(0)
        _state.update { it.copy(streaming = true, reply = "", proposal = null, error = null, asked = true) }

        streamJob = viewModelScope.launch {
            try {
                val answer = repository.reviewSession(
                    sessionId = sessionId,
                    feedback = current.feedback,
                ) { delta ->
                    buffer.append(delta)
                    // 和聊天页同一套切分：json 块永远不进正文，流式过程中也不会闪出半截 JSON
                    val visible = PlanBlockParser.split(buffer.toString()).text
                    _state.update { it.copy(reply = visible) }
                }
                _state.update {
                    it.copy(
                        streaming = false,
                        reply = answer.text,
                        proposal = answer.proposal,
                    )
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val visible = PlanBlockParser.split(buffer.toString()).text
                    _state.update {
                        it.copy(
                            streaming = false,
                            reply = visible.ifBlank { "已停止" },
                            error = if (visible.isBlank()) null else it.error,
                        )
                    }
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        streaming = false,
                        error = e.message ?: "请求失败，检查网络或设置",
                    )
                }
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    fun discardProposal() = _state.update { it.copy(proposal = null) }

    fun applyProposal() {
        val proposal = _state.value.proposal ?: return
        if (_state.value.applying) return
        viewModelScope.launch {
            _state.update { it.copy(applying = true) }
            val result = runCatching { repository.applyProposal(proposal) }
            _state.update { current ->
                current.copy(
                    applying = false,
                    appliedRoutineId = result.getOrNull(),
                    proposal = if (result.isSuccess) null else current.proposal,
                    error = result.exceptionOrNull()?.let { "应用失败：${it.message ?: "写入被中断"}" },
                )
            }
        }
    }

    class Factory(
        private val container: AppContainer,
        private val sessionId: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SessionReviewViewModel(container, sessionId) as T
    }
}
