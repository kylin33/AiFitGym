package io.github.kylin33.aifitgym.ui.components

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 动作演示：有短片就静音循环播放，没有就退回静态图。
 *
 * 上游的 `media/still/<id>.webp` 是**单帧**图（180×180、约 5 KB），动作过程只在
 * `media/clip/<id>.mp4` 里（同样 180×180、约 9 秒）。短片太大不方便全量内置，
 * 所以 assets/clips/ 里只放常用动作，其余动作显示静态图 —— 两种情况尺寸一致，
 * 界面不会跳。
 */
@Composable
fun ExerciseClip(
    exerciseId: String,
    imagePath: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    if (!ClipAssets.has(context, exerciseId)) {
        ExerciseImage(
            path = imagePath,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    val exoPlayer = remember(exerciseId) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("asset:///clips/$exerciseId.mp4"))
            // 演示片是循环动作：往复循环、静音、一进页面就播
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    // 页面退出时必须 release，否则解码器会一直被占着，多个动作切来切去会 OOM
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // 切后台暂停：不留一个后台还在解码的播放器，回前台自动续播
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_START -> exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 底色和 ExerciseImage 保持一致：短片是方形的，FIT 进宽幅容器时两侧留空，
    // 有底色才像「一块演示区」，不然会显得 Layout 漏了一块
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    // 180×180 的方形片子放进宽幅容器时，FIT 比 ZOOM 好：
                    // ZOOM 会把人物裁掉，FIT 只是两边留空，背景透明看不出来
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(Color.TRANSPARENT)
                    // 注意别写成 this.player：apply 里那个是 PlayerView 自己的属性
                    player = exoPlayer
                }
            },
            update = { view -> view.player = exoPlayer },
            onRelease = { view -> view.player = null },
        )
    }
}

/**
 * `assets/clips/` 里到底有哪些短片。
 *
 * 每个动作都去 `assets.open()` 试一次会抛大量异常，直接列目录拿集合，进程内缓存一份。
 * 目前只有几十个文件，同步读取的开销远小于一次 IO 线程切换带来的首帧闪烁。
 */
internal object ClipAssets {

    @Volatile
    private var ids: Set<String>? = null

    fun has(context: Context, exerciseId: String): Boolean = available(context).contains(exerciseId)

    private fun available(context: Context): Set<String> {
        ids?.let { return it }
        synchronized(this) {
            ids?.let { return it }
            val loaded = runCatching {
                context.assets.list("clips")
                    ?.asSequence()
                    ?.filter { it.endsWith(".mp4", ignoreCase = true) }
                    ?.map { it.substringBeforeLast('.') }
                    ?.toSet()
                    .orEmpty()
            }.getOrDefault(emptySet())
            ids = loaded
            return loaded
        }
    }
}
