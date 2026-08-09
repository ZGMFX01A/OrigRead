package me.ash.reader.infrastructure.content

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.nodes.Document

/** 提取 JSON-LD articleBody 以及 OpenGraph/description 兜底内容。 */
class StructuredMetadataContentExtractor @Inject constructor() : ContentExtractor {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun extract(document: Document, sourceUrl: String): List<ContentExtractionCandidate> = buildList {
        document.select("script[type=application/ld+json]").forEach { script ->
            val root = runCatching { json.parseToJsonElement(script.data().ifBlank { script.html() }) }.getOrNull()
                ?: return@forEach
            findObjects(root).forEach { node ->
                val articleBody = node.stringValue("articleBody")?.trim().orEmpty()
                if (articleBody.length < MIN_STRUCTURED_TEXT_LENGTH) return@forEach
                val html = textToParagraphs(articleBody)
                add(
                    ContentExtractionCandidate(
                        source = ContentExtractionSource.STRUCTURED_DATA,
                        html = html,
                        title = node.stringValue("headline") ?: node.stringValue("name"),
                        author = extractAuthor(node["author"]),
                        publishedTime = node.stringValue("datePublished"),
                        score = ContentCandidateScorer.score(html) + STRUCTURED_DATA_BONUS,
                    )
                )
            }
        }

        val description = sequenceOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
        if (!description.isNullOrBlank() && description.length >= MIN_META_TEXT_LENGTH) {
            val html = textToParagraphs(description)
            add(
                ContentExtractionCandidate(
                    source = ContentExtractionSource.META_DESCRIPTION,
                    html = html,
                    title = document.selectFirst("meta[property=og:title]")?.attr("content"),
                    score = ContentCandidateScorer.score(html),
                )
            )
        }
    }

    private fun findObjects(element: JsonElement): Sequence<JsonObject> = sequence {
        when (element) {
            is JsonObject -> {
                yield(element)
                element.values.forEach { yieldAll(findObjects(it)) }
            }
            is JsonArray -> element.forEach { yieldAll(findObjects(it)) }
            else -> Unit
        }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun extractAuthor(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element.stringValue("name")
        is JsonArray -> element.mapNotNull(::extractAuthor).joinToString().ifBlank { null }
        else -> null
    }

    private fun textToParagraphs(text: String): String =
        text.split(Regex("\\n{2,}"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(separator = "") { paragraph ->
                org.jsoup.nodes.Element("p").text(paragraph).outerHtml()
            }

    private companion object {
        const val MIN_STRUCTURED_TEXT_LENGTH = 80
        const val MIN_META_TEXT_LENGTH = 80
        const val STRUCTURED_DATA_BONUS = 20
    }
}
