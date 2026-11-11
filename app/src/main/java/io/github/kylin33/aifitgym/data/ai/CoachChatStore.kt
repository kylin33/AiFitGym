package io.github.kylin33.aifitgym.data.ai

import io.github.kylin33.aifitgym.data.local.AppMetaDao
import io.github.kylin33.aifitgym.data.local.AppMetaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 一条聊天记录。
 *
 * [raw] 只在 assistant 消息上有值，存的是**含 json 代码块的原始回复** ——
 * 回填历史时用它重新解析计划，也把它原样发回给模型，让模型看到自己上一轮是怎么写的，
 * 格式一致性比省那几十个 token 值钱。
 */
@Serializable
data class StoredChatMessage(
    val role: String,
    val text: String,
    val raw: String? = null,
)

/**
 * 对话落盘。
 *
 * 没有为它加一张 Room 表：这是「一段可以随时丢掉的会话」，不是用户资产，
 * 进数据库只会让 schema 多一次迁移，且备份时还得决定要不要带走它。
 * 放在 `app_meta` 这个现成的 KV 表里，清空数据时连同其它记录一起没了，正合适。
 */
class CoachChatStore(private val appMetaDao: AppMetaDao) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): List<StoredChatMessage> {
        val raw = runCatching { appMetaDao.get(KEY) }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(SERIALIZER, raw) }
            .getOrDefault(emptyList())
    }

    suspend fun save(messages: List<StoredChatMessage>) {
        val trimmed = messages.takeLast(MAX_STORED)
        if (trimmed.isEmpty()) {
            runCatching { appMetaDao.remove(KEY) }
            return
        }
        val encoded = runCatching { json.encodeToString(SERIALIZER, trimmed) }.getOrNull() ?: return
        runCatching { appMetaDao.put(AppMetaEntity(KEY, encoded)) }
    }

    suspend fun clear() {
        runCatching { appMetaDao.remove(KEY) }
    }

    private companion object {
        /** 30 条 ≈ 15 轮，够回看，也不至于把一条 KV 写到几百 KB。 */
        const val MAX_STORED = 30
        const val KEY = "ai_coach_chat"
        val SERIALIZER = ListSerializer(StoredChatMessage.serializer())
    }
}
