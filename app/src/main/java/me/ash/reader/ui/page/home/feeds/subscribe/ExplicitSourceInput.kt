package me.ash.reader.ui.page.home.feeds.subscribe

import java.net.URI

/**
 * 明确的 JSON/API endpoint 必须只进入 JSON 探测链。
 * 这类地址如果 JSON 探测失败，应直接显示 JSON 错误，不能继续落到 Website/WebView。
 */
internal fun isExplicitJsonEndpoint(url: String): Boolean =
    runCatching {
        val path = URI(url).path.orEmpty().lowercase()
        path.contains("/wp-json/") ||
            path.endsWith(".json") ||
            path.startsWith("/api/") ||
            path.contains("/api/")
    }.getOrDefault(false)
