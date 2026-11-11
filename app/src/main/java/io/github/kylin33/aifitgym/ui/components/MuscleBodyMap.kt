package io.github.kylin33.aifitgym.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.min

/**
 * 人体肌肉图的几何加载与绘制内核。
 *
 * 几何来自 assets/anatomy/body-paths.json（提取自 openGym frontend/src/lib/body-paths.js，
 * 原始图形是 MuscleMap by Melih Colpan，MIT）。这里不做 VectorDrawable 转换，而是直接在
 * Canvas 上用 PathParser 把 SVG path data 变成 Compose 的 Path，逐块上色：
 * VectorDrawable 的 tint 只能整体着色，改不到单块肌肉。
 *
 * 对外有两个入口：
 *  - [MuscleHeatmap] 强度 0f…1f 的连续配色，主入口（动作预览 / 计划预览 / 统计页）
 *  - [MuscleBodyMap] 档位 0…4 的离散配色 + 恢复期红橙黄，留给打卡恢复视图
 */

/** 一个视图（正面或背面）：viewBox + 每块肌肉的 path data。 */
data class MuscleView(
    val viewBoxX: Float,
    val viewBoxY: Float,
    val viewBoxWidth: Float,
    val viewBoxHeight: Float,
    val paths: Map<String, List<String>>,
)

data class Anatomy(
    val muscles: List<String>,
    val inert: List<String>,
    val views: Map<String, MuscleView>,
)

object AnatomyAssets {

    const val ASSET_FILE = "anatomy/body-paths.json"

    @Volatile
    private var cache: Anatomy? = null

    suspend fun load(context: Context): Anatomy? {
        cache?.let { return it }
        return runCatching {
            val raw = context.assets.open(ASSET_FILE).use { it.readBytes() }
            val root = JSONObject(String(raw, Charsets.UTF_8))
            val muscles = root.getJSONArray("muscles").toStringList()
            val inert = root.getJSONArray("inert").toStringList()

            val viewsJson = root.getJSONObject("views")
            val views = buildMap {
                viewsJson.keys().forEach { sex ->
                    val sides = viewsJson.getJSONObject(sex)
                    sides.keys().forEach { side ->
                        val view = sides.getJSONObject(side)
                        val box = view.getString("viewBox").trim().split(" ")
                        val pathsJson = view.getJSONObject("paths")
                        val paths = buildMap {
                            pathsJson.keys().forEach { slug ->
                                put(slug, pathsJson.getJSONArray(slug).toStringList())
                            }
                        }
                        put(
                            "$sex/$side",
                            MuscleView(
                                viewBoxX = box.getOrNull(0)?.toFloatOrNull() ?: 0f,
                                viewBoxY = box.getOrNull(1)?.toFloatOrNull() ?: 0f,
                                viewBoxWidth = box.getOrNull(2)?.toFloatOrNull() ?: 1f,
                                viewBoxHeight = box.getOrNull(3)?.toFloatOrNull() ?: 1f,
                                paths = paths,
                            ),
                        )
                    }
                }
            }
            Anatomy(muscles, inert, views).also { cache = it }
        }.onFailure {
            // 资源缺失/JSON 坏了的话界面只会是一片空白，留条日志方便定位
            Log.w("AnatomyAssets", "人体图资源读取失败（$ASSET_FILE）", it)
        }.getOrNull()
    }

    private fun org.json.JSONArray.toStringList(): List<String> =
        buildList(length()) { for (i in 0 until length()) add(optString(i)) }
}

enum class BodySex(val key: String) { Male("male"), Female("female") }

/** 训练量色阶 0…4，和 assets/anatomy/muscles.json 的 ramp 一致。 */
fun muscleRamp(): FloatArray = floatArrayOf(0f, 0.32f, 0.56f, 0.78f, 1f)

/** 未训练底色：label 11% 叠在 surface 上（照抄上游 .bm-m 的 default）。 */
@Composable
internal fun muscleBaseColor(): Color =
    lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, 0.11f)

/** 躯干轮廓色：label 18%。 */
@Composable
internal fun muscleSilhouetteColor(): Color =
    lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, 0.18f)

/**
 * 正 + 反两个人形并排，颜色由调用方按 slug 给。
 *
 * @param muscleColors slug → 颜色；没给的肌肉用 [fallback]
 */
@Composable
internal fun BodyPair(
    muscleColors: Map<String, Color>,
    fallback: Color,
    silhouette: Color,
    modifier: Modifier = Modifier,
    sex: BodySex = BodySex.Male,
    gap: Int = 6,
) {
    val context = LocalContext.current
    val anatomy by produceState<Anatomy?>(null) {
        value = AnatomyAssets.load(context)
    }

    val body = anatomy ?: return
    BoxWithConstraints(modifier) {
        // 调用方给了高度就撑满；只给宽度时按两个人形并排的比例自己算高度。
        // 不这么做的话 Row 里的 Canvas 会量成 0 高度，界面上就是一块空白。
        val rowModifier = if (constraints.hasBoundedHeight) {
            Modifier.fillMaxSize()
        } else {
            Modifier.fillMaxWidth().aspectRatio(COMBINED_FIGURE_ASPECT)
        }
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
            BodyCanvas(body.views["${sex.key}/front"], body, muscleColors, fallback, silhouette)
            BodyCanvas(body.views["${sex.key}/back"], body, muscleColors, fallback, silhouette)
        }
    }
}

/** 两个 viewBox 727×1280 并排、中间留 6 的间距：approx (727×2+6)/1280。 */
private const val COMBINED_FIGURE_ASPECT = 1.14f

@Composable
private fun RowScope.BodyCanvas(
    view: MuscleView?,
    anatomy: Anatomy,
    muscleColors: Map<String, Color>,
    fallback: Color,
    silhouette: Color,
) {
    // fillMaxHeight 不能省：Canvas 内部是 Spacer，只有约束为「固定」尺寸才撑得开，
    // 而 Row 传给子项的交叉轴是 minHeight = 0，光给 weight 会量出 0 高度（画不出东西）。
    if (view == null) {
        Canvas(Modifier.weight(1f).fillMaxHeight()) {}
        return
    }
    val shapes = remember(view, muscleColors, fallback, silhouette) {
        buildShapes(view, anatomy, muscleColors, fallback, silhouette)
    }
    Canvas(Modifier.weight(1f).fillMaxHeight()) {
        drawMuscleShapes(view, shapes)
    }
}

private data class MuscleShape(val path: Path, val color: Color)

private fun buildShapes(
    view: MuscleView,
    anatomy: Anatomy,
    muscleColors: Map<String, Color>,
    fallback: Color,
    silhouette: Color,
): List<MuscleShape> {
    val result = ArrayList<MuscleShape>()

    // 先画头/手/脚这些不可着色的部件当剪影，再叠肌肉，保证边界干净
    anatomy.inert.forEach { slug ->
        view.paths[slug]?.forEach { d ->
            result += MuscleShape(PathParser().parsePathString(d).toPath(), silhouette)
        }
    }
    anatomy.muscles.forEach { slug ->
        val color = muscleColors[slug] ?: fallback
        view.paths[slug]?.forEach { d ->
            result += MuscleShape(PathParser().parsePathString(d).toPath(), color)
        }
    }
    return result
}

/** viewBox → 画布：等比缩放并居中。 */
private fun DrawScope.drawMuscleShapes(view: MuscleView, shapes: List<MuscleShape>) {
    if (shapes.isEmpty()) return
    val scale = min(size.width / view.viewBoxWidth, size.height / view.viewBoxHeight)
    val dx = (size.width - view.viewBoxWidth * scale) / 2f
    val dy = (size.height - view.viewBoxHeight * scale) / 2f

    withTransform({
        translate(dx - view.viewBoxX * scale, dy - view.viewBoxY * scale)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        shapes.forEach { drawPath(it.path, it.color) }
    }
}

/**
 * 档位版（0…4）。恢复期视图用它：`fatigue = true` 时换成 黄 → 橙 → 红。
 *
 * @param levels slug → 档位 0…4
 */
@Composable
fun MuscleBodyMap(
    levels: Map<String, Int>,
    modifier: Modifier = Modifier,
    sex: BodySex = BodySex.Male,
    accent: Color = MaterialTheme.colorScheme.primary,
    fatigue: Boolean = false,
    gap: Int = 6,
) {
    val base = muscleBaseColor()
    val silhouette = muscleSilhouetteColor()
    val ramp = muscleRamp()

    // 恢复期用系统色：1 档黄 32%、2 档黄、3 档橙、4 档红
    val fatigueColors = listOf(
        base,
        lerp(base, FatigueYellow, 0.32f),
        FatigueYellow,
        FatigueOrange,
        FatigueRed,
    )

    val colors = remember(levels, base, accent, fatigue) {
        buildMap {
            levels.forEach { (slug, raw) ->
                val level = raw.coerceIn(0, 4)
                put(
                    slug,
                    if (fatigue) fatigueColors[level] else lerp(base, accent, ramp[level]),
                )
            }
        }
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

/** 上游 index.css 的系统色，只在恢复期视图里用（恢复看板的图例也复用这几个）。 */
internal val FatigueYellow = Color(0xFFFFD60A)
internal val FatigueOrange = Color(0xFFFF9F0A)
internal val FatigueRed = Color(0xFFFF453A)
