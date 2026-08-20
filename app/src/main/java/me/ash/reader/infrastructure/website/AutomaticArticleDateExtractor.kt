package me.ash.reader.infrastructure.website

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 自动 DOM 列表文章时间提取器。
 * 顺序为：节点元数据 → JSON-LD → time/日期属性 → 邻近文本 → URL 日期 → 抓取时间。
 */
class AutomaticArticleDateExtractor private constructor(
    private val fetchedAt: Date,
    private val jsonLdDatesByUrl: Map<String, Date>,
) {
    private val zoneId: ZoneId = ZoneId.systemDefault()

    fun extract(item: Element, articleUrl: String): Date {
        extractMetaDate(item)?.let { return it }
        jsonLdDatesByUrl[normalizeUrl(articleUrl)]?.let { return it }
        extractDateAttributes(item)?.let { return it }
        extractNearbyTextDate(item)?.let { return it }
        extractUrlDate(articleUrl)?.let { return it }
        return fetchedAt
    }

    /** 优先读取文章卡片内部的标准发布时间元数据。 */
    private fun extractMetaDate(item: Element): Date? =
        item.select(
            "meta[property=article:published_time], " +
                "meta[itemprop=datePublished], meta[name=publishdate], meta[name=date]"
        ).asSequence()
            .map { it.attr("content").trim() }
            .mapNotNull(::parseDateValue)
            .firstOrNull()

    /** 读取 time 标签和常见 data-* 时间属性。 */
    private fun extractDateAttributes(item: Element): Date? =
        item.select(
            "time, [datetime], [data-time], [data-date], [data-publish-time], " +
                "[data-published], [data-timestamp]"
        ).asSequence()
            .flatMap { element ->
                sequenceOf(
                    element.attr("datetime"),
                    element.attr("content"),
                    element.attr("data-time"),
                    element.attr("data-date"),
                    element.attr("data-publish-time"),
                    element.attr("data-published"),
                    element.attr("data-timestamp"),
                    element.text(),
                )
            }
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull(::parseDateValue)
            .firstOrNull()

    /** 仅扫描名称明显带 time/date/publish 的节点，避免从标题正文里的数字误判日期。 */
    private fun extractNearbyTextDate(item: Element): Date? {
        val likelyDateNodes = item.select(
            ".age, .time, .date, .datetime, .publish-time, .published-time, .post-date, .article-date, " +
                "[class*=time], [class*=date], [class*=publish], [id*=time], [id*=date], [id*=publish]"
        )
        return likelyDateNodes.asSequence()
            .take(20)
            .map(Element::text)
            .mapNotNull(::parseDateFromText)
            .firstOrNull()
            ?: parseDateFromText(item.ownText())
    }

    private fun parseDateFromText(raw: String): Date? {
        val text = raw.trim()
        if (text.isBlank()) return null
        parseRelativeDate(text)?.let { return it }

        absoluteDateRegex.find(text)?.value?.let(::parseDateValue)?.let { return it }
        chineseDateRegex.find(text)?.value?.let(::parseDateValue)?.let { return it }
        monthDayRegex.find(text)?.value?.let(::parseDateValue)?.let { return it }
        timeOnlyRegex.find(text)?.value?.let(::parseDateValue)?.let { return it }
        return null
    }

    private fun parseRelativeDate(text: String): Date? {
        val normalized = text.lowercase().replace(" ", "")
        if (normalized.contains("刚刚") || normalized.contains("刚才") || normalized == "justnow") {
            return fetchedAt
        }

        chineseRelativeRegex.find(normalized)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return@let
            val unit = match.groupValues[2]
            val instant = when (unit) {
                "秒" -> fetchedAt.toInstant().minus(amount, ChronoUnit.SECONDS)
                "分钟" -> fetchedAt.toInstant().minus(amount, ChronoUnit.MINUTES)
                "小时" -> fetchedAt.toInstant().minus(amount, ChronoUnit.HOURS)
                "天" -> fetchedAt.toInstant().minus(amount, ChronoUnit.DAYS)
                else -> return@let
            }
            return Date.from(instant)
        }

        englishRelativeRegex.find(text.lowercase())?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return@let
            val unit = match.groupValues[2]
            val instant = when {
                unit.startsWith("sec") -> fetchedAt.toInstant().minus(amount, ChronoUnit.SECONDS)
                unit.startsWith("min") -> fetchedAt.toInstant().minus(amount, ChronoUnit.MINUTES)
                unit.startsWith("hour") -> fetchedAt.toInstant().minus(amount, ChronoUnit.HOURS)
                unit.startsWith("day") -> fetchedAt.toInstant().minus(amount, ChronoUnit.DAYS)
                else -> return@let
            }
            return Date.from(instant)
        }

        val fetchedDate = fetchedAt.toInstant().atZone(zoneId).toLocalDate()
        val dayOffset = when {
            normalized.startsWith("今天") || normalized.startsWith("today") -> 0L
            normalized.startsWith("昨天") || normalized.startsWith("yesterday") -> 1L
            else -> return null
        }
        val time = timeOnlyRegex.find(text)?.value?.let(::parseLocalTime) ?: LocalTime.MIDNIGHT
        return Date.from(fetchedDate.minusDays(dayOffset).atTime(time).atZone(zoneId).toInstant())
    }

    private fun parseDateValue(raw: String): Date? {
        val value = raw.trim()
        if (value.isBlank()) return null

        value.toLongOrNull()?.let { number ->
            if (value.length in 10..13) {
                val millis = if (value.length == 10) number * 1000 else number
                return validDate(Date(millis))
            }
        }

        runCatching { Instant.parse(value) }.getOrNull()?.let { return validDate(Date.from(it)) }
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()?.let { return validDate(Date.from(it)) }
        runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()?.let { return validDate(Date.from(it)) }

        dateTimeFormatters.forEach { formatter ->
            try {
                val parsed = LocalDateTime.parse(value, formatter)
                return validDate(Date.from(parsed.atZone(zoneId).toInstant()))
            } catch (_: DateTimeParseException) {
                // 尝试下一个格式。
            }
        }

        dateFormatters.forEach { formatter ->
            try {
                val parsed = LocalDate.parse(value, formatter)
                return validDate(Date.from(parsed.atStartOfDay(zoneId).toInstant()))
            } catch (_: DateTimeParseException) {
                // 尝试下一个格式。
            }
        }

        parseMonthDay(value)?.let { return validDate(it) }
        parseLocalTime(value)?.let { time ->
            val reference = fetchedAt.toInstant().atZone(zoneId)
            var candidate = reference.toLocalDate().atTime(time).atZone(zoneId)
            if (candidate.toInstant().isAfter(fetchedAt.toInstant().plusSeconds(5 * 60))) {
                candidate = candidate.minusDays(1)
            }
            return validDate(Date.from(candidate.toInstant()))
        }
        return null
    }

    private fun parseMonthDay(value: String): Date? {
        val match = monthDayValueRegex.matchEntire(value.trim()) ?: return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val hour = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        val minute = match.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
        val reference = fetchedAt.toInstant().atZone(zoneId)
        var candidate = runCatching {
            LocalDateTime.of(reference.year, month, day, hour, minute).atZone(zoneId)
        }.getOrNull() ?: return null
        if (candidate.toInstant().isAfter(fetchedAt.toInstant().plus(7, ChronoUnit.DAYS))) {
            candidate = candidate.minusYears(1)
        }
        return Date.from(candidate.toInstant())
    }

    private fun parseLocalTime(value: String): LocalTime? =
        runCatching { LocalTime.parse(value.trim(), DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()

    private fun extractUrlDate(articleUrl: String): Date? {
        val path = runCatching { URI(articleUrl).path.orEmpty() }.getOrDefault("")
        val match = urlDateRegex.find(path)
        val compactMatch = compactUrlDateRegex.find(path)
        val year = (match?.groupValues?.getOrNull(1) ?: compactMatch?.groupValues?.getOrNull(1))?.toIntOrNull()
            ?: return null
        val month = (match?.groupValues?.getOrNull(2) ?: compactMatch?.groupValues?.getOrNull(2))?.toIntOrNull()
            ?: return null
        val day = (match?.groupValues?.getOrNull(3) ?: compactMatch?.groupValues?.getOrNull(3))?.toIntOrNull()
            ?: return null
        return runCatching {
            Date.from(LocalDate.of(year, month, day).atStartOfDay(zoneId).toInstant())
        }.getOrNull()?.let(::validDate)
    }

    /** 排除明显来自错误数字或模板占位符的未来时间。 */
    private fun validDate(date: Date): Date? =
        date.takeIf { it.time <= fetchedAt.time + MAX_FUTURE_TIME_MS }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val MAX_FUTURE_TIME_MS = 2L * 24 * 60 * 60 * 1000

        private val dateTimeFormatters = listOf(
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
            DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm:ss", Locale.CHINA),
            DateTimeFormatter.ofPattern("yyyy年M月d日 H:mm", Locale.CHINA),
        )
        private val dateFormatters = listOf(
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA),
        )

        private val chineseRelativeRegex = Regex("(\\d+)(秒|分钟|小时|天)前")
        private val englishRelativeRegex = Regex("(\\d+)\\s*(second|seconds|sec|minute|minutes|min|hour|hours|day|days)\\s+ago")
        private val absoluteDateRegex = Regex("(?:19|20)\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}(?:[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)?")
        private val chineseDateRegex = Regex("(?:19|20)\\d{2}年\\d{1,2}月\\d{1,2}日(?:\\s*\\d{1,2}:\\d{2}(?::\\d{2})?)?")
        private val monthDayRegex = Regex("(?<!\\d)\\d{1,2}-\\d{1,2}(?:\\s+\\d{1,2}:\\d{2})?(?!\\d)")
        private val timeOnlyRegex = Regex("(?<!\\d)\\d{1,2}:\\d{2}(?!\\d)")
        private val monthDayValueRegex = Regex("^(\\d{1,2})-(\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{2}))?$")
        private val urlDateRegex = Regex("(?:^|/)((?:19|20)\\d{2})[/_-](\\d{1,2})[/_-](\\d{1,2})(?:/|[-_.]|$)")
        private val compactUrlDateRegex = Regex("(?:^|/)((?:19|20)\\d{2})(\\d{2})(\\d{2})(?:/|[-_.]|$)")

        fun create(document: Document, fetchedAt: Date): AutomaticArticleDateExtractor =
            AutomaticArticleDateExtractor(
                fetchedAt = fetchedAt,
                jsonLdDatesByUrl = collectJsonLdDates(document, fetchedAt),
            )

        private fun collectJsonLdDates(document: Document, fetchedAt: Date): Map<String, Date> {
            val temporaryExtractor = AutomaticArticleDateExtractor(fetchedAt, emptyMap())
            val dates = linkedMapOf<String, Date>()
            document.select("script[type=application/ld+json]").forEach { script ->
                val root = runCatching {
                    json.parseToJsonElement(script.data().ifBlank { script.html() })
                }.getOrNull() ?: return@forEach
                findObjects(root).forEach { node ->
                    val rawDate = sequenceOf("datePublished", "dateCreated", "uploadDate")
                        .mapNotNull { key -> node.stringValue(key) }
                        .firstOrNull()
                        ?: return@forEach
                    val date = temporaryExtractor.parseDateValue(rawDate) ?: return@forEach
                    extractJsonLdUrls(node).forEach { rawUrl ->
                        resolveUrl(document.baseUri(), rawUrl)?.let { resolved ->
                            dates.putIfAbsent(normalizeUrl(resolved), date)
                        }
                    }
                }
            }
            return dates
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

        private fun extractJsonLdUrls(node: JsonObject): Sequence<String> = sequence {
            node.stringValue("url")?.let { yield(it) }
            node.stringValue("@id")?.let { yield(it) }
            when (val mainEntity = node["mainEntityOfPage"]) {
                is JsonPrimitive -> mainEntity.contentOrNull?.let { yield(it) }
                is JsonObject -> {
                    mainEntity.stringValue("@id")?.let { yield(it) }
                    mainEntity.stringValue("url")?.let { yield(it) }
                }
                else -> Unit
            }
        }

        private fun JsonObject.stringValue(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

        private fun resolveUrl(baseUrl: String, value: String): String? =
            runCatching {
                val base = URI(baseUrl.ifBlank { value })
                base.resolve(value.trim()).toString()
            }.getOrNull()

        private fun normalizeUrl(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return url.trim().trimEnd('/')
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme.isBlank() || host.isBlank()) return url.trim().trimEnd('/')
            val port = when {
                uri.port < 0 -> ""
                scheme == "http" && uri.port == 80 -> ""
                scheme == "https" && uri.port == 443 -> ""
                else -> ":${uri.port}"
            }
            val path = uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
            return buildString {
                append(scheme).append("://").append(host).append(port).append(path)
                uri.rawQuery?.takeIf(String::isNotBlank)?.let { append('?').append(it) }
            }
        }
    }
}
