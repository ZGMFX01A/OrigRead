package me.ash.reader.infrastructure.json

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.ui.ext.spacerDollar
import org.jsoup.Jsoup

/** 根据 JsonRule 将公开 JSON API 结果转换为文章列表。 */
class JsonArticleParser @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(
        content: String,
        rule: JsonRule,
        feed: Feed,
        baseUrl: String,
        fetchedAt: Date = Date(),
    ): List<Article> {
        val root = json.parseToJsonElement(content)
        val seenLinks = hashSetOf<String>()
        return SimpleJsonPath.query(root, rule.itemsPath)
            .asSequence()
            .mapNotNull { item -> buildArticle(item, rule, feed, baseUrl, fetchedAt) }
            .filter { seenLinks.add(it.link) }
            .take(rule.maxItems)
            .toList()
            .also { require(it.isNotEmpty()) { "规则 ${rule.name} 未解析出有效文章" } }
    }

    private fun buildArticle(
        item: JsonElement,
        rule: JsonRule,
        feed: Feed,
        baseUrl: String,
        fetchedAt: Date,
    ): Article? {
        val title = stringValue(item, rule.titlePath)
            ?.toPlainText()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val linkValue = stringValue(item, rule.linkPath)?.trim()?.takeIf(String::isNotBlank) ?: return null
        val link = resolveUrl(baseUrl, linkValue) ?: return null
        val description = stringValue(item, rule.descriptionPath).orEmpty()
        val image = stringValue(item, rule.imagePath)?.let { resolveUrl(baseUrl, it) }
        val stableId = stringValue(item, rule.idPath).orEmpty().ifBlank { link }

        return Article(
            id = feed.accountId.spacerDollar(stableId.ifBlank { UUID.randomUUID().toString() }),
            date = parseDate(SimpleJsonPath.first(item, rule.datePath), rule.dateFormat, fetchedAt),
            title = title,
            author = stringValue(item, rule.authorPath)?.toPlainText()?.ifBlank { null },
            rawDescription = description,
            shortDescription = description,
            img = image,
            link = link,
            feedId = feed.id,
            accountId = feed.accountId,
            updateAt = fetchedAt,
        )
    }

    private fun stringValue(root: JsonElement, path: String?): String? =
        SimpleJsonPath.first(root, path)
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull

    /** WordPress rendered 字段等可能携带 HTML 与实体，标题和作者统一转为纯文本。 */
    private fun String.toPlainText(): String = Jsoup.parse(this).text().trim()

    private fun parseDate(value: JsonElement?, format: String?, fallback: Date): Date {
        val primitive = value as? JsonPrimitive ?: return fallback
        primitive.longOrNull?.let { number ->
            val millis = if (number < 10_000_000_000L) number * 1000 else number
            return Date(millis)
        }
        val text = primitive.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) return fallback
        val formats = listOfNotNull(format, "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")
        return formats.asSequence()
            .mapNotNull { pattern ->
                runCatching {
                    SimpleDateFormat(pattern, Locale.getDefault()).apply { isLenient = false }.parse(text)
                }.getOrNull()
            }
            .firstOrNull() ?: fallback
    }

    private fun resolveUrl(baseUrl: String, value: String): String? =
        runCatching {
            val resolved = URI(baseUrl).resolve(value.trim()).toString()
            resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.getOrNull()
}

