package me.ash.reader.infrastructure.share

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 将阅读页 HTML 转成兼容 Obsidian 等文本接收器的 Markdown。 */
object HtmlToMarkdown {
    fun convert(html: String): String {
        if (html.isBlank()) return ""

        val body = Jsoup.parseBodyFragment(html).body()
        return normalize(renderBlocks(body))
    }

    private fun renderBlocks(parent: Element): String =
        buildString {
            parent.childNodes().forEach { node ->
                when (node) {
                    is TextNode -> append(escapeText(node.text()))
                    is Element -> append(renderBlock(node))
                }
            }
        }

    private fun renderBlock(element: Element): String {
        val tag = element.tagName().lowercase()
        return when {
            tag.matches(Regex("h[1-6]")) -> {
                val level = tag[1].digitToInt()
                "${"#".repeat(level)} ${renderInlineChildren(element).trim()}\n\n"
            }
            tag == "p" -> "${renderInlineChildren(element).trim()}\n\n"
            tag == "br" -> "\n"
            tag == "hr" -> "---\n\n"
            tag == "blockquote" -> renderQuote(element)
            tag == "pre" -> renderCode(element)
            tag == "ul" || tag == "ol" -> renderList(element)
            tag == "table" -> renderTable(element)
            tag == "img" -> "${renderInline(element)}\n\n"
            tag == "li" -> "${renderInlineChildren(element).trim()}\n"
            else -> {
                if (hasBlockChildren(element)) {
                    renderBlocks(element) + "\n"
                } else {
                    "${renderInline(element).trim()}\n\n"
                }
            }
        }
    }

    private fun renderInlineChildren(element: Element): String =
        buildString {
            element.childNodes().forEach { node ->
                when (node) {
                    is TextNode -> append(escapeText(node.text()))
                    is Element -> {
                        val childTag = node.tagName().lowercase()
                        if (childTag == "ul" || childTag == "ol") return@forEach
                        append(renderInline(node))
                    }
                }
            }
        }

    private fun renderInline(node: Node): String =
        when (node) {
            is TextNode -> escapeText(node.text())
            is Element -> {
                val tag = node.tagName().lowercase()
                when (tag) {
                    "strong", "b" -> "**${renderInlineChildren(node)}**"
                    "em", "i" -> "*${renderInlineChildren(node)}*"
                    "del", "s", "strike" -> "~~${renderInlineChildren(node)}~~"
                    "code" -> "`${node.text().replace("`", "\\`")}`"
                    "br" -> "\n"
                    "a" -> renderLink(node)
                    "img" -> renderImage(node)
                    else -> renderInlineChildren(node)
                }
            }
            else -> ""
        }

    private fun renderLink(element: Element): String {
        val text = renderInlineChildren(element).trim()
        val href = element.absUrl("href").ifBlank { element.attr("href") }
        return if (text.isBlank()) {
            href
        } else if (href.startsWith("http://") || href.startsWith("https://")) {
            "[$text](${href.replace(")", "\\)")})"
        } else {
            text
        }
    }

    private fun renderImage(element: Element): String {
        val source =
            sequenceOf("src", "data-src", "data-original", "data-lazy-src")
                .map { attribute -> element.absUrl(attribute).ifBlank { element.attr(attribute) } }
                .firstOrNull(String::isNotBlank)
                .orEmpty()
        if (!source.startsWith("http://") && !source.startsWith("https://")) return ""
        val alt = escapeText(element.attr("alt").ifBlank { "image" })
        return "![$alt](${source.replace(")", "\\)")})"
    }

    private fun renderQuote(element: Element): String {
        val content = normalize(renderBlocks(element))
        if (content.isBlank()) return ""
        return content.lineSequence().joinToString("\n") { "> $it" } + "\n\n"
    }

    private fun renderCode(element: Element): String {
        val code = element.selectFirst("code")?.wholeText() ?: element.wholeText()
        val language = element.selectFirst("code")?.classNames()
            ?.firstOrNull { it.startsWith("language-") }
            ?.removePrefix("language-")
            .orEmpty()
        return "```$language\n${code.trimEnd()}\n```\n\n"
    }

    private fun renderList(element: Element, indent: String = ""): String =
        buildString {
            val ordered = element.tagName().lowercase() == "ol"
            var index = 1
            element.children().filter { it.tagName().lowercase() == "li" }.forEach { item ->
                val marker = if (ordered) "${index++}." else "-"
                append(indent).append(marker).append(' ')
                append(renderInlineChildren(item).trim())
                append('\n')
                item.children()
                    .filter { it.tagName().lowercase() == "ul" || it.tagName().lowercase() == "ol" }
                    .forEach { nested -> append(renderList(nested, "$indent  ")) }
            }
            append('\n')
        }

    private fun renderTable(element: Element): String {
        val rows = element.select("tr")
        if (rows.isEmpty()) return ""
        val cells = rows.map { row ->
            row.children().filter { it.tagName().lowercase() == "th" || it.tagName().lowercase() == "td" }
                .map { renderInlineChildren(it).trim() }
        }
        val width = cells.maxOfOrNull { it.size } ?: return ""
        if (width == 0) return ""
        fun row(values: List<String>) = "| ${values.take(width).let { it + List(width - it.size) { "" } }.joinToString(" | ")} |\n"
        return buildString {
            append(row(cells.first()))
            append(row(List(width) { "---" }))
            cells.drop(1).forEach { append(row(it)) }
            append('\n')
        }
    }

    private fun hasBlockChildren(element: Element): Boolean =
        element.children().any {
            it.tagName().lowercase() in setOf("address", "article", "aside", "div", "dl", "figure", "footer", "header", "main", "nav", "ol", "p", "pre", "section", "table", "ul")
        }

    private fun escapeText(value: String): String =
        value.replace(Regex("[\\t\\n\\r ]+"), " ")
            .replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("[", "\\[")
            .replace("]", "\\]")

    private fun normalize(value: String): String =
        value.replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}
