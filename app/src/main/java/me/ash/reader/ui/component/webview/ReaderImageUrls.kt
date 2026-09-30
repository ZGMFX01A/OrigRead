package me.ash.reader.ui.component.webview

import java.net.URI
import org.jsoup.nodes.Element

/** 修正支持 HTTPS 的 Bilibili 图片 CDN 地址，兼容已入库的旧 RSS 正文。 */
internal fun upgradeReaderImageUrls(body: Element): Boolean {
    var changed = false
    body.select("img").forEach { image ->
        listOf("src", "data-src", "data-original").forEach { attribute ->
            val original = image.attr(attribute)
            if (canUpgradeReaderImageUrl(original)) {
                image.attr(attribute, "https" + original.trim().substring(4))
                changed = true
            }
        }
    }
    body.select("img[srcset], picture source[srcset]").forEach { image ->
        val original = image.attr("srcset")
        val upgraded = upgradeReaderImageSrcSet(original)
        if (original != upgraded) {
            image.attr("srcset", upgraded)
            changed = true
        }
    }
    return changed
}

private fun canUpgradeReaderImageUrl(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("http", ignoreCase = true) && uri.port == -1 && uri.rawUserInfo == null &&
        (host == "hdslb.com" || host.endsWith(".hdslb.com"))
}

/** URL 内的逗号（data URL、查询参数）不是候选分隔符；只跳过 URL 后的描述符。 */
private fun upgradeReaderImageSrcSet(value: String): String {
    val replacements = mutableListOf<Int>()
    var position = 0
    while (position < value.length) {
        while (position < value.length && (value[position].isWhitespace() || value[position] == ',')) position++
        val start = position
        while (position < value.length && !value[position].isWhitespace()) position++
        val token = value.substring(start, position)
        if (canUpgradeReaderImageUrl(token.trimEnd(','))) replacements += start
        if (token.endsWith(',')) continue
        var parentheses = 0
        while (position < value.length) {
            val character = value[position++]
            if (character == '(') parentheses++
            if (character == ')' && parentheses > 0) parentheses--
            if (character == ',' && parentheses == 0) break
        }
    }
    return StringBuilder(value).apply {
        replacements.asReversed().forEach { replace(it, it + 4, "https") }
    }.toString()
}
