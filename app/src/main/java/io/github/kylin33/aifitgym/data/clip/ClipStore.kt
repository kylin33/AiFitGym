package io.github.kylin33.aifitgym.data.clip

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** 单个动作短片的下载状态；[finished] 的短片直接从 [ClipStore.available] 里看，不在这里留记录。 */
sealed interface ClipDownload {

    /** [total] 为 -1 表示服务端没给 Content-Length，进度只能显示成不确定态。 */
    data class Running(val received: Long, val total: Long) : ClipDownload {
        val fraction: Float
            get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    data class Failed(val message: String) : ClipDownload
}

/**
 * 动作演示短片的「哪来的」与「怎么补」。
 *
 * APK 里只内置主打动作（徒手 + 力量，见 `tools/fetch_clips.py`），
 * 拉伸 / 柔韧 / 瑜伽 / 有氧这些冷门分类不占安装包体积 —— 用户在抽屉里点一下，
 * 当场下到 `noBackupFilesDir/clips/<id>.mp4`，之后与内置片一样循环播放。
 *
 * 为什么放 `noBackupFilesDir` 而不是 `filesDir`：manifest 里 `allowBackup=true`，
 * filesDir 会被系统算进云备份 —— 几十 MB 的演示片同步到 Google 网盘既没意义又费流量，
 * 换手机重下就好。缓存目录（cacheDir）也不能用：系统清缓存会把它删掉，
 * 用户明明「下载过」的片子却突然没了。
 *
 * 下载不落数据库：文件在不在就是唯一状态，省掉一次 Room 迁移，
 * 也避免「数据库说有、文件被删了」这种半截状态。
 */
class ClipStore(context: Context) {

    private val appContext = context.applicationContext

    private val dir = File(appContext.noBackupFilesDir, "clips")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 随 APK 发行的短片 id。assets 里没有目录时会返回空集，不影响后续按需下载。 */
    private val bundled: Set<String> = runCatching {
        appContext.assets.list(BUNDLED_DIR)
            ?.asSequence()
            ?.filter { it.endsWith(".mp4", ignoreCase = true) }
            ?.map { it.substringBeforeLast('.') }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    /** 已下载到本机的短片：id → 字节数，顺带给「我的」页算占用与条数。 */
    private val _local = MutableStateFlow(scanLocal())

    /** 已下载短片（不含内置），id → 字节数。 */
    val local: StateFlow<Map<String, Long>> = _local.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, ClipDownload>>(emptyMap())

    /** 下载中的动作。下完就从这个 map 里消失，状态转移到 [local]。 */
    val downloads: StateFlow<Map<String, ClipDownload>> = _downloads.asStateFlow()

    /** 有片可播的动作：内置 ∪ 已下载。播放组件订阅它，下载完不用重建页面就能出画面。 */
    private val _available = MutableStateFlow(bundled + _local.value.keys)

    val available: StateFlow<Set<String>> = _available.asStateFlow()

    /** 有片就返回播放地址，没片返回 null（调用方退回静态图）。 */
    fun uriFor(exerciseId: String): String? {
        if (exerciseId in bundled) return "asset:///$BUNDLED_DIR/$exerciseId.mp4"
        val file = fileOf(exerciseId)
        return if (file.isFile && file.length() > 0) Uri.fromFile(file).toString() else null
    }

    fun isAvailable(exerciseId: String): Boolean = exerciseId in _available.value

    /** 同一个动作重复点按只会有一次请求。 */
    fun download(exerciseId: String) {
        if (isAvailable(exerciseId)) return
        if (_downloads.value[exerciseId] is ClipDownload.Running) return
        _downloads.update { it + (exerciseId to ClipDownload.Running(0, -1)) }
        scope.launch { fetch(exerciseId) }
    }

    /** 清掉所有「按需下载」的片子；内置的那批删不掉，也不该删。 */
    fun clearDownloaded() {
        scope.launch {
            dir.listFiles()?.forEach { it.delete() }
            _local.value = emptyMap()
            _available.value = bundled
            _downloads.value = emptyMap()
        }
    }

    private fun fileOf(exerciseId: String) = File(dir, "$exerciseId.mp4")

    private fun scanLocal(): Map<String, Long> =
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".mp4") && it.length() > 0 }
            ?.associate { it.nameWithoutExtension to it.length() }
            .orEmpty()

    private fun fetch(exerciseId: String) {
        var lastError = "下载失败"
        for (source in SOURCES) {
            val outcome = runCatching { fetchFrom(source.format(exerciseId), exerciseId) }
            if (outcome.isSuccess) {
                _downloads.update { it - exerciseId }
                _local.value = scanLocal()
                _available.value = bundled + _local.value.keys
                return
            }
            lastError = readableError(outcome.exceptionOrNull())
        }
        Log.w(TAG, "下载 $exerciseId 失败：$lastError")
        _downloads.update { it + (exerciseId to ClipDownload.Failed(lastError)) }
    }

    /**
     * 异常翻成人话。
     *
     * 抽屉里那行提示是直接显示给用户看的，`Unable to resolve host ...`
     * 除了吓人没有任何帮助 —— 用户能做的只有「检查网络再点重试」这一件事。
     */
    private fun readableError(error: Throwable?): String = when (error) {
        is UnknownHostException -> "网络不可用"
        is SocketTimeoutException -> "连接超时"
        is ConnectException -> "连不上服务器"
        else -> error?.message?.takeIf { it.isNotBlank() } ?: "下载失败"
    }

    /**
     * 单个源的一次完整下载。
     *
     * 成败靠 [ClipDownload.Running] 之外的返回值判定 —— 抛异常即失败，由 [fetch] 换源重试。
     * 先写 `.part` 再改名：中断留下半截文件也不会被当成「已下载」，重进抽屉再点一次即可。
     */
    private fun fetchFrom(url: String, exerciseId: String) {
        dir.mkdirs()
        val target = fileOf(exerciseId)
        val tmp = File(dir, "$exerciseId.mp4.part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var received = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        _downloads.update {
                            it + (exerciseId to ClipDownload.Running(received, total))
                        }
                    }
                }
            }
            if (!looksLikeMp4(tmp)) error("文件校验失败")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } finally {
            connection.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }

    /** ftp 类型标识在偏移 4，是判断「拿到的到底是不是 mp4」最省事的办法。 */
    private fun looksLikeMp4(file: File): Boolean {
        if (file.length() < 1024) return false
        return runCatching {
            file.inputStream().use { stream ->
                val head = ByteArray(12)
                if (stream.read(head) < 12) return@use false
                String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "ClipStore"
        const val BUNDLED_DIR = "clips"
        const val USER_AGENT = "FitGym/1.0 (Android)"

        /**
         * 上游仓库同样是动作库的来源（见 NOTICE）。
         *
         * jsDelivr 放前面是因为它对国内网络更友好；raw.githubusercontent 作为兜底，
         * CDN 上没同步到的片子（刚推上去的新动作）只有 raw 有。两个都不通就报错让用户重试。
         */
        val SOURCES = listOf(
            "https://cdn.jsdelivr.net/gh/DuarteSantos8/openGym@main/catalogue/media/clip/%s.mp4",
            "https://raw.githubusercontent.com/DuarteSantos8/openGym/main/catalogue/media/clip/%s.mp4",
        )
    }
}
