package io.github.kylin33.aifitgym.data.anatomy

import android.content.Context
import org.json.JSONObject

/**
 * 肌群词表：把动作库里的各种写法翻译成人体图上的 slug。
 *
 * 动作库里同一个肌群有四种写法，全都要能认：
 *  - target 是英文枚举（`pectorals` / `lats` / `spine`）
 *  - secondaryMuscles 是中文（`胸肌` / `背阔肌` / `下背`）
 *  - 有些已经是 slug 本身（`chest`）
 *  - 还有零星的英文残留（`wrists`）
 *
 * 数据来自 `assets/anatomy/muscles.json`（由 tools/extract_anatomy.py 从 openGym 前端提取，
 * 中文别名表 `aliasCn` 是对着 2303 个动作的实际取值补的）。
 */
data class MuscleInfo(
    val slug: String,
    val nameEn: String,
    val nameCn: String,
    val halfLifeHours: Float,
)

class MuscleTaxonomy internal constructor(
    val muscles: List<MuscleInfo>,
    private val drawable: Set<String>,
    private val enAlias: Map<String, String?>,
    private val cnAlias: Map<String, String?>,
    private val bodyPartFallback: Map<String, Map<String, Float>>,
    /** 副肌群权重，上游给的是 0.4。 */
    val secondaryWeight: Float,
) {

    private val bySlug: Map<String, MuscleInfo> = muscles.associateBy { it.slug }

    /**
     * 任意写法 → 可绘制的 slug。画不出来的（踝关节、心肺系统、足部…）返回 null，
     * 调用方直接跳过就行，不用特殊处理。
     */
    fun slugOf(token: String?): String? {
        val raw = token?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val lowered = raw.lowercase()
        val resolved = when {
            enAlias.containsKey(lowered) -> enAlias[lowered]
            cnAlias.containsKey(raw) -> cnAlias[raw]
            drawable.contains(raw) -> raw
            else -> null
        }
        // 别名可能指向不可绘制的名字（cardiovascular system），这里统一兜住
        return resolved?.takeIf { drawable.contains(it) }
    }

    fun nameCn(slug: String): String = bySlug[slug]?.nameCn ?: slug

    fun nameEn(slug: String): String = bySlug[slug]?.nameEn ?: slug

    /** 动作没给出可用肌群时，用身体部位回退（如 upper legs → 股四头肌 0.4 / 腘绳肌 0.35 / 臀肌 0.25）。 */
    fun bodyPartWeights(bodyPart: String): Map<String, Float> =
        bodyPartFallback[bodyPart].orEmpty().filterKeys { drawable.contains(it) }

    /** 回退链的最后一档：实在没有就直接铺满全身，避免热力图全灰。 */
    fun fullBodyWeights(): Map<String, Float> =
        bodyPartFallback["full body"].orEmpty().filterKeys { drawable.contains(it) }
}

object MuscleTaxonomyAssets {

    const val ASSET_FILE = "anatomy/muscles.json"

    @Volatile
    private var cache: MuscleTaxonomy? = null

    suspend fun load(context: Context): MuscleTaxonomy? {
        cache?.let { return it }
        return runCatching {
            val raw = context.assets.open(ASSET_FILE).use { it.readBytes() }
            val root = JSONObject(String(raw, Charsets.UTF_8))

            val muscles = buildList {
                val array = root.getJSONArray("muscles")
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        MuscleInfo(
                            slug = item.getString("slug"),
                            nameEn = item.optString("nameEn"),
                            nameCn = item.optString("nameCn"),
                            halfLifeHours = item.optDouble("halfLifeHours", 30.0).toFloat(),
                        ),
                    )
                }
            }

            MuscleTaxonomy(
                muscles = muscles,
                drawable = muscles.mapTo(HashSet()) { it.slug },
                enAlias = root.optJSONObject("alias").toStringMap(),
                cnAlias = root.optJSONObject("aliasCn").toStringMap(),
                bodyPartFallback = root.optJSONObject("bodyPartFallback").toWeightMap(),
                secondaryWeight = root.optDouble("secondaryWeight", 0.4).toFloat(),
            ).also { cache = it }
        }.onFailure {
            android.util.Log.w("MuscleTaxonomy", "肌群词表读取失败（$ASSET_FILE）", it)
        }.getOrNull()
    }

    private fun JSONObject?.toStringMap(): Map<String, String?> {
        val source = this ?: return emptyMap()
        return buildMap {
            source.keys().forEach { key ->
                val value = source.opt(key)
                put(key, if (value == null || value == JSONObject.NULL) null else value.toString())
            }
        }
    }

    private fun JSONObject?.toWeightMap(): Map<String, Map<String, Float>> {
        val source = this ?: return emptyMap()
        return buildMap {
            source.keys().forEach { group ->
                val weights = source.optJSONObject(group) ?: return@forEach
                put(
                    group,
                    buildMap {
                        weights.keys().forEach { slug ->
                            put(slug, weights.optDouble(slug, 0.0).toFloat())
                        }
                    },
                )
            }
        }
    }
}
