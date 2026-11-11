package io.github.kylin33.aifitgym.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import io.github.kylin33.aifitgym.data.anatomy.MuscleTaxonomy
import io.github.kylin33.aifitgym.data.anatomy.MuscleTaxonomyAssets
import kotlin.math.floor

/**
 * 人体肌肉热力图（连续强度版）。
 *
 * 无状态：只吃 `slug → 强度(0f…1f)`，自己负责画。动作详情、计划卡片、统计页共用这一个组件：
 *
 * ```kotlin
 * // 单个动作：主肌群 1.0 / 副肌群 0.4
 * val scores = MuscleScoring.forExercise(taxonomy, exercise)
 * MuscleHeatmap(scores, Modifier.fillMaxWidth().height(240.dp))
 *
 * // 整份计划：按组数累加后归一化
 * MuscleHeatmap(MuscleScoring.forPlan(taxonomy, entries), Modifier.height(260.dp))
 * ```
 *
 * 配色沿用 openGym 的色阶（base → l1 32% → l2 56% → l3 78% → l4 100% accent），
 * 只是把四档离散值改成在档位之间线性插值，这样 0.4 这种中间强度也有对应的浅色。
 */
@Composable
fun MuscleHeatmap(
    muscleScores: Map<String, Float>,
    modifier: Modifier = Modifier,
    sex: BodySex = BodySex.Male,
    accent: Color = MaterialTheme.colorScheme.primary,
    gap: Int = 6,
) {
    val base = muscleBaseColor()
    val silhouette = muscleSilhouetteColor()

    val colors = remember(muscleScores, base, accent) {
        muscleScores.mapValues { (_, score) -> muscleColorForScore(score, base, accent) }
    }

    BodyPair(
        muscleColors = colors,
        fallback = base,
        silhouette = silhouette,
        modifier = modifier,
        sex = sex,
        gap = gap,
    )
}

/**
 * 强度 → 颜色。
 *
 * 档位是 0 / 0.25 / 0.5 / 0.75 / 1.0（对应 ramp 的 0 / .32 / .56 / .78 / 1.0），
 * 中间值按相邻档位插值，所以 0.4 落在 l1 和 l2 之间，是明显的浅色而不是中深色。
 */
internal fun muscleColorForScore(score: Float, base: Color, accent: Color): Color {
    val value = score.coerceIn(0f, 1f)
    if (value <= 0f) return base

    val ramp = muscleRamp()
    val position = value * (ramp.size - 1)
    val low = floor(position).toInt().coerceIn(0, ramp.size - 2)
    val fraction = position - low
    val mix = ramp[low] + (ramp[low + 1] - ramp[low]) * fraction
    return lerp(base, accent, mix)
}

/**
 * 读一次肌群词表（assets/anatomy/muscles.json），进程内缓存。
 *
 * 调用方在拿到非 null 之前可以先画空状态，不要阻塞主线程：这里走的是 produceState。
 */
@Composable
fun rememberMuscleTaxonomy(): MuscleTaxonomy? {
    val context = LocalContext.current
    val state by produceState<MuscleTaxonomy?>(initialValue = null) {
        value = MuscleTaxonomyAssets.load(context)
    }
    return state
}
