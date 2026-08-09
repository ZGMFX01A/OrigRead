package me.ash.reader.infrastructure.website

import java.util.Date
import me.ash.reader.domain.model.feed.Feed
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 常见静态新闻列表结构的固定 HTML 回归测试。 */
class AutomaticWebsiteFixtureCoverageTest {
    private val feed = Feed(
        id = "fixture-feed",
        name = "Fixture News",
        url = "https://news.example.com/",
        groupId = "fixture-group",
        accountId = 1,
    )
    private val fetchedAt = Date(1_786_000_000_000L)

    @Test
    fun `supports common static list structures`() {
        val cases = listOf(
            FixtureCase("website-samples/stateful-list.html", "/news/2026/08/05/stateful-news-", 6),
            FixtureCase("website-samples/table-list.html", "/notice/2026/08/05/", 5),
            FixtureCase("website-samples/definition-list.html", "/brief/2026/08/05/", 5),
            FixtureCase("website-samples/advertising-pagination.html", "/news/2026/08/05/mixed-stream-", 5),
            FixtureCase("website-samples/anonymous-cards.html", "/stories/2026/08/05/anonymous-card-", 5),
            FixtureCase("website-samples/query-id-list.html", "/view.php?", 5),
            FixtureCase("website-samples/nested-card-list.html", "/features/2026/08/05/nested-feature-", 5),
            FixtureCase("website-samples/mixed-separators.html", "/updates/2026/08/05/update-item-", 5),
            FixtureCase("website-samples/multi-link-card.html", "/reviews/2026/08/05/multi-link-review-", 5),
            FixtureCase("website-samples/pinned-list.html", "/pinned/2026/08/05/pinned-story-", 5),
            FixtureCase("website-samples/responsive-utility-list.html", "/responsive/2026/08/05/responsive-story-", 5),
            FixtureCase("website-samples/portal-columns.html", "/portal/2026/08/05/main-story-", 5),
            FixtureCase("website-samples/category-pollution.html", "/analysis/2026/08/05/category-clean-", 5),
            FixtureCase("website-samples/missing-date-list.html", "/nodate/2026/08/05/no-date-story-", 5),
            FixtureCase("website-samples/subdomain-mixed.html", "m.news.example.com/subdomain/2026/08/05/", 5),
            FixtureCase("website-samples/wordpress-archive.html", "/2026/08/05/wordpress-entry-", 5),
            FixtureCase("website-samples/pagination-content-area.html", "/page-two/2026/08/05/page-two-story-", 5),
        )

        cases.forEach { case ->
            val document = Jsoup.parse(resource(case.path), feed.url)
            val candidates = AutomaticWebsiteListDetector.detect(document, feed, fetchedAt)
            val candidate = candidates.firstOrNull { result ->
                result.articles.size == case.expectedCount &&
                    result.articles.all { it.link.contains(case.urlMarker) }
            }

            assertTrue("${case.path} should produce a matching candidate", candidate != null)
            val matched = requireNotNull(candidate)
            assertEquals(case.expectedCount, matched.articles.size)
            assertFalse(matched.rule.articleSelectors.any(String::isBlank))

            val reparsed = ConfigurableWebsiteParser(matched.rule).parse(document.clone(), feed, fetchedAt)
            assertEquals("${case.path} cached rule should be reusable", case.expectedCount, reparsed.size)
        }
    }

    @Test
    fun `state classes do not split one list or leak into cached selector`() {
        val document = Jsoup.parse(resource("website-samples/stateful-list.html"), feed.url)
        val candidate = AutomaticWebsiteListDetector.detect(document, feed, fetchedAt).first()

        assertEquals(6, candidate.articles.size)
        val selector = candidate.rule.articleSelectors.single()
        assertFalse(selector.contains(".odd"))
        assertFalse(selector.contains(".even"))
        assertFalse(selector.contains(".first"))
        assertFalse(selector.contains(".last"))
        assertFalse(Regex("\\.item-\\d+").containsMatchIn(selector))

        // 模拟刷新后奇偶行、首尾位置和序号 class 全部变化，缓存规则仍应命中完整列表。
        val refreshed = document.clone()
        refreshed.select("#stateful-list > li.news-row").forEachIndexed { index, item ->
            val parity = if (index % 2 == 0) "even" else "odd"
            val edge = when (index) {
                0 -> " last"
                5 -> " first"
                else -> ""
            }
            item.attr("class", "news-row $parity item-${20 + index}$edge")
        }

        val reparsed = ConfigurableWebsiteParser(candidate.rule).parse(refreshed, feed, fetchedAt)
        assertEquals(6, reparsed.size)
    }

    @Test
    fun `advertisements pagination and separators do not leak into cached result`() {
        val cases = listOf(
            "website-samples/advertising-pagination.html" to "/news/2026/08/05/mixed-stream-",
            "website-samples/mixed-separators.html" to "/updates/2026/08/05/update-item-",
        )

        cases.forEach { (path, articleMarker) ->
            val document = Jsoup.parse(resource(path), feed.url)
            val candidate = AutomaticWebsiteListDetector.detect(document, feed, fetchedAt)
                .first { result -> result.articles.all { it.link.contains(articleMarker) } }

            assertEquals(5, candidate.articles.size)
            assertFalse(candidate.articles.any { it.link.contains("/promo/") })
            assertFalse(candidate.articles.any { it.link.contains("page=") })

            val reparsed = ConfigurableWebsiteParser(candidate.rule).parse(document.clone(), feed, fetchedAt)
            assertEquals(5, reparsed.size)
            assertTrue(reparsed.all { it.link.contains(articleMarker) })
        }
    }

    @Test
    fun `query id pattern ignores tracking parameters while preserving real links`() {
        val document = Jsoup.parse(resource("website-samples/query-id-list.html"), feed.url)
        val candidate = AutomaticWebsiteListDetector.detect(document, feed, fetchedAt).first()

        assertEquals(5, candidate.articles.size)
        assertEquals("news.example.com/view.php?id={number}", candidate.rule.automaticUrlPattern)
        assertTrue(candidate.articles.all { it.link.contains("id=") })

        val refreshed = Jsoup.parse(
            resource("website-samples/query-id-list.html")
                .replace("utm_source=homepage", "utm_source=refresh")
                .replace("100", "900"),
            feed.url,
        )
        val reparsed = ConfigurableWebsiteParser(candidate.rule).parse(refreshed, feed, fetchedAt)

        assertEquals(5, reparsed.size)
        assertTrue(reparsed.all { it.link.contains("id=9") })
    }

    @Test
    fun `cached selector targets headline when card contains image and read more links`() {
        val document = Jsoup.parse(resource("website-samples/multi-link-card.html"), feed.url)
        val candidate = AutomaticWebsiteListDetector.detect(document, feed, fetchedAt).first()

        assertEquals(5, candidate.articles.size)
        assertTrue(candidate.articles.all { it.title.startsWith("多链接卡片") })
        assertFalse(candidate.rule.titleSelector == "a[href]")

        val reparsed = ConfigurableWebsiteParser(candidate.rule).parse(document.clone(), feed, fetchedAt)
        assertEquals(5, reparsed.size)
        assertTrue(reparsed.all { it.title.startsWith("多链接卡片") })
    }

    @Test
    fun `pinned state does not split the first article from the list`() {
        val document = Jsoup.parse(resource("website-samples/pinned-list.html"), feed.url)
        val candidate = AutomaticWebsiteListDetector.detect(document, feed, fetchedAt)
            .first { result -> result.articles.all { it.link.contains("/pinned/") } }

        assertEquals(5, candidate.articles.size)
        assertTrue(candidate.articles.first().title.contains("置顶"))
        val selector = candidate.rule.articleSelectors.single()
        assertFalse(selector.contains(".sticky"))
        assertFalse(selector.contains(".pinned"))
        assertFalse(selector.contains(".featured"))
    }

    @Test
    fun `responsive utility classes do not leak into cached selector`() {
        val document = Jsoup.parse(resource("website-samples/responsive-utility-list.html"), feed.url)
        val candidate = AutomaticWebsiteListDetector.detect(document, feed, fetchedAt)
            .first { result -> result.articles.all { it.link.contains("/responsive/") } }

        assertEquals(5, candidate.articles.size)
        val selector = candidate.rule.articleSelectors.single()
        assertFalse(selector.contains(".md-card"))

        val refreshed = document.clone()
        refreshed.select("#responsive-list > article").forEach { item ->
            item.removeClass("md-card")
            item.addClass("lg-card")
        }
        val reparsed = ConfigurableWebsiteParser(candidate.rule).parse(refreshed, feed, fetchedAt)
        assertEquals(5, reparsed.size)
    }

    @Test
    fun `same site subdomain is accepted while external links are rejected`() {
        val document = Jsoup.parse(resource("website-samples/subdomain-mixed.html"), feed.url)
        val candidate = AutomaticWebsiteListDetector.detect(document, feed, fetchedAt)
            .first { result -> result.articles.all { it.link.contains("m.news.example.com/subdomain/") } }

        assertEquals(5, candidate.articles.size)
        assertTrue(candidate.articles.all { it.link.startsWith("https://m.news.example.com/") })
        assertFalse(candidate.articles.any { it.link.contains("external.example.net") })

        val reparsed = ConfigurableWebsiteParser(candidate.rule).parse(document.clone(), feed, fetchedAt)
        assertEquals(5, reparsed.size)
        assertTrue(reparsed.all { it.link.startsWith("https://m.news.example.com/") })
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)) { "Missing test resource: $path" }
            .readText()

    private data class FixtureCase(
        val path: String,
        val urlMarker: String,
        val expectedCount: Int,
    )
}
