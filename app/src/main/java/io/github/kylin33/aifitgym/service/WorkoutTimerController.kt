package io.github.kylin33.aifitgym.service

import android.content.Context
import io.github.kylin33.aifitgym.service.WorkoutTimerBridge.TimerSnapshot
import kotlinx.coroutines.flow.SharedFlow

/**
 * 界面侧往计时服务走的唯一门面。
 *
 * ViewModel 不直接碰 Context 和 Intent，只跟这一层打交道：既保住了「ViewModel 里没有 Android 平台代码」
 * 的边界，也把「先写快照、再拉服务」的顺序收在一个地方 —— 服务一起来就能读到有效快照。
 */
class WorkoutTimerController(context: Context) {

    private val appContext = context.applicationContext

    /** 通知栏按钮的指令流，ViewModel 收来直接推进状态机。 */
    val commands: SharedFlow<WorkoutTimerBridge.Command> = WorkoutTimerBridge.commands

    /** 只更新快照（纯内存），供 ticker 每轮调用。 */
    fun publish(snapshot: TimerSnapshot?) {
        WorkoutTimerBridge.publish(snapshot)
    }

    /**
     * 进入训练时调一次：先把快照投进去，再拉起前台服务。
     * 必须在界面可见时调用（Android 12+ 禁止从后台启动前台服务）。
     */
    fun attach(snapshot: TimerSnapshot) {
        WorkoutTimerBridge.publish(snapshot)
        WorkoutTimerService.startTimer(appContext)
    }

    /** 训练收尾 / ViewModel 销毁：清空快照并撤掉通知。 */
    fun detach() {
        WorkoutTimerBridge.publish(null)
        WorkoutTimerService.stopTimer(appContext)
    }
}
