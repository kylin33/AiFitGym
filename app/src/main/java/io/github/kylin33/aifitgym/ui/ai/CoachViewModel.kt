package io.github.kylin33.aifitgym.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.kylin33.aifitgym.data.ai.AiSettingsState
import io.github.kylin33.aifitgym.data.ai.CoachChatStore
import io.github.kylin33.aifitgym.data.ai.CoachRepository
import io.github.kylin33.aifitgym.data.ai.OpenAiClient
import io.github.kylin33.aifitgym.data.ai.PlanBlockParser
import io.github.kylin33.aifitgym.data.ai.PlanDraft
import io.github.kylin33.aifitgym.data.ai.StoredChatMessage
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

enum class CoachRole { USER, COACH }

/**
 * 聊天气泡。
 *
 * [raw] 存含 json 代码块的原始回复，只用于落盘与回填，界面上永远显示 [text]。
 * [importedRoutineId] 非空表示这份计划已经导进「我的计划」，按钮随之变成「已加入」。
 */
data class CoachMessage(
    val id: Long,
    val role: CoachRole,
    val text: String,
    val plan: PlanDraft? = null,
    val raw: String? = null,
    val failed: Boolean = false,
    val importedRoutineId: Long? = null,
)

data class CoachUiState(
    val messages: List<CoachMessage> = emptyList(),
    val streaming: Boolean = false,
    /** 正在跑的非流式操作（导入计划）。 */
    val importing: Boolean = false,
    val restoring: Boolean = true,
) {
    val canSend: Boolean get() = !streaming
}

/**
 * AI 教练的会话状态。
 *
 * 流式回复的处理方式：把模型吐出来的**累计原文**每次重新切一遍（[PlanBlockParser.split]），
 * 只把代码块之外的部分显示出来。这样「正在生成的 json」不会以半截乱码的形式闪在气泡里，
 * 而且切分逻辑只有一份 —— 结束时的正式解析和过程中的预览走的是同一个函数。
 */
class CoachViewModel(container: AppContainer) : ViewModel() {

    private val repository: CoachRepository = container.coachRepository
    private val store: CoachChatStore = container.coachChatStore

    private val _state = MutableStateFlow(CoachUiState())
    val state: StateFlow<CoachUiState> = _state.asStateFlow()

    val settings: StateFlow<AiSettingsState> = container.aiSettings.state

    /** 一次性回执（导入成功等），Snackbar 消费后清空。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var nextId = 1L
    private var streamJob: Job? = null
    private val buffer = StringBuilder()

    init {
        viewModelScope.launch { restore() }
    }

    // ------------------------------------------------------------ 发送 / 停止

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.streaming) return

        // 历史只带「已经完成的往来」，再加上这一条新问题 —— 不能让 pending 那条空消息进上下文。
        val history = _state.value.messages
            .filter { !it.failed && it.text.isNotBlank() }
            .map { CoachMessage_.toChat(it) } + OpenAiClient.ChatMessage("user", trimmed)

        val userMessage = CoachMessage(id = nextId++, role = CoachRole.USER, text = trimmed)
        val pending = CoachMessage(id = nextId++, role = CoachRole.COACH, text = "")
        buffer.setLength(0)

        _state.update {
            it.copy(messages = it.messages + userMessage + pending, streaming = true)
        }

        streamJob = viewModelScope.launch {
            try {
                val answer = repository.ask(history) { delta -> appendDelta(pending.id, delta) }
                val finished = _state.value.messages.map { message ->
                    if (message.id != pending.id) {
                        message
                    } else {
                        message.copy(text = answer.text, plan = answer.plan, raw = answer.raw)
                    }
                }
                _state.update { it.copy(messages = finished, streaming = false) }
                persist()
            } catch (e: CancellationException) {
                // 用户主动点了停止：把已经收到的部分留下，顺手再解析一次（可能刚好写完计划）
                // 已经被取消的协程里任何挂起点都会立刻再抛，所以这一段必须 NonCancellable
                withContext(NonCancellable) { settleCancelled(pending.id) }
                throw e
            } catch (e: Exception) {
                val detail = e.message ?: "请求失败，检查网络或设置"
                _state.update { current ->
                    current.copy(
                        streaming = false,
                        messages = current.messages.map { message ->
                            if (message.id != pending.id) {
                                message
                            } else {
                                message.copy(text = detail, failed = true)
                            }
                        },
                    )
                }
                persist()
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun appendDelta(id: Long, delta: String) {
        buffer.append(delta)
        val visible = PlanBlockParser.split(buffer.toString()).text
        _state.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id == id) message.copy(text = visible) else message
                },
            )
        }
    }

    private suspend fun settleCancelled(id: Long) {
        val raw = buffer.toString()
        val split = PlanBlockParser.split(raw)
        val plan = if (raw.isNotBlank()) repository.resolvePlan(raw) else null
        _state.update { current ->
            current.copy(
                streaming = false,
                messages = current.messages.map { message ->
                    when {
                        message.id != id -> message
                        split.text.isBlank() && plan == null -> message.copy(
                            text = "已停止",
                            failed = true,
                        )

                        else -> message.copy(
                            text = split.text.ifBlank { "（已停止，计划还没写完）" },
                            plan = plan,
                            raw = raw,
                        )
                    }
                },
            )
        }
        persist()
    }

    // ------------------------------------------------------------ 计划导入

    fun importPlan(messageId: Long) {
        if (_state.value.importing) return
        val draft = _state.value.messages.firstOrNull { it.id == messageId }?.plan ?: return
        viewModelScope.launch {
            _state.update { it.copy(importing = true) }
            val result = runCatching { repository.importPlan(draft) }
            _state.update { current ->
                current.copy(
                    importing = false,
                    messages = current.messages.map { message ->
                        if (message.id == messageId) {
                            message.copy(importedRoutineId = result.getOrNull())
                        } else {
                            message
                        }
                    },
                )
            }
            _message.value = result.fold(
                onSuccess = { "「${draft.name}」已加入我的计划" },
                onFailure = { "导入失败：${it.message ?: "数据库写入被中断"}" },
            )
        }
    }

    fun newChat() {
        stop()
        buffer.setLength(0)
        _state.update { CoachUiState(restoring = false) }
        viewModelScope.launch { store.clear() }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------ 落盘 / 回填

    private suspend fun persist() {
        val stored = _state.value.messages
            .filter { it.text.isNotBlank() && !it.failed }
            .map { message ->
                when (message.role) {
                    CoachRole.USER -> StoredChatMessage("user", message.text)
                    CoachRole.COACH -> StoredChatMessage(
                        role = "assistant",
                        text = message.text,
                        raw = message.raw,
                    )
                }
            }
        store.save(stored)
    }

    /**
     * 回填上次的对话。
     *
     * 计划卡片会重新解析一遍：只有这样才能恢复「一键导入」按钮，
     * 否则重开 App 后旧计划的卡片就变成一张纯展示的图片。
     */
    private suspend fun restore() {
        val stored = runCatching { store.load() }.getOrDefault(emptyList())
        if (stored.isEmpty()) {
            _state.update { it.copy(restoring = false) }
            return
        }
        val restored = stored.map { message ->
            if (message.role == "user") {
                CoachMessage(id = nextId++, role = CoachRole.USER, text = message.text)
            } else {
                val raw = message.raw
                val plan = raw?.let { runCatching { repository.resolvePlan(it) }.getOrNull() }
                CoachMessage(
                    id = nextId++,
                    role = CoachRole.COACH,
                    text = message.text,
                    plan = plan,
                    raw = raw,
                )
            }
        }
        _state.update { current ->
            // 回填期间用户已经开聊了就别覆盖
            if (current.messages.isEmpty()) {
                current.copy(messages = restored, restoring = false)
            } else {
                current.copy(restoring = false)
            }
        }
    }

    /** UI 消息 → 发给模型的消息。assistant 优先用原始回复（带格式示范）。 */
    private object CoachMessage_ {
        fun toChat(message: CoachMessage): OpenAiClient.ChatMessage = when (message.role) {
            CoachRole.USER -> OpenAiClient.ChatMessage(role = "user", content = message.text)
            CoachRole.COACH -> OpenAiClient.ChatMessage(
                role = "assistant",
                content = message.raw ?: message.text,
            )
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CoachViewModel(container) as T
    }
}
