package me.ash.reader.infrastructure.json

import java.util.Date
import java.util.concurrent.TimeUnit
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 使用真实 GitHub Issues API 验证一条完整的 JSON 规则。
 * 通过 ORIGREAD_RUN_LIVE_RULE_CONTRACT=true 显式开启，避免日常单测依赖外网。
 */
class LiveJsonRuleContractTest {
    @Test
    fun `github issues rule parses live json list`() {
        assumeTrue(
            "未显式开启真实规则契约测试",
            System.getenv(ENV_RUN).equals("true", ignoreCase = true),
        )

        val url = "https://api.github.com/repos/openai/openai-cookbook/issues?state=open&per_page=5"
        val content = fetch(url)
        val articles = JsonArticleParser().parse(
            content = content,
            rule = rule,
            feed = Feed(
                id = "live-github-issues",
                name = "OpenAI Cookbook Issues",
                groupId = "live-contract",
                accountId = 0,
                url = url,
                sourceType = SourceType.JSON,
            ),
            baseUrl = url,
            fetchedAt = Date(),
        )

        assertTrue("应解析出 5 篇文章，实际为 ${articles.size}", articles.size == 5)
        assertTrue(articles.all { it.title.isNotBlank() })
        assertTrue(articles.all { it.link.startsWith("https://github.com/openai/openai-cookbook/") })
        println(
            "LIVE JSON INPUT=$url OUTPUT_COUNT=${articles.size} " +
                "SAMPLE=${articles.take(3).joinToString { "${it.title} -> ${it.link}" }}",
        )
    }

    private fun fetch(url: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OrigRead-live-validation/1.0")
            .build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.string()
        }
    }

    private companion object {
        const val ENV_RUN = "ORIGREAD_RUN_LIVE_RULE_CONTRACT"

        val rule = JsonRule(
            id = "github-openai-cookbook-issues",
            name = "OpenAI Cookbook Issues",
            hosts = listOf("api.github.com"),
            sourceKind = JsonSourceKind.API,
            endpoint = "https://api.github.com/repos/openai/openai-cookbook/issues?state=open&per_page=5",
            itemsPath = "\$[*]",
            titlePath = "\$.title",
            linkPath = "\$.html_url",
            datePath = "\$.created_at",
            authorPath = "\$.user.login",
            descriptionPath = "\$.body",
            idPath = "\$.id",
            maxItems = 5,
        )
    }
}
