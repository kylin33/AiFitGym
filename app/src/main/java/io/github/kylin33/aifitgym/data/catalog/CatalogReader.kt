package io.github.kylin33.aifitgym.data.catalog

import android.content.Context
import io.github.kylin33.aifitgym.data.local.ExerciseEntity
import io.github.kylin33.aifitgym.data.skill.SkillBranch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 读 `assets/exercises.json` 并转成可以直接入库的 [ExerciseEntity]。
 *
 * 解析完全在 IO 线程做；1.8MB / 2300 条的量级下 `decodeFromString` 是几百毫秒，
 * 没必要为了省内存上流式解析，直接 readBytes 换一次干净的字符串更容易排查问题。
 */
object CatalogReader {

    const val ASSET_FILE = "exercises.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    /** 仅供日志/计数使用；真正入库的是 [readEntities] 的返回值。 */
    suspend fun readRaw(context: Context): ExerciseCatalog = withContext(Dispatchers.IO) {
        val text = context.assets.open(ASSET_FILE).buffered().use { it.readBytes().decodeToString() }
        json.decodeFromString(ExerciseCatalog.serializer(), text)
    }

    /**
     * @param onParsed 解析完成，拿到总条数（此时还没开始写库）。
     * @param onProgress 映射进度，用于首启进度条。
     */
    suspend fun readEntities(
        context: Context,
        onParsed: (total: Int) -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ExerciseEntity> = withContext(Dispatchers.IO) {
        val catalog = try {
            val text = context.assets.open(ASSET_FILE).buffered().use { it.readBytes().decodeToString() }
            json.decodeFromString(ExerciseCatalog.serializer(), text)
        } catch (e: IOException) {
            throw IOException("读取 assets/$ASSET_FILE 失败：${e.message}", e)
        }

        val items = catalog.items
        onParsed(items.size)

        ArrayList<ExerciseEntity>(items.size).also { out ->
            items.forEachIndexed { index, dto ->
                out += dto.toEntity()
                if (index % 200 == 0 || index == items.lastIndex) onProgress(index + 1, items.size)
            }
        }
    }
}

/**
 * JSON → Room 实体。
 *
 * 中文名缺失时（上游极少数条目）回落到英文名，保证列表里永远不会出现空白标题。
 */
internal fun ExerciseJson.toEntity(): ExerciseEntity {
    val zhName = name.trim().takeIf { it.isNotEmpty() }
    val enName = nameEn.trim()
    val displayName = zhName ?: enName.ifEmpty { id }

    return ExerciseEntity(
        id = id,
        name = displayName,
        nameEn = enName,
        bodyPart = bodyPart,
        bodyPartCn = bodyPartCn.ifBlank { bodyPart },
        category = category,
        categoryCn = categoryCn.ifBlank { category },
        equipment = equipment,
        equipmentCn = equipmentCn.ifBlank { equipment },
        target = target,
        targetCn = targetCn.ifBlank { target },
        secondaryMuscles = secondaryMuscles.map { it.trim() }.filter { it.isNotEmpty() },
        description = description,
        steps = steps.map { it.trim() }.filter { it.isNotEmpty() },
        stepsInChinese = stepsInChinese,
        image = image?.trim()?.takeIf { it.isNotEmpty() && it != "null" },
        searchKey = buildSearchKey(displayName, enName, bodyPartCn, targetCn, categoryCn, secondaryMuscles),
        branch = branch.ifBlank { SkillBranch.fallbackOf(bodyPart).key },
        skillLevel = skillLevel.coerceIn(0, 8),
        unlockType = unlockType,
        unlockSets = unlockSets.coerceAtLeast(1),
        unlockValue = unlockValue.coerceAtLeast(0),
        unlockText = unlockText,
        isBodyweightFriendly = isBodyweightFriendly,
    )
}

/** 中英名 + 部位 + 目标肌群 + 分类，全部小写拼成一列，配合 LIKE 做一次扫描就够。 */
private fun buildSearchKey(
    name: String,
    nameEn: String,
    bodyPartCn: String,
    targetCn: String,
    categoryCn: String,
    secondary: List<String>,
): String = buildString {
    append(name).append(' ')
    append(nameEn).append(' ')
    append(bodyPartCn).append(' ')
    append(targetCn).append(' ')
    append(categoryCn)
    secondary.forEach { append(' ').append(it) }
}.lowercase()
