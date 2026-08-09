package me.ash.reader.infrastructure.website

import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticArticleDateExtractorTest {
    private val fetchedAt = date("2026-08-05T10:00:00+08:00")
    private val document = Jsoup.parse(resource("website-samples/date-extraction.html"), "https://news.example.com/")
    private val extractor = AutomaticArticleDateExtractor.create(document, fetchedAt)

    @Test
    fun `extracts metadata jsonld time text relative and url dates in order`() {
        assertDate("meta-date", "/article/1001", "2026-08-05T07:30:00+08:00")
        assertDate("jsonld-date", "/article/1002", "2026-08-04T08:15:00+08:00")
        assertDate("time-date", "/article/1003", "2026-08-03T11:45:00+08:00")
        assertLocalDate("text-date", "/article/1004", LocalDateTime.of(2026, 8, 2, 9, 20))
        assertDate("relative-date", "/article/1005", "2026-08-05T08:00:00+08:00")
        assertLocalDate("url-date", "/news/2026/08/01/url-date-1006.html", LocalDateTime.of(2026, 8, 1, 0, 0))
    }

    @Test
    fun `falls back to fetch time when no valid date exists`() {
        val item = Jsoup.parseBodyFragment("<article><a href='/article/2001'>没有日期的文章</a></article>")
            .selectFirst("article")!!

        assertEquals(fetchedAt, extractor.extract(item, "https://news.example.com/article/2001"))
    }

    private fun assertDate(itemId: String, path: String, expected: String) {
        val item = document.getElementById(itemId)!!
        val actual = extractor.extract(item, "https://news.example.com$path")
        assertEquals(date(expected), actual)
    }

    private fun assertLocalDate(itemId: String, path: String, expected: LocalDateTime) {
        val item = document.getElementById(itemId)!!
        val actual = extractor.extract(item, "https://news.example.com$path")
        assertEquals(Date.from(expected.atZone(ZoneId.systemDefault()).toInstant()), actual)
    }

    private fun date(value: String): Date = Date.from(OffsetDateTime.parse(value).toInstant())

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)) { "Missing test resource: $path" }
            .readText()
}
