package me.ash.reader.infrastructure.website

import java.util.Date
import me.ash.reader.domain.model.feed.Feed
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticWebsiteListDetectorTest {
    private val feed = Feed(
        id = "feed-1",
        name = "Example News",
        url = "https://news.example.com/",
        groupId = "group-1",
        accountId = 1,
    )

    @Test
    fun `clusters article urls and removes repeated category and external links`() {
        val document = loadSample()

        val candidates = AutomaticWebsiteListDetector.detect(document, feed, Date(1_786_000_000_000L))
        val articles = candidates.first().articles

        assertEquals(5, articles.size)
        assertTrue(articles.all { it.link.contains("/news/2026/08/05/") })
        assertFalse(articles.any { it.link.contains("/category/") })
        assertFalse(articles.any { it.link.contains("/author/") })
        assertFalse(articles.any { it.link.contains("/tag/") })
        assertFalse(articles.any { it.link.contains("/search") })
        assertFalse(articles.any { it.link.contains("external.example.net") })
    }

    @Test
    fun `candidate id stays stable when concrete article ids change`() {
        val first = AutomaticWebsiteListDetector.detect(loadSample(), feed, Date()).first()
        val changedHtml = resource("website-samples/url-clusters.html")
            .replace("100", "900")
        val secondDocument = Jsoup.parse(changedHtml, "https://news.example.com/")
        val second = AutomaticWebsiteListDetector.detect(secondDocument, feed, Date()).first()

        assertEquals(first.rule.id, second.rule.id)
    }

    @Test
    fun `generated automatic rule reparses refreshed page without dom analysis`() {
        val fetchedAt = Date(1_786_000_000_000L)
        val candidate = AutomaticWebsiteListDetector.detect(loadSample(), feed, fetchedAt).first()
        val refreshedHtml = resource("website-samples/url-clusters.html")
            .replace("100", "900")
        val refreshedDocument = Jsoup.parse(refreshedHtml, "https://news.example.com/")

        val articles = ConfigurableWebsiteParser(candidate.rule).parse(refreshedDocument, feed, fetchedAt)

        assertTrue(AutomaticWebsiteListDetector.isReusableRule(candidate.rule))
        assertTrue(candidate.rule.articleSelectors.all(String::isNotBlank))
        assertTrue(candidate.rule.titleSelector.isNotBlank())
        assertEquals(5, articles.size)
        assertTrue(articles.all { it.link.contains("-900") })
    }

    @Test
    fun `older automatic rule version is invalidated after selector strategy changes`() {
        val currentRule = AutomaticWebsiteListDetector.detect(loadSample(), feed, Date()).first().rule

        assertTrue(AutomaticWebsiteListDetector.isReusableRule(currentRule))
        assertFalse(AutomaticWebsiteListDetector.isReusableRule(currentRule.copy(version = 6)))
        assertFalse(AutomaticWebsiteListDetector.isReusableRule(currentRule.copy(version = 5)))
        assertFalse(AutomaticWebsiteListDetector.isReusableRule(currentRule.copy(version = 4)))
    }

    @Test
    fun `article title pattern outranks repeated metadata link pattern in same cards`() {
        val document = Jsoup.parse(articleCardsWithBrandLinksHtml(), "https://news.example.com/")

        val candidates = AutomaticWebsiteListDetector.detect(document, feed, Date(1_786_000_000_000L))

        assertTrue(
            candidates.joinToString { candidate ->
                "${candidate.articles.firstOrNull()?.link}:${candidate.diagnostics.linkQualityScore}"
            },
            candidates.size >= 2,
        )
        assertTrue(candidates.first().articles.all { it.link.contains("/archives/") })
        val brandCandidate = candidates.firstOrNull { candidate ->
            candidate.articles.all { it.link.contains("/brand/") }
        }
        assertTrue(brandCandidate != null)
        assertTrue((brandCandidate?.diagnostics?.linkQualityScore ?: 0) < 0)
        assertEquals(0, candidates.first().diagnostics.linkQualityScore)
    }

    @Test
    fun `block anchor card uses nested heading as title and outer anchor as link`() {
        val document = Jsoup.parse(blockAnchorCardsHtml(), "https://news.example.com/")
        val fetchedAt = Date(1_786_000_000_000L)

        val candidate = AutomaticWebsiteListDetector.detect(document, feed, fetchedAt).first()
        val reparsed = ConfigurableWebsiteParser(candidate.rule).parse(document.clone(), feed, fetchedAt)

        assertEquals(5, candidate.articles.size)
        assertEquals(5, reparsed.size)
        assertTrue(candidate.articles.all { it.title.startsWith("整卡链接文章") })
        assertTrue(reparsed.all { it.title.startsWith("整卡链接文章") })
        assertTrue(candidate.rule.titleSelector != candidate.rule.linkSelector)
    }

    @Test
    fun `main latest list outranks larger sidebar ranking list`() {
        val document = Jsoup.parse(
            resource("website-samples/region-priority.html"),
            "https://news.example.com/",
        )

        val candidates = AutomaticWebsiteListDetector.detect(document, feed, Date(1_786_000_000_000L))
        val mainCandidate = candidates.first()

        assertEquals(5, mainCandidate.articles.size)
        assertTrue(mainCandidate.articles.all { it.link.contains("/news/2026/08/05/") })
        assertTrue(mainCandidate.diagnostics.regionScore > 0)
        assertEquals(mainCandidate.diagnostics.regionScore, mainCandidate.rule.automaticRegionScore)
    }

    @Test
    fun `history score promotes previously stable list during candidate competition`() {
        val document = Jsoup.parse(twoNeutralListsHtml(), "https://news.example.com/")
        val baseline = AutomaticWebsiteListDetector.detect(document, feed, Date(1_786_000_000_000L))

        assertTrue(baseline.size >= 2)
        val stableRuleId = baseline[1].rule.id
        val rescored = AutomaticWebsiteListDetector.detect(
            document = document,
            feed = feed,
            fetchedAt = Date(1_786_000_000_000L),
            historyScoreProvider = { ruleId -> if (ruleId == stableRuleId) 12 else 0 },
        )

        assertEquals(stableRuleId, rescored.first().rule.id)
        assertEquals(12, rescored.first().diagnostics.historyScore)
    }

    private fun loadSample() =
        Jsoup.parse(resource("website-samples/url-clusters.html"), "https://news.example.com/")

    private fun twoNeutralListsHtml(): String = buildString {
        append("<html><body>")
        append("<section><div class='alpha-list'>")
        repeat(5) { index ->
            append("<article><h2><a href='/alpha/${100 + index}.html'>Alpha article ${index + 1}</a></h2></article>")
        }
        append("</div></section>")
        append("<section><div class='beta-list'>")
        repeat(5) { index ->
            append("<article><h2><a href='/beta/${200 + index}.html'>Beta article ${index + 1}</a></h2></article>")
        }
        append("</div></section>")
        append("</body></html>")
    }

    private fun blockAnchorCardsHtml(): String = buildString {
        append("<html><body><main><div class='row latest-posts'>")
        repeat(5) { index ->
            append("<div class='col'>")
            append("<a class='card img-visible' href='/blog/2026/08/block-card-${index + 1}/'>")
            append("<div class='card__header'><h4>整卡链接文章 ${index + 1}</h4></div>")
            append("<div class='card__body'><p>")
            repeat(30) { append("这是一段用于模拟真实博客卡片的较长摘要内容。") }
            append("</p></div></a></div>")
        }
        append("</div></main></body></html>")
    }

    /** 模拟新闻卡片同时带有品牌主页链接，且品牌链接在 DOM 中先于真正文章标题。 */
    private fun articleCardsWithBrandLinksHtml(): String = buildString {
        append("<html><body><main><section class='latest-news'><div class='article-list'>")
        repeat(6) { index ->
            append("<article class='news-card'>")
            append("<a class='brand' href='/brand/${2000 + index}.html'>品牌名称 ${index + 1}</a>")
            append("<h2><a class='article-title' href='/archives/${9000 + index}.html'>真正的新闻文章标题 ${index + 1}</a></h2>")
            append("<time>2026-08-07</time>")
            append("</article>")
        }
        append("</div></section></main></body></html>")
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)) { "Missing test resource: $path" }
            .readText()
}
