package me.ash.reader.infrastructure.rsshub

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * RSSHub 实例专用客户端池。
 *
 * OkHttpClient.newBuilder() 默认会复用原客户端的 ConnectionPool；这会让 RSSHub 探测看似拥有
 * 独立 client，实际上仍共享底层连接状态。这里显式为每个实例 origin 创建独立 ConnectionPool，
 * 同时继承应用现有的 proxy / DNS / TLS / interceptor 等网络策略。
 */
internal class RssHubHttpClients(
    private val baseClient: OkHttpClient,
) {
    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    fun forInstance(instanceBaseUrl: String): OkHttpClient {
        val key = requireNotNull(instanceKey(instanceBaseUrl)) {
            "Invalid RSSHub instance: $instanceBaseUrl"
        }
        return clients.computeIfAbsent(key) { baseClient.newRssHubClient() }
    }

    fun forUrl(url: String): OkHttpClient {
        val httpUrl = requireNotNull(url.toHttpUrlOrNull()) { "Invalid RSSHub URL: $url" }
        val key = "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
        return clients.computeIfAbsent(key) { baseClient.newRssHubClient() }
    }

    internal fun cachedClientCount(): Int = clients.size

    private fun instanceKey(value: String): String? {
        val normalized = runCatching { RssHubSettingsRepository.normalizeInstanceUrl(value) }.getOrNull()
            ?: return null
        val url = normalized.toHttpUrlOrNull() ?: return null
        return "${url.scheme}://${url.host}:${url.port}${url.encodedPath.trimEnd('/')}"
    }
}

private fun OkHttpClient.newRssHubClient(): OkHttpClient = newBuilder()
    .connectionPool(ConnectionPool())
    // 实例已经并行探测，不再需要靠 5 秒硬截断来避免串行候选拖垮总耗时。
    // Bilibili / 微博等动态 route 可能需要实例侧抓取上游或启动浏览器，给单实例接近
    // 总探测预算的执行窗口，同时仍由 Resolver 的全局 budget 负责最终收口。
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(11, TimeUnit.SECONDS)
    .protocols(listOf(Protocol.HTTP_1_1))
    .build()
