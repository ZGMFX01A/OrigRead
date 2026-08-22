package me.ash.reader.infrastructure.share

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 将分享 HTML 转成适合不解析富文本的笔记软件的可读纯文本。 */
object HtmlToPlainText {
    fun convert(html: String): String {
        if (html.isBlank()) return ""

        val body = Jsoup.parseBodyFragment(html).body()
        body.select("script, style, noscript, template").remove()
        return normalize(renderChildren(body))
    }

    private fun renderChildren(parent: Element): String =
        buildString {
            parent.childNodes().forEach { append(renderNode(it)) }
        }

    private fun renderNode(node: Node): String =
        when (node) {
            is TextNode -> node.text().replace(Regex("\\s+"), " ")
            is Element -> renderElement(node)
            else -> ""
        }

    private fun renderElement(element: Element): String {
        val tag = element.tagName().lowercase()
        return when (tag) {
            "br" -> "\n"
            "img" -> element.attr("alt").trim().ifBlank { "图片" }
            "hr" -> "\n\n"
            "a" -> {
                val text = renderChildren(element).trim()
                val href = element.absUrl("href").ifBlank { element.attr("href") }.trim()
                when {
                    text.isBlank() -> href
                    href.isBlank() || text == href -> text
                    else -> "$text（$href）"
                }
            }
            "li" -> renderChildren(element).trim() + "\n\n"
            "td", "th" -> renderChildren(element).trim() + "  "
            in BLOCK_TAGS -> renderChildren(element).trim() + "\n\n"
            else -> renderChildren(element)
        }
    }

    private fun normalize(value: String): String =
        value
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private val BLOCK_TAGS =
        setOf(
            "address", "article", "aside", "blockquote", "dd", "div", "dl", "dt",
            "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5",
            "h6", "header", "li", "main", "ol", "p", "pre", "section", "table", "tr",
            "ul",
        )
}
