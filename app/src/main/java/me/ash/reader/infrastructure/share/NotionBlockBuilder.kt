package me.ash.reader.infrastructure.share

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 将分享 HTML 映射为 Notion API 的页面块。图片只保留外链，不下载或上传图片文件。 */
object NotionBlockBuilder {
    fun fromHtml(html: String): List<JSONObject> {
        if (html.isBlank()) return emptyList()
        return Jsoup.parseBodyFragment(html).body().children().flatMap(::blocksFor)
    }

    private fun blocksFor(element: Element): List<JSONObject> {
        val tag = element.tagName().lowercase()
        return when {
            tag.matches(Regex("h[1-3]")) ->
                listOf(textBlock("heading_${tag[1]}", richTextFor(element)))
            tag == "p" -> paragraphOrImages(element)
            tag == "blockquote" -> quoteBlocks(element)
            tag == "ul" || tag == "ol" -> listBlocks(element)
            tag == "li" -> listOf(textBlock("bulleted_list_item", richTextFor(element)))
            tag == "img" -> imageBlock(element)?.let(::listOf).orEmpty()
            tag == "hr" -> listOf(JSONObject().put("object", "block").put("type", "divider").put("divider", JSONObject()))
            element.children().any(::isBlockElement) -> element.children().flatMap(::blocksFor)
            else -> paragraphOrImages(element)
        }
    }

    private fun paragraphOrImages(element: Element): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val richText = richTextFor(element)
        if (richText.length() > 0) result += textBlock("paragraph", richText)
        element.select("img").forEach { imageBlock(it)?.let(result::add) }
        return result
    }

    private fun quoteBlocks(element: Element): List<JSONObject> {
        val children = element.children()
        if (children.isEmpty()) {
            return listOf(textBlock("quote", richTextFor(element)))
        }

        return children.flatMap { child ->
            when (child.tagName().lowercase()) {
                "ul", "ol" ->
                    child.children()
                        .filter { it.tagName().lowercase() == "li" }
                        .map { item ->
                            textBlock(
                                "quote",
                                withPrefix(
                                    richTextFor(item),
                                    if (child.tagName().lowercase() == "ol") "• " else "• ",
                                ),
                            )
                        }
                "div" -> quoteBlocks(child)
                else -> listOf(textBlock("quote", richTextFor(child)))
            }
        }
    }

    private fun listBlocks(element: Element): List<JSONObject> {
        val type = if (element.tagName().lowercase() == "ol") "numbered_list_item" else "bulleted_list_item"
        return element.children()
            .filter { it.tagName().lowercase() == "li" }
            .flatMap { item ->
                buildList {
                    add(textBlock(type, richTextFor(item)))
                    item.children()
                        .filter { child -> child.tagName().lowercase() in setOf("ul", "ol") }
                        .flatMapTo(this) { nested -> listBlocks(nested) }
                }
            }
    }

    private fun textBlock(type: String, richText: JSONArray): JSONObject =
        JSONObject()
            .put("object", "block")
            .put("type", type)
            .put(type, JSONObject().put("rich_text", richText))

    private fun imageBlock(element: Element): JSONObject? {
        val source = element.absUrl("src").ifBlank { element.attr("src") }.trim()
        if (!source.startsWith("http://") && !source.startsWith("https://")) return null
        return JSONObject()
            .put("object", "block")
            .put("type", "image")
            .put(
                "image",
                JSONObject()
                    .put("type", "external")
                    .put("external", JSONObject().put("url", source)),
            )
    }

    private fun richTextFor(element: Element): JSONArray {
        val runs = mutableListOf<RichTextRun>()
        appendRuns(element, AnnotationState(), null, runs)
        return JSONArray().apply {
            runs.forEach { run ->
                run.text.chunked(MAX_RICH_TEXT_LENGTH).forEach { chunk ->
                    if (chunk.isNotEmpty()) {
                        put(
                            JSONObject()
                                .put("type", "text")
                                .put(
                                    "text",
                                    JSONObject().put("content", chunk).apply {
                                        run.link?.let { put("link", JSONObject().put("url", it)) }
                                    },
                                )
                                .put(
                                    "annotations",
                                    JSONObject()
                                        .put("bold", run.annotations.bold)
                                        .put("italic", run.annotations.italic)
                                        .put("strikethrough", run.annotations.strikethrough)
                                        .put("underline", run.annotations.underline)
                                        .put("code", run.annotations.code)
                                        .put("color", "default"),
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun appendRuns(
        node: Node,
        annotations: AnnotationState,
        link: String?,
        output: MutableList<RichTextRun>,
    ) {
        when (node) {
            is TextNode -> {
                val text = node.wholeText.replace(Regex("[\\t\\r\\n]+"), " ")
                // 保留标签之间合法的分隔空格，例如 <strong>Hello</strong> <em>World</em>。
                if (text.isNotEmpty()) output += RichTextRun(text, annotations, link)
            }
            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag == "img" || tag == "ul" || tag == "ol") return
                if (tag == "br") {
                    output += RichTextRun("\n", annotations, link)
                    return
                }
                val nextAnnotations =
                    annotations.copy(
                        bold = annotations.bold || tag == "strong" || tag == "b",
                        italic = annotations.italic || tag == "em" || tag == "i",
                        strikethrough = annotations.strikethrough || tag in setOf("del", "s", "strike"),
                        underline = annotations.underline || tag == "u",
                        code = annotations.code || tag == "code",
                    )
                val nextLink =
                    if (tag == "a") node.absUrl("href").ifBlank { node.attr("href") }.takeIf {
                        it.startsWith("http://") || it.startsWith("https://")
                    } else link
                node.childNodes().forEach { appendRuns(it, nextAnnotations, nextLink, output) }
            }
        }
    }

    private fun withPrefix(richText: JSONArray, prefix: String): JSONArray =
        JSONArray().apply {
            put(
                JSONObject()
                    .put("type", "text")
                    .put("text", JSONObject().put("content", prefix))
                    .put("annotations", JSONObject().put("color", "default")),
            )
            for (index in 0 until richText.length()) put(richText.getJSONObject(index))
        }

    private fun isBlockElement(element: Element): Boolean =
        element.tagName().lowercase() in
            setOf("address", "article", "blockquote", "div", "figure", "figcaption", "h1", "h2", "h3", "hr", "li", "ol", "p", "pre", "section", "table", "ul")

    private data class AnnotationState(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strikethrough: Boolean = false,
        val underline: Boolean = false,
        val code: Boolean = false,
    )

    private data class RichTextRun(
        val text: String,
        val annotations: AnnotationState,
        val link: String?,
    )

    private const val MAX_RICH_TEXT_LENGTH = 1900
}
