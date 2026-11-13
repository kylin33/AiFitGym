package io.github.kylin33.aifitgym.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kylin33.aifitgym.FitGymApp
import io.github.kylin33.aifitgym.data.clip.ClipDownload
import io.github.kylin33.aifitgym.data.clip.ClipStore

/**
 * 拿应用级的 [ClipStore]。
 *
 * 没走 CompositionLocal / 参数透传：只有播放和下载两处用得到，为一个单例把
 * 容器沿 LibraryScreen → Sheet 一路传下来反而更吵。Application 是唯一实例，
 * 这里取到的必然是 AppContainer 里那个。
 */
@Composable
fun rememberClipStore(): ClipStore {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as FitGymApp).container.clipStore
    }
}

/**
 * 演示短片的补全入口：内置的动作什么都不显示，缺片的给一个「下载」。
 *
 * 放抽屉和详情页两处 —— 用户从哪看到这个动作，就能在哪把它的演示补上，
 * 不用为了一个 30 KB 的片子跳回动作库再找一遍。
 */
@Composable
fun ClipDownloadAction(
    exerciseId: String,
    modifier: Modifier = Modifier,
) {
    val store = rememberClipStore()
    val available by store.available.collectAsStateWithLifecycle()
    val downloads by store.downloads.collectAsStateWithLifecycle()

    // 有片就直接播，不需要任何提示，更不需要一行「已就绪」占地方
    if (exerciseId in available) return

    when (val state = downloads[exerciseId]) {
        is ClipDownload.Running -> Column(modifier.fillMaxWidth()) {
            Text(
                text = if (state.total > 0) {
                    "正在下载 ${(state.fraction * 100).toInt()}%"
                } else {
                    "正在下载…"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is ClipDownload.Failed -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { store.download(exerciseId) }) {
                Text("重试")
            }
        }

        null -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "演示短片未内置，可下载补全",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { store.download(exerciseId) }) {
                Text("下载")
            }
        }
    }
}
