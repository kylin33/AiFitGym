package io.github.kylin33.aifitgym.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 明文判定是「要不要吓到用户」的开关，误报（把 https 报成不安全）
 * 和漏报（把公网 http 当成局域网）都会让这个提示失去意义，所以把
 * 实际会遇到的地址形态都钉一遍。
 */
class EndpointSecurityTest {

    private fun check(url: String) = classifyEndpoint(url)

    @Test
    fun `https 与非 http 地址都不提示`() {
        assertEquals(EndpointSecurity.Secure, check(""))
        assertEquals(EndpointSecurity.Secure, check("   "))
        assertEquals(EndpointSecurity.Secure, check("https://api.deepseek.com/v1"))
        // 没写 scheme：请求阶段会直接失败，不会悄悄走明文
        assertEquals(EndpointSecurity.Secure, check("api.deepseek.com/v1"))
        assertEquals(EndpointSecurity.Secure, check("http://"))
        assertEquals(EndpointSecurity.Secure, check("http:///v1"))
    }

    @Test
    fun `本机与私有网段算局域网`() {
        assertEquals(EndpointSecurity.LocalCleartext, check("http://localhost:11434/v1"))
        assertEquals(EndpointSecurity.LocalCleartext, check("http://127.0.0.1:11434/v1"))
        assertEquals(EndpointSecurity.LocalCleartext, check("http://0.0.0.0:8080"))
        assertEquals(EndpointSecurity.LocalCleartext, check("http://192.168.1.8:11434/v1"))
        assertEquals(EndpointSecurity.LocalCleartext, check("http://10.0.0.5/v1"))
        assertEquals(EndpointSecurity.LocalCleartext, check("http://172.16.3.4:8080"))
        assertEquals(EndpointSecurity.LocalCleartext, check("http://172.31.255.254"))
    }

    @Test
    fun `不容易想到但实际会遇到的局域网形态`() {
        // 手机连热点没拿到 DHCP 时常见的自分配地址
        assertEquals(EndpointSecurity.LocalCleartext, check("http://169.254.10.10:11434"))
        // Tailscale 默认网段
        assertEquals(EndpointSecurity.LocalCleartext, check("http://100.101.102.103:11434/v1"))
        // mDNS 主机名
        assertEquals(EndpointSecurity.LocalCleartext, check("http://macbook.local:11434/v1"))
        // 带认证信息与端口
        assertEquals(EndpointSecurity.LocalCleartext, check("http://user:pass@192.168.0.2:8080/v1"))
        // IPv6 环回
        assertEquals(EndpointSecurity.LocalCleartext, check("http://[::1]:11434/v1"))
    }

    @Test
    fun `公网 http 必须报警`() {
        assertEquals(EndpointSecurity.PublicCleartext, check("http://api.deepseek.com/v1"))
        assertEquals(EndpointSecurity.PublicCleartext, check("http://1.2.3.4:11434/v1"))
        assertEquals(EndpointSecurity.PublicCleartext, check("http://172.32.0.1/v1"))
        assertEquals(EndpointSecurity.PublicCleartext, check("http://192.169.0.1/v1"))
    }

    @Test
    fun `大小写与域名误导都不能影响判断`() {
        assertEquals(EndpointSecurity.PublicCleartext, check("HTTP://API.EXAMPLE.COM/v1"))
        // 「fd」开头的域名不是 IPv6 唯一本地地址，不能被前缀骗过去
        assertEquals(EndpointSecurity.PublicCleartext, check("http://fdic.com/v1"))
        assertEquals(EndpointSecurity.PublicCleartext, check("http://localhost.evil.com/v1"))
    }

    @Test
    fun `完整路径也认得出主机`() {
        assertEquals(
            EndpointSecurity.LocalCleartext,
            check("http://192.168.1.8:11434/v1/chat/completions"),
        )
        assertEquals(
            EndpointSecurity.PublicCleartext,
            check("http://relay.example.com/v1/chat/completions?x=1"),
        )
    }
}
