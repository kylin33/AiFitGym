package io.github.kylin33.aifitgym.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.kylin33.aifitgym.MainActivity
import io.github.kylin33.aifitgym.R
import io.github.kylin33.aifitgym.service.WorkoutTimerBridge.NO_DEADLINE
import io.github.kylin33.aifitgym.service.WorkoutTimerBridge.TimerSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 跟练期间的前台服务：让休息倒计时在锁屏、切微信、Doze 里都活着。
 *
 * 服务本身**不是**计时器 —— 计时的事实是 [TimerSnapshot.deadline]（一个
 * `elapsedRealtime` 截止点），界面和通知都从它各算各的剩余，所以两边永远一致。
 * 服务只负责三件事：
 * 1. 挂住前台通知，把进程优先级提到「不会被随手回收」；
 * 2. 倒计时阶段持有 PARTIAL_WAKE_LOCK，别让息屏把 CPU 挂起导致提示音迟到；
 * 3. 把通知栏按钮变成 [WorkoutTimerBridge.Command] 交回状态机。
 *
 * 计次动作等用户点「完成」的时段不持有唤醒锁也不刷新通知 —— 那时没人需要准点响铃，
 * 白亮着屏幕只会费电。
 */
class WorkoutTimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renderJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRenderKey: String? = null

    private val manager: NotificationManager
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            // 按钮只投递意图，真正的状态推进在 ViewModel 里做
            ACTION_EXTEND -> WorkoutTimerBridge.post(WorkoutTimerBridge.Command.EXTEND_REST)
            ACTION_SKIP -> WorkoutTimerBridge.post(WorkoutTimerBridge.Command.SKIP_REST)
            ACTION_FINISH -> WorkoutTimerBridge.post(WorkoutTimerBridge.Command.FINISH)
        }

        // startForegroundService 之后必须立刻进前台，不能等第一轮渲染
        if (renderJob?.isActive != true) {
            startForeground(NOTIFICATION_ID, buildNotification(WorkoutTimerBridge.snapshot.value))
        }
        ensureRenderLoop()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        renderJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 渲染循环

    /**
     * 每 [POLL_MS] 用截止点重算一次剩余。
     *
     * 500ms 而不是 1s：跨秒的那一下最多晚半秒，通知栏数字不会「跳两下」。
     * 只在内容真的变了才 notify，避免无谓地叫醒系统。
     */
    private fun ensureRenderLoop() {
        if (renderJob?.isActive == true) return
        renderJob = scope.launch {
            while (isActive) {
                val snapshot = WorkoutTimerBridge.snapshot.value
                if (snapshot == null) {
                    // 训练结束 / 页面销毁：快照没了就收工
                    stopEverything()
                    return@launch
                }

                val remainingMs = remainingOf(snapshot)
                val remainingSec = ceil(remainingMs / 1000.0).toInt()
                syncWakeLock(snapshot.deadline != NO_DEADLINE)

                val key = buildString {
                    append(snapshot.title).append('|').append(snapshot.subtitle)
                    append('|').append(snapshot.subText).append('|').append(remainingSec)
                    append('|').append(snapshot.allowExtend).append(snapshot.allowSkip)
                }
                if (key != lastRenderKey) {
                    lastRenderKey = key
                    manager.notify(NOTIFICATION_ID, buildNotification(snapshot, remainingMs))
                }
                delay(POLL_MS)
            }
        }
    }

    private fun remainingOf(snapshot: TimerSnapshot): Long {
        if (snapshot.deadline == NO_DEADLINE) return 0L
        return (snapshot.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    private fun stopEverything() {
        // 快照只由 ViewModel 单向写入，服务这边只读不写：否则服务一停就把界面状态抹了，
        // 界面下次更新同样的内容又会被去重拦住，通知就再也回不来了
        renderJob?.cancel()
        renderJob = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- 唤醒锁

    /**
     * 只在倒计时阶段拿锁：息屏后 CPU 会 suspend，协程和 ToneGenerator 一起停摆，
     * 90 秒休息结束时那声「嘀」就会迟到甚至不响。带超时上限，不会挂死电池。
     */
    private fun syncWakeLock(needed: Boolean) {
        if (!needed) {
            releaseWakeLock()
            return
        }
        val lock = wakeLock ?: runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        }.getOrNull()?.also { wakeLock = it } ?: return

        if (!lock.isHeld) {
            runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "训练计时", NotificationManager.IMPORTANCE_LOW).apply {
            description = "组间休息与计时动作的倒计时"
            setSound(null, null) // 提示音由 WorkoutAudioManager 统一控制，别双重响
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        snapshot: TimerSnapshot?,
        remainingMs: Long = 0L,
    ): Notification {
        val title = snapshot?.title ?: "训练准备中"
        val subtitle = snapshot?.subtitle.orEmpty()
        val remainingSec = ceil(remainingMs / 1000.0).toInt()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(
                if (snapshot != null && snapshot.deadline != NO_DEADLINE) {
                    "${formatClock(remainingSec)} · $subtitle"
                } else {
                    subtitle
                },
            )
            .setSubText(snapshot?.subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setColor(ACCENT_COLOR)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())

        if (snapshot != null && snapshot.deadline != NO_DEADLINE && snapshot.totalMs > 0L) {
            val done = 1f - remainingMs.toFloat() / snapshot.totalMs
            builder.setProgress(100, (done.coerceIn(0f, 1f) * 100).toInt(), false)
        }

        if (snapshot?.allowExtend == true) {
            builder.addAction(0, "＋15 秒", serviceIntent(ACTION_EXTEND, REQ_EXTEND))
        }
        if (snapshot?.allowSkip == true) {
            builder.addAction(0, "跳过休息", serviceIntent(ACTION_SKIP, REQ_SKIP))
        }
        builder.addAction(0, "结束训练", serviceIntent(ACTION_FINISH, REQ_FINISH))

        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQ_OPEN,
        Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, WorkoutTimerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun formatClock(seconds: Int): String =
        "%d:%02d".format(seconds / 60, seconds % 60)

    companion object {
        private const val CHANNEL_ID = "workout_timer"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_SYNC = "io.github.kylin33.aifitgym.timer.SYNC"
        private const val ACTION_EXTEND = "io.github.kylin33.aifitgym.timer.EXTEND"
        private const val ACTION_SKIP = "io.github.kylin33.aifitgym.timer.SKIP"
        private const val ACTION_FINISH = "io.github.kylin33.aifitgym.timer.FINISH"
        private const val ACTION_STOP = "io.github.kylin33.aifitgym.timer.STOP"

        private const val REQ_OPEN = 10
        private const val REQ_EXTEND = 11
        private const val REQ_SKIP = 12
        private const val REQ_FINISH = 13

        private const val POLL_MS = 500L

        /** 单次持锁上限。每个阶段会重新申请，长时间训练也不会被提前掐断。 */
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_TAG = "FitGym::WorkoutTimer"

        private val ACCENT_COLOR = 0xFF11855C.toInt()

        /** 训练开始时调一次：拉起前台服务。必须在前台（跟练页可见）时调用。 */
        fun startTimer(context: Context) {
            val intent = Intent(context, WorkoutTimerService::class.java).setAction(ACTION_SYNC)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 训练结束 / 页面销毁：撤掉通知并停服务。 */
        fun stopTimer(context: Context) {
            runCatching { context.stopService(Intent(context, WorkoutTimerService::class.java)) }
        }
    }
}
