package io.github.kylin33.aifitgym.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.kylin33.aifitgym.R

/**
 * 跟练提示音 + 震动，**不打断用户正在放的歌**。
 *
 * 音频焦点请求 [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK]：系统把 Spotify / 网易云
 * 临时压低，播完再还回去 —— 而不是 GAIN 那种「把别人暂停掉」的强夺。
 *
 * 焦点不是每响一声申请一次：连响三下「滴」如果每秒 abandon + request 一遍，背景音乐的音量
 * 会跟着一上一下地抽。这里用「持有 + 延时释放」：连响期间焦点一直在我手上，
 * 最后一响之后 [FOCUS_TAIL_MS] 才松开。
 *
 * 单例由 [io.github.kylin33.aifitgym.di.AppContainer] 持有，跟 Application 同寿命。
 */
class WorkoutAudioManager(context: Context) {

    private val appContext = context.applicationContext

    private val audioManager: AudioManager? =
        runCatching { appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()

    /**
     * API 31 起推荐 VibratorManager，但 `getSystemService(Vibrator::class.java)`
     * 在 31+ 仍然返回默认马达，省一个分支。
     */
    private val vibrator: Vibrator? =
        runCatching { appContext.getSystemService(Vibrator::class.java) }.getOrNull()

    /**
     * **必须走媒体通道（USAGE_MEDIA）。**
     *
     * 最初用的是 `USAGE_ASSISTANCE_SONIFICATION`，结果实测「只有震动没有声音」——
     * 那个 usage 会被路由到系统音效通道，手机只要处于静音/震动模式，那个通道就是 0，
     * 而媒体通道（音乐、视频）的音量是独立的一套，静音模式并不会把它压到 0。
     * 提示音本来就是和用户的歌一起听的，跟着媒体音量走才是对的。
     *
     * contentType 仍标 sonification：告诉系统这是短提示音，别当成一首歌去做媒体会话。
     */
    private val cueAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundPool: SoundPool? = runCatching {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(cueAttributes)
            .build()
    }.getOrNull()

    private var tickId = 0
    private var goId = 0
    private var doneId = 0

    /** 加载成功的样本数。三个都到齐之前不做真播放，交给 [fallbackTone] 顶着。 */
    @Volatile
    private var readySamples = 0

    /**
     * 兜底音源。
     *
     * 两条路径会用到它：SoundPool 还没加载完（刚进训练就点开始），
     * 或者某些 ROM 解不了这个 wav 导致 load 失败。宁可音色差一点，也不能没声音。
     *
     * 懒创建：ToneGenerator 一造出来就占着一条 AudioTrack，SoundPool 正常工作时
     * 这一整场训练都不该有它的事。
     */
    private var fallbackTone: ToneGenerator? = null
    private var fallbackRequested = false

    private val handler = Handler(Looper.getMainLooper())
    private var holdingFocus = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { /* 提示音不关心被抢 */ }

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(cueAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        } else {
            null
        }

    private val releaseFocus = Runnable { abandonFocus() }

    init {
        // listener 必须在 load 之前挂好，否则加载太快会漏掉回调
        soundPool?.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) readySamples++
        }
        tickId = soundPool?.load(appContext, R.raw.tick, 1) ?: 0
        goId = soundPool?.load(appContext, R.raw.go, 1) ?: 0
        doneId = soundPool?.load(appContext, R.raw.done, 1) ?: 0
    }

    // ---------------------------------------------------------------- 三种提示

    /** 倒计时最后三秒的短提示。 */
    fun tick() {
        holdFocus()
        play(tickId, ToneGenerator.TONE_PROP_BEEP)
        vibrateOneShot(TICK_VIBRATION_MS, TICK_AMPLITUDE)
        scheduleFocusRelease()
    }

    /** 开始一组 / 休息结束。 */
    fun go() {
        holdFocus()
        play(goId, ToneGenerator.TONE_PROP_ACK)
        vibrateOneShot(GO_VIBRATION_MS, GO_AMPLITUDE)
        scheduleFocusRelease()
    }

    /** 一组结束、整场结算。 */
    fun done() {
        holdFocus()
        play(doneId, ToneGenerator.TONE_PROP_BEEP2)
        vibratePattern(DONE_TIMINGS, DONE_AMPLITUDES)
        scheduleFocusRelease()
    }

    /** 进程结束前回收；跟练页退出**不要**调，这是全应用共享的。 */
    fun release() {
        handler.removeCallbacks(releaseFocus)
        abandonFocus()
        runCatching { soundPool?.release() }
        runCatching { fallbackTone?.release() }
    }

    // ---------------------------------------------------------------- 音频焦点

    private fun holdFocus() {
        // 还在「余音期」里：取消这次的释放计划就够了，焦点继续拿着
        handler.removeCallbacks(releaseFocus)
        if (holdingFocus) return

        val manager = audioManager ?: return
        holdingFocus = requestFocus(manager)
    }

    private fun scheduleFocusRelease() {
        handler.removeCallbacks(releaseFocus)
        handler.postDelayed(releaseFocus, FOCUS_TAIL_MS)
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(manager: AudioManager): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
        // 拿不到焦点也照样播：只是压低不了别人的音乐，不该因此让训练没声音
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (!holdingFocus) return
        holdingFocus = false
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            manager.abandonAudioFocus(focusListener)
        }
    }

    // ---------------------------------------------------------------- 播放与震动

    private fun play(soundId: Int, fallbackCue: Int) {
        val pool = soundPool
        if (pool == null || soundId == 0 || readySamples < SAMPLE_COUNT) {
            playFallbackCue(fallbackCue)
            return
        }
        // play 返回 0 表示这次没排上（流数超限、样本失效），同样交给兜底
        val streamId = runCatching { pool.play(soundId, VOLUME, VOLUME, 1, 0, 1f) }.getOrDefault(0)
        if (streamId == 0) playFallbackCue(fallbackCue)
    }

    private fun playFallbackCue(cue: Int) {
        if (!fallbackRequested) {
            fallbackRequested = true
            fallbackTone = runCatching {
                ToneGenerator(AudioManager.STREAM_MUSIC, FALLBACK_VOLUME)
            }.getOrNull()
        }
        runCatching { fallbackTone?.startTone(cue, FALLBACK_DURATION_MS) }
    }

    @Suppress("DEPRECATION")
    private fun vibrateOneShot(durationMs: Long, amplitude: Int) {
        val device = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                device.vibrate(durationMs)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibratePattern(timings: LongArray, amplitudes: IntArray) {
        val device = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                device.vibrate(timings, -1)
            }
        }
    }

    private companion object {
        const val SAMPLE_COUNT = 3
        const val VOLUME = 1f
        const val FALLBACK_VOLUME = 100

        /** 兜底音是合成音，时长交给 ToneGenerator 自己收，给足 300ms。 */
        const val FALLBACK_DURATION_MS = 300

        /**
         * 最后一响之后多久还回音频焦点。
         *
         * 必须大于倒计时两响的间隔（1 秒），否则连续「滴滴滴」会变成
         * 「压低—恢复—压低—恢复」，比不压还吵。
         */
        const val FOCUS_TAIL_MS = 1_200L

        const val TICK_VIBRATION_MS = 35L
        const val GO_VIBRATION_MS = 60L

        /** 0~255，取一半左右：手机贴在地板/口袋里能感觉到，但不至于吓人。 */
        const val TICK_AMPLITUDE = 110
        const val GO_AMPLITUDE = 150

        val DONE_TIMINGS = longArrayOf(0L, 130L, 60L, 200L)
        val DONE_AMPLITUDES = intArrayOf(0, 190, 0, 150)
    }
}
