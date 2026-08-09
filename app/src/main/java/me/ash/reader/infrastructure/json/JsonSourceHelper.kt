package me.ash.reader.infrastructure.json

import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

data class JsonSourceProbeResult(
    val rule: JsonRule,
    val endpointUrl: String,
    val feed: SyndFeed,
)

/** 执行 JSON/API 规则的网络探测与后续同步。 */
@Singleton
class JsonSourceHelper @Inject constructor(
    private val ruleRepository: JsonRuleRepository,
    private val parser: JsonArticleParser,
    okHttpClient: OkHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun probe(inputUrl: String): JsonSourceProbeResult? = withContext(ioDispatcher) {
        WordPressJsonRuleFactory.createFromEndpoint(inputUrl)?.let { directRule ->
            runCatching { probeRule(inputUrl, directRule) }.getOrNull()?.let {
                return@withContext it
            }
        }

        val importedRuleResult = ruleRepository.findRules(inputUrl).firstNotNullOfOrNull { rule ->
            runCatching {
                probeRule(inputUrl, rule)
            }.getOrNull()
        }
        if (importedRuleResult != null) return@withContext importedRuleResult

        // 标准 WordPress 不要求用户提前导入规则。兼容根目录和子目录安装，
        // 只有真实 REST 响应能够解析出文章时才判定候选成立。
        WordPressJsonRuleFactory.createCandidates(inputUrl).firstNotNullOfOrNull { rule ->
            runCatching { probeRule(inputUrl, rule) }.getOrNull()
        }
    }

    suspend fun fetch(feed: Feed, preDate: Date = Date()): FeedWithArticle = withContext(ioDispatcher) {
        val rule = ruleRepository.findRuleForEndpoint(feed.url)
            ?: WordPressJsonRuleFactory.createFromEndpoint(feed.url)
            ?: error("未找到 ${feed.url} 对应的 JSON 来源规则")
        val articles = executeRule(feed.url, rule, feed, preDate)
        FeedWithArticle(feed.copy(isNotification = feed.isNotification && articles.isNotEmpty()), articles)
    }

    /** 探测阶段和同步阶段共用同一执行入口，保证保存后不会换一种解析方式。 */
    private fun probeRule(inputUrl: String, rule: JsonRule): JsonSourceProbeResult {
        val sourceUrl =
            if (rule.sourceKind == JsonSourceKind.API) {
                ruleRepository.resolveEndpoint(inputUrl, rule.endpoint)
            } else {
                // 内嵌 JSON 属于当前页面；endpoint="." 仅用于保持规则格式统一。
                inputUrl
            }
        val articles = executeRule(sourceUrl, rule, temporaryFeed(rule.name, sourceUrl), Date())
        return JsonSourceProbeResult(rule, sourceUrl, articles.toSyndFeed(rule.name, inputUrl))
    }

    private fun executeRule(
        sourceUrl: String,
        rule: JsonRule,
        feed: Feed,
        fetchedAt: Date,
    ): List<Article> =
        when (rule.sourceKind) {
            JsonSourceKind.API -> requestJson(sourceUrl, rule, feed, fetchedAt)
            JsonSourceKind.NEXT_DATA -> requestEmbedded(sourceUrl, rule, feed, fetchedAt, next = true)
            JsonSourceKind.NUXT_DATA -> requestEmbedded(sourceUrl, rule, feed, fetchedAt, next = false)
        }

    private fun requestJson(endpoint: String, rule: JsonRule, feed: Feed, fetchedAt: Date): List<Article> =
        client.newCall(Request.Builder().url(endpoint).build()).execute().use { response ->
            check(response.isSuccessful) { "JSON API 请求失败：HTTP ${response.code}" }
            parser.parse(response.body.string(), rule, feed, endpoint, fetchedAt)
        }

    /** 请求静态 HTML 后仅读取框架内嵌 JSON，不执行网页 JavaScript。 */
    private fun requestEmbedded(
        pageUrl: String,
        rule: JsonRule,
        feed: Feed,
        fetchedAt: Date,
        next: Boolean,
    ): List<Article> =
        client.newCall(Request.Builder().url(pageUrl).build()).execute().use { response ->
            check(response.isSuccessful) { "网页请求失败：HTTP ${response.code}" }
            val html = response.body.string()
            val jsonContent =
                if (next) EmbeddedJsonExtractor.extractNextData(html, pageUrl)
                else EmbeddedJsonExtractor.extractNuxtData(html, pageUrl)
            require(!jsonContent.isNullOrBlank()) { "网页中未找到对应的内嵌 JSON 数据" }
            parser.parse(jsonContent, rule, feed, pageUrl, fetchedAt)
        }

    private fun temporaryFeed(name: String, endpoint: String) = Feed(
        id = "json-probe-${UUID.randomUUID()}", name = name, url = endpoint,
        groupId = "", accountId = 0, sourceType = SourceType.JSON,
    )

    private fun List<Article>.toSyndFeed(name: String, sourceUrl: String): SyndFeed =
        SyndFeedImpl().apply {
            feedType = "json"
            title = name
            link = sourceUrl
            entries = this@toSyndFeed.map { article ->
                SyndEntryImpl().apply {
                    title = article.title
                    link = article.link
                    author = article.author
                    publishedDate = article.date
                    description = SyndContentImpl().apply { value = article.rawDescription }
                }
            }
        }
}
