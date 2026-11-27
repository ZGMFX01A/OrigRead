package me.ash.reader.infrastructure.content

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Whitelist as Safelist
import java.net.URI
import java.net.URISyntaxException

// 当前 Jsoup 的白名单 API 仍使用旧名称，别名保留相同语义且无需新增依赖。
object ContentHtmlSanitizer {
    fun sanitize(html: String, sourceUrl: String): String = clean(html, sourceUrl, false)

    fun sanitizeForWebView(html: String, sourceUrl: String): String = clean(html, sourceUrl, true)

    private fun clean(html: String, sourceUrl: String, webView: Boolean): String {
        val document = Jsoup.parseBodyFragment(html, sourceUrl)
        // 危险容器必须整块移除，避免 Cleaner 解包后泄露内部脚本或导航文本。
        document.select(DANGEROUS_TAGS + if (webView) "" else ", nav, footer, aside").remove()
        if (!webView) document.select("img[data-src], img[data-original]").forEach { image ->
            if (image.attr("src").isBlank()) {
                image.attr("src", image.attr("data-src").ifBlank { image.attr("data-original") })
            }
        }
        val body = Cleaner(articleSafelist(webView)).clean(document).body()
        body.select("*").forEach { element ->
            val urls = when (element.tagName()) {
                "a" -> listOf("href")
                "img" -> listOf("src", "data-src", "data-original")
                "video" -> listOf("src", "poster")
                "source", "audio", "track" -> listOf("src")
                "blockquote", "q" -> listOf("cite")
                else -> emptyList()
            }
            urls.filter(element::hasAttr).forEach { attribute ->
                val url = safeUrl(element.attr(attribute), sourceUrl, webView,
                    allowMail = element.tagName() == "a",
                    allowBitmap = webView && element.tagName() == "img" && attribute != "poster")
                if (url == null) element.removeAttr(attribute) else element.attr(attribute, url)
            }
            if (element.hasAttr("srcset")) sanitizeSrcset(element, sourceUrl, webView)
        }
        return body.html().trim()
    }

    private fun articleSafelist(webView: Boolean): Safelist = Safelist()
        .addTags(*ARTICLE_TAGS.split(' ').toTypedArray())
        .addAttributes(":all", "dir", "lang", "title")
        .addAttributes("a", "href", "rel")
        .addAttributes("img", "src", "alt", "width", "height", "loading", "decoding", "referrerpolicy", "srcset", "sizes")
        .addAttributes("source", "src", "srcset", "type", "media", "sizes")
        .addAttributes("video", "src", "poster", "controls", "width", "height", "preload", "playsinline", "muted", "loop")
        .addAttributes("audio", "src", "controls", "preload", "muted", "loop")
        .addAttributes("track", "src", "kind", "srclang", "label", "default")
        .addAttributes("th", "colspan", "rowspan", "scope", "headers")
        .addAttributes("td", "colspan", "rowspan", "scope", "headers")
        .addAttributes("col", "span").addAttributes("colgroup", "span")
        .addAttributes("ol", "start", "type", "reversed").addAttributes("time", "datetime")
        .addAttributes("blockquote", "cite").addAttributes("q", "cite").addAttributes("details", "open")
        .apply { if (webView) addAttributes("img", "data-src", "data-original") }

    internal fun safeImageUrl(raw: String): Boolean =
        safeUrl(raw, "", false, allowBitmap = true) != null

    private fun safeUrl(
        raw: String, sourceUrl: String, webView: Boolean,
        allowMail: Boolean = false, allowBitmap: Boolean = false,
    ): String? {
        val value = raw.trim().trimStart { it <= ' ' || it == '\u007f' || it in ZERO_WIDTH_PREFIX }
        if (value.isBlank() || value.any { it <= '\u001f' || it == '\u007f' || it == '\\' }) return null
        if (allowBitmap && BITMAP_DATA.matches(value)) return value
        val uri = try { URI(value) } catch (_: URISyntaxException) { return null }
        val scheme = uri.scheme?.lowercase()
        if (scheme in setOf("http", "https")) {
            return value.takeIf { !uri.host.isNullOrBlank() }
        }
        if (scheme == "mailto" && allowMail && uri.rawSchemeSpecificPart.isNotBlank()) return value
        if (scheme != null || value.startsWith("//")) return null
        if (webView) return value
        val base = try { URI(sourceUrl) } catch (_: URISyntaxException) { return null }
        val resolved = base.resolve(uri)
        return resolved.toString().takeIf {
            resolved.scheme?.lowercase() in setOf("http", "https") && !resolved.host.isNullOrBlank()
        }
    }

    private fun sanitizeSrcset(element: Element, sourceUrl: String, webView: Boolean) {
        val raw = element.attr("srcset")
        val candidates = mutableListOf<String>()
        var position = 0
        // URL 内可含逗号（包括位图 data URI）；只有尾逗号或描述符之后的逗号才分隔候选。
        while (position < raw.length) {
            while (position < raw.length && (raw[position].isWhitespace() || raw[position] == ',')) position++
            val start = position
            while (position < raw.length && !raw[position].isWhitespace()) position++
            val token = raw.substring(start, position)
            val ended = token.endsWith(',')
            val url = token.trimEnd(',')
            val descriptorStart = position
            if (!ended) while (position < raw.length && raw[position] != ',') position++
            val descriptor = if (ended) "" else raw.substring(descriptorStart, position).trim()
            if (position < raw.length && raw[position] == ',') position++
            val safe = safeUrl(url, sourceUrl, webView, allowBitmap = webView)
            if (safe != null && (descriptor.isEmpty() || SRCSET_DESCRIPTOR.matches(descriptor))) {
                candidates += safe + if (descriptor.isEmpty()) "" else " $descriptor"
            }
        }
        if (candidates.isEmpty()) element.removeAttr("srcset")
        else element.attr("srcset", candidates.joinToString(", "))
    }

    private const val DANGEROUS_TAGS = "script, style, noscript, template, svg, math, iframe, frame, frameset, object, embed, applet, base, meta, link, title, head, form, input, button, select, option, textarea"
    private const val ARTICLE_TAGS = "h1 h2 h3 h4 h5 h6 p br hr div span blockquote pre code strong b em i u s del ins mark small sub sup abbr cite q kbd samp var time wbr ruby rt rp ul ol li dl dt dd table caption colgroup col thead tbody tfoot tr th td a img picture source figure figcaption video audio track details summary section article header main"
    private val ZERO_WIDTH_PREFIX = setOf('\u200b', '\u200c', '\u200d', '\u2060', '\ufeff')
    private val BITMAP_DATA = Regex("data:image/(?:png|jpeg|jpg|gif|webp|avif);base64,[A-Za-z0-9+/]+={0,2}", RegexOption.IGNORE_CASE)
    private val SRCSET_DESCRIPTOR = Regex("(?:[1-9][0-9]*w|(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)x)")
}
