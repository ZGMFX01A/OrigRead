package me.ash.reader.infrastructure.content

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/** 清理正文 HTML，并将相对资源地址补全为绝对地址。 */
object ContentHtmlSanitizer {
    fun sanitize(html: String, sourceUrl: String): String {
        val document = Jsoup.parseBodyFragment(html, sourceUrl)
        val body = document.body()

        body.select("script, style, noscript, template, iframe, object, embed, form, input, button, nav, footer, aside")
            .remove()
        body.select("*").forEach(::sanitizeElement)
        body.select("a[href]").forEach { sanitizeUrlAttribute(it, "href") }
        body.select("img[src], source[src], video[src], audio[src]").forEach {
            sanitizeUrlAttribute(it, "src")
        }
        body.select("img[data-src], img[data-original]").forEach { element ->
            if (element.attr("src").isBlank()) {
                val attribute = if (element.attr("data-src").isNotBlank()) "data-src" else "data-original"
                element.attr("src", element.attr(attribute))
                sanitizeUrlAttribute(element, "src")
            }
        }
        body.select("img[srcset], source[srcset]").forEach(::sanitizeSrcSet)

        return body.html().trim()
    }

    private fun sanitizeElement(element: Element) {
        element.attributes().asList()
            .filter { attribute ->
                attribute.key.startsWith("on", ignoreCase = true) ||
                    attribute.key.equals("srcdoc", ignoreCase = true)
            }
            .forEach { element.removeAttr(it.key) }
    }

    /** 仅保留正文中可安全加载或跳转的网络 URL，并补全相对地址。 */
    private fun sanitizeUrlAttribute(element: Element, attribute: String) {
        val absoluteUrl = element.absUrl(attribute).trim()
        val scheme = runCatching { URI(absoluteUrl).scheme?.lowercase() }.getOrNull()
        if (absoluteUrl.isBlank() || scheme !in ALLOWED_URL_SCHEMES) {
            element.removeAttr(attribute)
        } else {
            element.attr(attribute, absoluteUrl)
        }
    }

    /** 补全响应式图片地址，并丢弃含危险协议的条目。 */
    private fun sanitizeSrcSet(element: Element) {
        val normalized = element.attr("srcset")
            .split(',')
            .mapNotNull { item ->
                val parts = item.trim().split(Regex("\\s+"), limit = 2)
                val rawUrl = parts.firstOrNull().orEmpty()
                if (rawUrl.isBlank()) return@mapNotNull null
                val absoluteUrl = runCatching { URI(element.baseUri()).resolve(rawUrl).toString() }.getOrNull().orEmpty()
                val scheme = runCatching { URI(absoluteUrl).scheme?.lowercase() }.getOrNull()
                if (scheme !in ALLOWED_URL_SCHEMES) return@mapNotNull null
                parts.getOrNull(1)?.let { "$absoluteUrl $it" } ?: absoluteUrl
            }
            .joinToString(", ")
        if (normalized.isBlank()) element.removeAttr("srcset") else element.attr("srcset", normalized)
    }

    private val ALLOWED_URL_SCHEMES = setOf("http", "https")
}
