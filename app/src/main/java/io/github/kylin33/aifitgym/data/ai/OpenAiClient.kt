package io.github.kylin33.aifitgym.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * OpenAI 兼容网关的最小客户端。
 *
 * 只依赖 `HttpURLConnection`，没有引入 OkHttp：
 * 请求体是一段 JSON、响应是 `text/event-stream` 或一段 JSON，
 * 这两种用 JDK 自带的连接 + `readLine()` 就够，多一个库只是多一套要跟着升级的东西。
 *
 * 兼容性处理（这些坑都是中转站逼出来的）：
 *  - 用户勾了 `stream: true`，但有些服务端仍返回一整段 JSON —— 所以不按 Content-Type 分支，
 *    而是按「第一行是不是以 `data:` 开头」现场判断，两种都能吃；
 *  - SSE 里的空行、`event:` 行、`:` 注释行一律跳过；
 *  - `delta.content` 可能为 null（首帧只带 role，或 DeepSeek-R1 只给 `reasoning_content`）。
 */
class OpenAiClient {

    data class ChatMessage(val role: String, val content: String)

    /** 带 HTTP 状态码的失败，[message] 已经是能直接给用户看的中文。 */
    class AiHttpException(val status: Int, message: String) : Exception(message)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 流式对话。每收到一段增量就回调一次 [onDelta]（**在 IO 线程**，调用方自己切主线程），
     * 返回拼接后的完整回复。
     */
    suspend fun streamChat(
        endpoint: AiEndpoint,
        apiKey: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.6,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", endpoint.model)
            put("stream", true)
            put("temperature", temperature)
            put("messages", messages.toJson())
        }.toString()

        val connection = open(endpoint, apiKey)
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status !in 200..299) throw failure(status, readError(connection))

            val accumulated = StringBuilder()
            val rawBody = StringBuilder()
            var seenSse = false

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    when {
                        trimmed.isEmpty() -> Unit
                        trimmed.startsWith(":") -> Unit
                        trimmed.startsWith("event:") -> Unit
                        trimmed.startsWith("data:") -> {
                            seenSse = true
                            val payload = trimmed.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            val delta = deltaOf(payload)
                            if (delta.isNotEmpty()) {
                                accumulated.append(delta)
                                onDelta(delta)
                            }
                        }
                        // 还没遇到过 SSE 帧 → 这可能是整段 JSON，先攒着
                        !seenSse -> rawBody.append(trimmed)
                    }
                }
            }

            if (seenSse) {
                if (accumulated.isEmpty()) {
                    throw AiHttpException(status, "服务端没有返回内容，可能是余额不足或触发了内容审核")
                }
                accumulated.toString()
            } else {
                wholeContent(rawBody.toString())
                    ?: throw AiHttpException(status, "看不懂服务端的返回格式，确认地址是 OpenAI 兼容接口（…/v1）")
            }
        } catch (e: IOException) {
            throw AiHttpException(0, networkMessage(e))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * 连通性测试：让它回一个词就行，不烧 token。
     *
     * 用非流式，因为设置页只需要「通/不通」和一句人话，不需要打字机效果。
     */
    suspend fun ping(endpoint: AiEndpoint, apiKey: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", endpoint.model)
            put("stream", false)
            put("max_tokens", 8)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "只回复两个字：收到")
                })
            })
        }.toString()

        val connection = open(endpoint, apiKey)
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw failure(status, readError(connection))
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            wholeContent(text) ?: "连接成功，但没解析出回复"
        } catch (e: IOException) {
            throw AiHttpException(0, networkMessage(e))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ------------------------------------------------------------ 内部

    private fun open(endpoint: AiEndpoint, apiKey: String): HttpURLConnection {
        val url = endpoint.chatUrl()
        if (url.isBlank()) throw AiHttpException(0, "还没填 Base URL，先去设置里配一下")
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw AiHttpException(0, "Base URL 格式不对：$url")
        }
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        // 流式回复在思考模型上可能几十秒才出第一个字，读超时给足
        connection.readTimeout = 180_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "text/event-stream, application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("User-Agent", "FitGym/1.0 (Android)")
        return connection
    }

    private fun List<ChatMessage>.toJson(): JsonArray = buildJsonArray {
        forEach { message ->
            add(buildJsonObject {
                put("role", message.role)
                put("content", message.content)
            })
        }
    }

    /** 从一帧 SSE 里抠出 `choices[0].delta.content`。 */
    private fun deltaOf(payload: String): String = runCatching {
        val root = json.parseToJsonElement(payload).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull() as? JsonObject ?: return ""
        val delta = choice["delta"] as? JsonObject
        if (delta != null) {
            delta["content"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        } else {
            // 少数中转站把非流式结构塞进 SSE 帧里
            (choice["message"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        }
    }.getOrDefault("")

    /** 从一整段非流式 JSON 里取 `choices[0].message.content`。 */
    private fun wholeContent(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull() as? JsonObject ?: return@runCatching null
        (choice["message"] as? JsonObject)?.get("content")?.jsonPrimitive?.contentOrNullSafe()
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    /** 服务端给的错误体一般是 `{"error":{"message":"..."}}`，也可能带 `code`。 */
    private fun readError(connection: HttpURLConnection): String =
        runCatching {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        }.getOrDefault("")

    private fun failure(status: Int, rawBody: String): AiHttpException {
        val detail = runCatching {
            val root = json.parseToJsonElement(rawBody).jsonObject
            (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNullSafe()
                ?: root["message"]?.jsonPrimitive?.contentOrNullSafe()
        }.getOrNull().orEmpty().take(160)

        val friendly = when (status) {
            400 -> "请求被拒绝：$detail"
            401, 403 -> "API Key 无效或没有权限（$status）。检查 Key 有没有粘全、有没有选错服务商"
            404 -> "找不到接口（404）。Base URL 多半少了 /v1，或者中转站给的不是这个路径"
            413 -> "对话太长了，点右上角开个新会话"
            429 -> "请求太频繁或额度用完了（429）"
            500, 502, 503, 504 -> "服务商那边出错了（$status），等会儿再试"
            else -> "请求失败（$status）${if (detail.isNotEmpty()) "：$detail" else ""}"
        }
        return AiHttpException(status, friendly)
    }

    private fun networkMessage(e: IOException): String = when (e) {
        is UnknownHostException -> "域名解析失败，检查 Base URL 拼写和网络"
        is SocketTimeoutException -> "连接超时。本地 Ollama 的话确认手机和电脑在同一个 WiFi"
        is SSLException -> "HTTPS 证书校验失败，可能是代理或抓包工具改了证书"
        else -> "网络请求失败：${e.message ?: e::class.java.simpleName}"
    }
}
