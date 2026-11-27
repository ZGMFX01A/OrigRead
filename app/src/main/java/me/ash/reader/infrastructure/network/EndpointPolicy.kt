package me.ash.reader.infrastructure.network

import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** 例外只能来自用户填写的字面本地地址，不能由服务器元数据或 DNS 回答扩展。 */
object EndpointPolicy {
    fun isLoopback(url: HttpUrl): Boolean = url.host == "localhost" ||
        literalAddress(url.host)?.isLoopbackAddress == true

    fun isExplicitLocal(url: HttpUrl): Boolean = isLoopback(url) ||
        literalAddress(url.host)?.let { it.isSiteLocalAddress || it.isLinkLocalAddress ||
            (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc) } == true

    fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    fun requireUrl(raw: String, allowLocal: Boolean = false, requireHttps: Boolean = true): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("不是有效 HTTP(S) URL")
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "URL 不能包含凭据或 fragment"
        }
        require(!requireHttps || url.scheme == "https" || (allowLocal && isLoopback(url))) { "远端服务必须使用 HTTPS" }
        val literal = literalAddress(url.host)
        require(allowLocal || (url.host != "localhost" && (literal == null || isPublicAddress(literal)))) {
            "禁止访问私网、环回或保留地址"
        }
        return url
    }

    /** 在真实建连时校验全部 DNS 回答，并将原回答交给 OkHttp，避免另一次解析产生 rebinding。 */
    fun client(base: OkHttpClient, allowLocal: Boolean = false): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = base.dns.lookup(hostname)
                if (addresses.isEmpty() || (!allowLocal && addresses.any { !isPublicAddress(it) })) {
                    throw UnknownHostException("禁止访问私网、环回或保留地址")
                }
                return addresses
            }
        }).build()

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            return !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
                (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
                (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 192 && b[1] == 0 && b[2] in listOf(0, 2)) ||
                (b[0] == 192 && b[1] == 88 && b[2] == 99) ||
                (b[0] == 198 && b[1] in 18..19) || (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
                (b[0] == 203 && b[1] == 0 && b[2] == 113))
        }
        // 只允许 IPv6 global unicast，排除 ULA、IPv4 映射、NAT64、隧道、文档与基准网段。
        return b.size == 16 && (b[0] and 0xe0) == 0x20 &&
            !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8))) &&
            !(b[0] == 0x20 && b[1] == 0x02) &&
            !(b[0] == 0x3f && b[1] == 0xff && b[2] < 0x10)
    }

    private fun literalAddress(host: String): InetAddress? {
        if (':' !in host && !host.matches(Regex("[0-9.]+"))) return null
        return try { InetAddress.getByName(host) } catch (error: UnknownHostException) {
            throw IllegalArgumentException("无效 IP 地址", error)
        }
    }
}
