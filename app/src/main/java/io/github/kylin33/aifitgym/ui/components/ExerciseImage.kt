package io.github.kylin33.aifitgym.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage

/**
 * 动作演示图。
 *
 * 图片全在 `assets/gifs/<id>.webp`，Coil 可以直接吃 `file:///android_asset/...`，
 * 自带磁盘/内存缓存和按尺寸降采样，列表滑动时不用自己写 bitmap 缓存。
 */
@Composable
fun ExerciseImage(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    // 同一张图在列表里会反复进出屏幕，model 用 remember 缓存住，避免每帧重建请求
    val model = remember(path) {
        path?.takeIf { it.isNotBlank() }?.let { "file:///android_asset/$it" }
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
