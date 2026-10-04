package me.ash.reader.infrastructure.source

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import me.ash.reader.domain.model.feed.Feed

/**
 * 来源 URL 的比较键。仅用于查重/候选去重，不改写真正保存和请求的 URL。
 *
 * 新候选只归一化 scheme/host、默认端口和 fragment，路径与 query 保留来源语义。
 * 历史 v1 算法独立保存，已持久化 key 不因升级而漂移。
 */
object SourceUrlNormalizer {
    private val trackingQueryKeys =
        setOf(
            "fbclid",
            "gclid",
            "dclid",
            "msclkid",
            "mc_cid",
            "mc_eid",
            "spm",
        )

    /** 新候选保留业务 query、路径尾斜杠及原转义；HttpUrl 统一 Unicode/空格编码。 */
    fun comparisonKey(value: String): String {
        val trimmed = value.trim()
        val url = trimmed.toHttpUrlOrNull() ?: return trimmed
        return url.newBuilder().fragment(null).build().toString()
    }

    /** 已签名 v1 身份的历史算法；仅用于读取和核对旧 key，不用于创建新映射。 */
    fun legacyComparisonKey(value: String): String {
        val trimmed = value.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val scheme = uri.scheme?.lowercase() ?: return trimmed
        val host = uri.host?.lowercase() ?: return trimmed
        if (scheme !in setOf("http", "https")) return trimmed

        val port =
            when {
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }
        val path =
            uri.rawPath.orEmpty()
                .let { if (it == "/") "" else it.trimEnd('/') }
        val query = normalizeQuery(uri.rawQuery)

        return URI(
            scheme,
            uri.rawUserInfo,
            host,
            port,
            path,
            query.ifBlank { null },
            null,
        ).toASCIIString()
    }

    private fun normalizeQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrBlank()) return ""
        return rawQuery.split('&')
            .filter { pair ->
                val rawKey = pair.substringBefore('=').trim().lowercase()
                rawKey.isNotBlank() && !rawKey.startsWith("utm_") && rawKey !in trackingQueryKeys
            }
            .joinToString("&")
    }
}

/** Feed merge 共用的 URL 匹配入口，确保配置恢复与 Edition Sync 使用完全相同的去重语义。 */
internal fun findFeedByComparisonUrl(
    existingFeeds: List<Feed>,
    candidateUrl: String,
): Feed? {
    val candidateKey = SourceUrlNormalizer.comparisonKey(candidateUrl)
    return existingFeeds.firstOrNull { SourceUrlNormalizer.comparisonKey(it.url) == candidateKey }
}
