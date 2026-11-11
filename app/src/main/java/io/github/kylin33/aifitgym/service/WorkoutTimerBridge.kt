package io.github.kylin33.aifitgym.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跟练界面与前台服务之间唯一的通道。
 *
 * 这里跨界的不是「剩余多少秒」，而是 [TimerSnapshot.deadline] —— 阶段的绝对截止时刻。
 * 界面用 `SystemClock.elapsedRealtime()` 算剩余，通知栏也用同一个截止点各算各的，
 * 谁被挂起都不会让另一个走慢，两边也不需要互相追：唯一的事实只有那个物理时刻。
 */
object WorkoutTimerBridge {

    /** 通知栏按钮回传给界面状态机的指令。 */
    enum class Command {
        /** 休息 +15 秒。 */
        EXTEND_REST,

        /** 跳过剩下的休息。 */
        SKIP_REST,

        /** 提前结束整场训练。 */
        FINISH,
    }

    /**
     * 当前阶段的展示快照。
     *
     * @param title 一行主文案（休息 / 准备开始 / 动作名）
     * @param subtitle 副文案（组次、目标、下一组是什么）
     * @param subText 通知栏右上角的整体进度
     * @param deadline 阶段截止时刻（elapsedRealtime）；[NO_DEADLINE] 表示这一段不计时
     * @param totalMs 阶段总时长，进度条分母
     */
    data class TimerSnapshot(
        val title: String,
        val subtitle: String,
        val subText: String,
        val deadline: Long,
        val totalMs: Long,
        val allowExtend: Boolean = false,
        val allowSkip: Boolean = false,
    )

    /** 计次动作等用户点「完成」，没有截止时刻。 */
    const val NO_DEADLINE = 0L

    private val _snapshot = MutableStateFlow<TimerSnapshot?>(null)

    /** null = 训练没在进行，服务该收工了。 */
    val snapshot: StateFlow<TimerSnapshot?> = _snapshot.asStateFlow()

    // 按钮连点也只会排队几条，满了丢最旧的：按钮是「当前的意图」，旧的没意义
    private val _commands = MutableSharedFlow<Command>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    internal fun publish(snapshot: TimerSnapshot?) {
        _snapshot.value = snapshot
    }

    /** 由通知栏按钮调用（Service 侧）。 */
    fun post(command: Command) {
        _commands.tryEmit(command)
    }
}
