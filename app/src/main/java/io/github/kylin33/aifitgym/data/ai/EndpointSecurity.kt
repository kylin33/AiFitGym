package io.github.kylin33.aifitgym.data.ai

/**
 * 端点地址的传输安全性。
 *
 * `network_security_config.xml` 里为了照顾局域网 Ollama 已经全局放开了明文 HTTP，
 * 这一步是那个决定的代价：**App 不再拦截明文，改由界面向用户讲清楚风险**。
 * 放行本身是正确的（用户填的地址不可预知），但不提示就等于默认用户知道
 * 「http 意味着同网络的人能看见你的 Key 和聊天内容」—— 多数人并不知道。
 *
 * 三级而不是两级，是因为这两种情况的处置完全不同：
 *  · 局域网 http：自己电脑上跑的 Ollama，本来就没有 Key，也不出家门，
 *    提示一句就够，把语气做成红色警告只会把人吓跑；
 *  · 公网 http：Key 和训练数据要穿过运营商和一堆中间设备，
 *    这时候必须让人停下来看一眼。
 */
enum class EndpointSecurity {
    /** 没填，或者用了 https。不需要提示。 */
    Secure,

    /** http + 本机/局域网地址：可以接受，但要说清楚「同一 Wi-Fi 下的设备能看见」。 */
    LocalCleartext,

    /** http + 公网地址：Key 与对话内容明文过网，需要强警告。 */
    PublicCleartext,
}

/** 便捷入口，设置页直接拿 endpoint 问。 */
fun AiEndpoint.security(): EndpointSecurity = classifyEndpoint(baseUrl)

/**
 * 判断一个 Base URL 的明文风险。
 *
 * 只解析 scheme 和 host，不追求完整的 URL 规范：这里的目标是「别漏报、别误报」，
 * 不是校验地址合法性 —— 地址写错时请求阶段会给出更准确的错误，不需要在这里重复。
 *
 * 没写 scheme 的地址（`api.deepseek.com/v1`）当成安全：请求会直接失败，
 * 而不是悄悄地走明文。
 */
fun classifyEndpoint(rawUrl: String): EndpointSecurity {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return EndpointSecurity.Secure

    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://")) return EndpointSecurity.Secure

    // 已经确认过前缀是 http://（大小写不敏感），这里直接按长度切，避免大小写不一致切不掉
    val host = extractHost(trimmed.substring("http://".length))
    if (host.isEmpty()) return EndpointSecurity.Secure

    return if (isLocalHost(host)) EndpointSecurity.LocalCleartext else EndpointSecurity.PublicCleartext
}

/**
 * 从 scheme 之后的部分取出 host。
 *
 * 要处理 `user:pass@host:port/path` 和 IPv6 的 `[::1]:8080` 两种形态 ——
 * 中转站地址里带路径很常见，带认证信息的也不罕见。
 */
private fun extractHost(afterScheme: String): String {
    val authority = afterScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')

    return if (authority.startsWith("[")) {
        // IPv6 字面量：[::1]:11434
        val end = authority.indexOf(']')
        if (end <= 1) "" else authority.substring(1, end).lowercase()
    } else {
        authority.substringBefore(':').lowercase()
    }
}

/**
 * 本机 / 局域网判定。
 *
 * 除了常规私有网段，额外认了几类实际会遇到的：
 *  · `169.254.x`：手机连电脑热点但没拿到 DHCP 时的自分配地址，Ollama 教程里常出现；
 *  · `100.64/10`：运营商级 NAT，也是 Tailscale 默认分的网段，远程连家里电脑全靠它；
 *  · `.local` / `.lan`：mDNS 主机名，比记 IP 友好，很多路由器直接给。
 *
 * 判定为「局域网」不代表没有风险，只是风险范围被限制在一个物理网络内，
 * 所以用提示而不是警告。
 */
private fun isLocalHost(host: String): Boolean {
    when (host) {
        "localhost", "127.0.0.1", "0.0.0.0", "::1", "::", "0:0:0:0:0:0:0:1" -> return true
    }

    if (host.endsWith(".local") || host.endsWith(".lan") ||
        host.endsWith(".localdomain") || host.endsWith(".home.arpa") ||
        host.endsWith(".internal")
    ) {
        return true
    }

    // IPv6 唯一本地地址 fc00::/7 与链路本地 fe80::/10
    if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")) {
        // 只有真的是 IPv6 字面量（含冒号）才按地址前缀判断，
        // 否则 fdic.com 这类域名会被误判成局域网
        if (host.contains(':')) return true
    }

    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size != 4) return false
    if (octets.any { it == null || it < 0 || it > 255 }) return false

    val a = octets[0]!!
    val b = octets[1]!!

    return when {
        a == 0 || a == 127 -> true          // 本机
        a == 10 -> true                     // 10/8
        a == 192 && b == 168 -> true        // 192.168/16
        a == 172 && b in 16..31 -> true     // 172.16/12
        a == 169 && b == 254 -> true        // 链路本地
        a == 100 && b in 64..127 -> true    // CGNAT / Tailscale
        else -> false
    }
}
