package me.ash.reader.infrastructure.website

import com.rometools.rome.feed.synd.SyndFeed
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.net.HttpTextDecoder
import me.ash.reader.infrastructure.net.HttpTextKind
import me.ash.reader.infrastructure.net.useCancellable
import me.ash.reader.infrastructure.network.readBoundedBytes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/** 无人工规则或可复用缓存时，沿用已有自动 DOM 识别的资源上限。 */
private const val MAX_AUTOMATIC_HTML_CHARS = 750_000
private const val MAX_WEBSITE_HTML_BYTES = 10L * 1024L * 1024L

/** 页面超过自动识别资源上限。 */
class WebsitePageTooComplexException : IllegalStateException()

/** 网站抓取入口；候选选择与 Feed 转换分别由同包的纯解析流程负责。 */
class WebsiteHelper @Inject constructor(
    private val okHttpClient: OkHttpClient,
    internal val ruleRepository: WebsiteRuleRepository,
    internal val preferenceRepository: WebsiteParsePreferenceRepository,
    private val dynamicHtmlRenderer: DynamicWebsiteHtmlRenderer,
    private val articleWebSessionManager: ArticleWebSessionManager,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** 静态请求的取消同时覆盖响应头和响应体，成功后再参与来源候选竞争。 */
    suspend fun inspect(url: String, fetchedAt: Date = Date()): SyndFeed = withContext(ioDispatcher) {
        requestHtml(url) { html, finalUrl ->
            buildInspectableFeed(WebsiteProbePage(url, finalUrl, html, fetchedAt))
        }
    }

    /** 保留现有动态页面探测策略，渲染完成后由真实 DOM 结果决定候选质量。 */
    suspend fun inspectDynamic(url: String, fetchedAt: Date = Date()): SyndFeed {
        val rendered = dynamicHtmlRenderer.render(url, articleWebSessionManager.desktopHttpUserAgent)
        return withContext(ioDispatcher) {
            buildInspectableFeed(WebsiteProbePage(url, rendered.finalUrl, rendered.html, fetchedAt, true))
        }
    }

    fun setDynamicRenderingEnabled(feedId: String, enabled: Boolean) {
        preferenceRepository.setDynamicRenderingEnabled(feedId, enabled)
    }

    /** 大页面检查在构建 DOM 前执行，避免无规则页面消耗大量内存。 */
    internal fun ensureAutomaticParsingAllowed(feed: Feed, html: String) {
        val hasManualRule = ruleRepository.findRules(feed.url).isNotEmpty()
        val hasCachedAutomaticRule = preferenceRepository.get(feed.id)?.cachedAutomaticRule
            ?.let(AutomaticWebsiteListDetector::isReusableRule) == true
        if (!hasManualRule && !hasCachedAutomaticRule && html.length > MAX_AUTOMATIC_HTML_CHARS) {
            throw WebsitePageTooComplexException()
        }
    }

    /** 设置页按真实页面展示候选评分，便于重新选择已经失效的固定规则。 */
    suspend fun evaluateCandidates(feed: Feed, fetchedAt: Date = Date()): List<WebsiteParseCandidate> =
        withContext(ioDispatcher) {
            requestHtml(feed.url) { html, finalUrl ->
                ensureAutomaticParsingAllowed(feed, html)
                buildCandidateBatch(
                    WebsiteCandidateContext(feed, Jsoup.parse(html, finalUrl), fetchedAt, forceFullScan = true)
                ).candidates.sortedWith(
                    compareByDescending<WebsiteParseCandidate> { it.diagnostics.accepted }
                        .thenByDescending { it.diagnostics.rankingScore }
                )
            }
        }

    fun getParsePreference(feedId: String): WebsiteParsePreference? = preferenceRepository.get(feedId)

    /** 自动规则名称沿用设置页约定，人工规则按当前配置查找。 */
    fun getRuleName(ruleId: String?): String? = when {
        ruleId == null -> null
        ruleId.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX) -> "Smart detection"
        else -> ruleRepository.findRuleById(ruleId)?.name
    }

    fun getConfiguredRules(url: String): List<WebsiteRule> = ruleRepository.findConfiguredRules(url)

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        ruleRepository.setEnabled(ruleId, enabled)
    }

    fun setPreferredRule(feedId: String, ruleId: String?, ruleName: String? = null) {
        preferenceRepository.setPreferredRule(feedId, ruleId, ruleName)
    }

    fun hasRule(url: String): Boolean = ruleRepository.findRules(url).isNotEmpty()

    /** 刷新沿用来源的动态偏好，固定规则失效必须通过正常错误出口暴露。 */
    suspend fun fetchArticles(feed: Feed, fetchedAt: Date = Date()): WebsiteArticleBatch {
        if (preferenceRepository.get(feed.id)?.dynamicRenderingEnabled == true) {
            val rendered = dynamicHtmlRenderer.render(feed.url, articleWebSessionManager.desktopHttpUserAgent)
            return withContext(ioDispatcher) {
                parseAndRecordSelection(
                    WebsiteCandidateContext(
                        feed, Jsoup.parse(rendered.html, rendered.finalUrl), fetchedAt,
                        allowLowConfidence = true,
                    )
                )
            }
        }
        return withContext(ioDispatcher) {
            requestHtml(feed.url) { html, finalUrl ->
                ensureAutomaticParsingAllowed(feed, html)
                parseAndRecordSelection(WebsiteCandidateContext(feed, Jsoup.parse(html, finalUrl), fetchedAt))
            }
        }
    }

    /** 规则管理页验证使用同一候选选择链路与最终跳转地址。 */
    suspend fun testRule(url: String): Int = withContext(ioDispatcher) {
        requestHtml(url) { html, finalUrl ->
            val feed = Feed(
                id = "rule-test", name = "Rule Test", url = url,
                groupId = "rule-test", accountId = 0, sourceType = SourceType.WEBSITE,
            )
            selectBestCandidate(
                WebsiteCandidateContext(feed, Jsoup.parse(html, finalUrl), Date(), forceFullScan = true)
            ).candidate.articles.size
        }
    }

    /** HTML 使用浏览器 UA；后台同步不携带 WebView 登录 Cookie。 */
    private fun websiteRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", articleWebSessionManager.desktopHttpUserAgent)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .header("Upgrade-Insecure-Requests", "1")
        .build()

    /** 统一检查 HTTP 状态与字符集，并在 Call 仍受协程管理时消费响应体。 */
    private suspend fun <T> requestHtml(url: String, consume: (String, String) -> T): T =
        okHttpClient.newCall(websiteRequest(url)).useCancellable { response ->
            check(response.isSuccessful) { "网站请求失败：HTTP ${response.code}" }
            consume(decodeHtmlResponse(response), response.request.url.toString())
        }

    /** 静态网页按 HTTP charset、HTML meta 和 BOM 解码。 */
    private fun decodeHtmlResponse(response: Response): String = HttpTextDecoder.decode(
        bytes = response.readBoundedBytes(MAX_WEBSITE_HTML_BYTES),
        contentType = response.header("Content-Type"),
        kind = HttpTextKind.HTML,
    )
}
