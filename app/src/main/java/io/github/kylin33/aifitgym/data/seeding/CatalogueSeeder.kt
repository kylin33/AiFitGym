package io.github.kylin33.aifitgym.data.seeding

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.kylin33.aifitgym.data.catalog.CatalogReader
import io.github.kylin33.aifitgym.data.local.AppMetaEntity
import io.github.kylin33.aifitgym.data.local.FitGymDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** 内置动作库导入的对外状态，UI 靠它决定显示进度页还是列表。 */
sealed interface ImportState {

    /** 还没开始（数据库尚未建出来）。 */
    data object Idle : ImportState

    data class Running(val phase: Phase, val done: Int, val total: Int) : ImportState {
        enum class Phase { PARSING, WRITING }
    }

    /** @param fromCache true 表示库里本来就有数据，没有真的导入。 */
    data class Done(val count: Int, val elapsedMs: Long, val fromCache: Boolean) : ImportState

    data class Failed(val message: String) : ImportState
}

/**
 * 把 `assets/exercises.json` 灌进 Room。
 *
 * 触发点是 [FitGymDatabase.create] 里的 `onCreate` 回调（首启建库时自动跑一次）；
 * 另外也暴露 [importInto] 给「导入失败重试」用。整个流程有互斥锁 + 条数检查，
 * 重复调用不会重复写入。
 */
class CatalogueSeeder(private val context: Context) {

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val mutex = Mutex()

    /**
     * @param force true 时忽略库里已有数据，强制重新导入（调试或数据包更新时用）。
     */
    suspend fun importInto(database: FitGymDatabase, force: Boolean = false): ImportState =
        mutex.withLock {
            val startedAt = SystemClock.elapsedRealtime()
            val dao = database.exerciseDao()

            val existing = dao.count()
            if (!force && existing > 0) {
                // 建库回调和启动兜底会并发进来，后来者别把「真的导入过」的状态覆盖成 fromCache
                val previous = _state.value
                val state = previous as? ImportState.Done
                    ?: ImportState.Done(existing, elapsedMs = 0, fromCache = true)
                _state.value = state
                return@withLock state
            }

            val state = try {
                _state.value = ImportState.Running(ImportState.Running.Phase.PARSING, 0, 0)

                val parseStart = SystemClock.elapsedRealtime()
                val items = CatalogReader.readEntities(
                    context = context,
                    onParsed = { total ->
                        _state.value = ImportState.Running(ImportState.Running.Phase.PARSING, 0, total)
                    },
                    onProgress = { done, total ->
                        _state.value = ImportState.Running(ImportState.Running.Phase.PARSING, done, total)
                    },
                )
                val parseMs = SystemClock.elapsedRealtime() - parseStart

                if (items.isEmpty()) {
                    ImportState.Failed("内置动作库是空的，检查 assets/${CatalogReader.ASSET_FILE}")
                } else {
                    _state.value = ImportState.Running(ImportState.Running.Phase.WRITING, 0, items.size)

                    // 整库替换放在一个事务里：中途被杀进程也不会留下半套数据
                    val writeStart = SystemClock.elapsedRealtime()
                    dao.replaceAll(items)
                    database.appMetaDao().put(
                        AppMetaEntity(KEY_SEED_COUNT, items.size.toString())
                    )
                    database.appMetaDao().put(
                        AppMetaEntity(KEY_SEED_AT, System.currentTimeMillis().toString())
                    )
                    val writeMs = SystemClock.elapsedRealtime() - writeStart

                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    Log.i(
                        TAG,
                        "内置动作库导入完成：${items.size} 条，总耗时 ${elapsed}ms" +
                            "（解析 ${parseMs}ms / 写库 ${writeMs}ms）",
                    )
                    ImportState.Done(items.size, elapsedMs = elapsed, fromCache = false)
                }
            } catch (e: IOException) {
                Log.e(TAG, "读取内置动作库失败", e)
                ImportState.Failed("读取内置数据失败：${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "导入内置动作库失败", e)
                ImportState.Failed("导入失败：${e.message ?: e::class.simpleName}")
            }

            _state.value = state
            state
        }

    companion object {
        private const val TAG = "CatalogueSeeder"
        private const val KEY_SEED_COUNT = "catalogue_seed_count"
        private const val KEY_SEED_AT = "catalogue_seed_at"
    }
}
