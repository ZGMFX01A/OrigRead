package me.ash.reader.infrastructure.website

import java.net.URI
import java.util.Date
import java.util.concurrent.TimeUnit
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 使用真实 Hacker News 首页验证一条完整的网站规则。
 * 通过 ORIGREAD_RUN_LIVE_RULE_CONTRACT=true 显式开启，避免日常单测依赖外网。
 */
class LiveWebsiteRuleContractTest {
    @Test
    fun `hacker news rule parses live article list`() {
        assumeTrue(
            "未显式开启真实规则契约测试",
            System.getenv(ENV_RUN).equals("true", ignoreCase = true),
        )

        val url = "https://news.ycombinator.com/"
        val html = fetch(url, "text/html")
        val articles = ConfigurableWebsiteParser(rule).parse(
            document = Jsoup.parse(html, url),
            feed = Feed(
                id = "live-hacker-news",
                name = "Hacker News",
                groupId = "live-contract",
                accountId = 0,
                url = url,
                sourceType = SourceType.WEBSITE,
            ),
            fetchedAt = Date(),
        )

        assertTrue("应至少解析出 20 篇文章，实际为 ${articles.size}", articles.size >= 20)
        assertTrue(articles.all { it.title.isNotBlank() })
        assertTrue(articles.all { URI(it.link).host.orEmpty().isNotBlank() })
        println(
            "LIVE WEBSITE INPUT=$url OUTPUT_COUNT=${articles.size} " +
                "SAMPLE=${articles.take(3).joinToString { "${it.title} -> ${it.link}" }}",
        )
    }

    private fun fetch(url: String, accept: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "OrigRead-live-validation/1.0")
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.string()
        }
    }

    private companion object {
        const val ENV_RUN = "ORIGREAD_RUN_LIVE_RULE_CONTRACT"

        val rule = WebsiteRule(
            id = "hacker-news-frontpage",
            name = "Hacker News 首页",
            hosts = listOf("news.ycombinator.com"),
            articleSelectors = listOf("tr.athing.submission"),
            titleSelector = "span.titleline a",
            linkSelector = "span.titleline a",
            maxItems = 30,
        )
    }
}
