package io.github.kylin33.aifitgym.data.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * 配置读写。
 *
 * 分两处存是有意的：
 *  - 端点、模型、训练目标 → 普通 SharedPreferences（明文无所谓，用户自己填的）；
 *  - API Key → [AiSecretStore]（Keystore 加密），并且**只在内存里缓存一次**，
 *    不做任何日志输出、不写进备份文件、不出现在错误提示里。
 *
 * Key 用内存缓存而不是每次读都解密：解密要走 Keystore，几十毫秒，聊天时每次请求都做不划算；
 * 缓存的生命周期就是进程，进程没了下次解密一次即可。
 */
class AiSettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var cachedKey: String? = AiSecretStore.read(appContext)

    private val _state = MutableStateFlow(
        AiSettingsState(
            preferences = readPreferences(),
            hasKey = cachedKey != null,
        ),
    )
    val state: StateFlow<AiSettingsState> = _state.asStateFlow()

    val apiKey: String? get() = cachedKey

    // ------------------------------------------------------------ 写入

    fun saveEndpoint(endpoint: AiEndpoint) {
        update { it.copy(endpoint = endpoint) }
    }

    fun saveProfile(profile: AiProfile) {
        update { it.copy(profile = profile) }
    }

    /** 空串表示清除。 */
    fun saveApiKey(key: String?) {
        val trimmed = key?.trim().orEmpty()
        AiSecretStore.save(appContext, trimmed)
        cachedKey = trimmed.ifEmpty { null }
        _state.value = _state.value.copy(hasKey = cachedKey != null)
    }

    /** 用户在输入框里没动 Key（留空）时，保留原来那把。 */
    fun hasStoredKey(): Boolean = cachedKey != null

    // ------------------------------------------------------------ 内部

    private fun update(transform: (AiPreferences) -> AiPreferences) {
        val next = transform(_state.value.preferences)
        prefs.edit().putString(KEY_PREFERENCES, json.encodeToString(AiPreferences.serializer(), next)).apply()
        _state.value = _state.value.copy(preferences = next)
    }

    private fun readPreferences(): AiPreferences {
        val raw = prefs.getString(KEY_PREFERENCES, null) ?: return AiPreferences()
        return runCatching { json.decodeFromString(AiPreferences.serializer(), raw) }
            .getOrElse { AiPreferences() }
    }

    private companion object {
        const val PREFS = "ai_prefs"
        const val KEY_PREFERENCES = "preferences"
    }
}
