package me.ash.reader.infrastructure.website

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndImageImpl
import java.net.URI
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.net.HttpTextDecoder
import me.ash.reader.infrastructure.net.HttpTextKind
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/** 无人工规则的网站超过该 HTML 字符数时不再执行自动 DOM 识别。 */
private const val MAX_AUTOMATIC_HTML_CHARS = 750_000

/** 页面超过自动识别资源上限。 */
class WebsitePageTooComplexException : IllegalStateException()

/**
 * 普通网站来源探测器，用于读取站点名称和图标等基础元数据。
 */
class WebsiteHelper @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val ruleRepository: WebsiteRuleRepository,
    private val preferenceRepository: WebsiteParsePreferenceRepository,
    private val dynamicHtmlRenderer: DynamicWebsiteHtmlRenderer,
    private val articleWebSessionManager: ArticleWebSessionManager,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** 保存当前同步进程中每个来源实际选中的规则，保证后续清理使用同一解析器。 */
    private val selectedRuleIds = ConcurrentHashMap<String, String>()

    /** 请求静态 HTML 并执行完整网站候选解析，只有真实文章列表才能进入添加来源竞争。 */
    suspend fun inspect(url: String, fetchedAt: Date = Date()): SyndFeed = withContext(ioDispatcher) {
        val request = websiteRequest(url)
        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "网站请求失败：HTTP ${response.code}" }
            val html = decodeHtmlResponse(response)
            buildInspectableFeed(
                sourceUrl = url,
                documentBaseUrl = response.request.url.toString(),
                html = html,
                fetchedAt = fetchedAt,
            )
        }
    }

    /** 使用受限 WebView 渲染页面后，严格候选失败时允许安全低置信候选作为最终兜底。 */
    suspend fun inspectDynamic(url: String, fetchedAt: Date = Date()): SyndFeed {
        val rendered = dynamicHtmlRenderer.render(url, articleWebSessionManager.desktopHttpUserAgent)
        return withContext(ioDispatcher) {
            buildInspectableFeed(
                sourceUrl = url,
                documentBaseUrl = rendered.finalUrl,
                html = rendered.html,
                fetchedAt = fetchedAt,
                allowLowConfidenceFallback = true,
            )
        }
    }

    /** 保存网站来源是否需要动态渲染，不为此增加数据库字段和迁移。 */
    fun setDynamicRenderingEnabled(feedId: String, enabled: Boolean) {
        preferenceRepository.setDynamicRenderingEnabled(feedId, enabled)
    }

    private data class CandidateBatch(
        val candidates: List<WebsiteParseCandidate>,
        val automaticFullScan: Boolean,
    )

    private data class CandidateSelection(
        val candidate: WebsiteParseCandidate,
        val batch: CandidateBatch,
    )

    /**
     * 人工规则可以处理较大的页面；仅对需要自动 DOM 识别的页面执行硬阈值。
     * 检查发生在 Jsoup.parse() 之前，避免超大 HTML 构建完整 DOM 后才发现超限。
     */
    private fun ensureAutomaticParsingAllowed(feed: Feed, html: String) {
        val hasManualRule = ruleRepository.findRules(feed.url).isNotEmpty()
        val hasCachedAutomaticRule = preferenceRepository.get(feed.id)
            ?.cachedAutomaticRule
            ?.let(AutomaticWebsiteListDetector::isReusableRule) == true
        if (!hasManualRule && !hasCachedAutomaticRule && html.length > MAX_AUTOMATIC_HTML_CHARS) {
            throw WebsitePageTooComplexException()
        }
    }

    /** 抓取一次页面并返回全部规则的评分结果，供用户手动比较解析方式。 */
    suspend fun evaluateCandidates(feed: Feed, fetchedAt: Date = Date()): List<WebsiteParseCandidate> =
        withContext(ioDispatcher) {
            val request = websiteRequest(feed.url)
            okHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "网站请求失败：HTTP ${response.code}" }
                val html = decodeHtmlResponse(response)
                ensureAutomaticParsingAllowed(feed, html)
                val document = Jsoup.parse(html, feed.url)
                buildCandidateBatch(
                    feed = feed,
                    document = document,
                    fetchedAt = fetchedAt,
                    forceAutomaticFullScan = true,
                ).candidates
                    .sortedWith(
                        compareByDescending<WebsiteParseCandidate> { it.diagnostics.accepted }
                            .thenByDescending { it.diagnostics.rankingScore }
                    )
            }
        }

    /** 返回指定来源保存的解析偏好。 */
    fun getParsePreference(feedId: String): WebsiteParsePreference? = preferenceRepository.get(feedId)

    /** 按规则 id 返回当前实际生效的规则名称。 */
    fun getRuleName(ruleId: String?): String? =
        when {
            ruleId == null -> null
            ruleId.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX) -> "Smart detection"
            else -> ruleRepository.findRuleById(ruleId)?.name
        }

    /** 返回来源域名下的全部已配置规则，供来源设置展示启用/停用状态。 */
    fun getConfiguredRules(url: String): List<WebsiteRule> = ruleRepository.findConfiguredRules(url)

    /** 从来源设置中切换固定规则的启用状态。 */
    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        ruleRepository.setEnabled(ruleId, enabled)
    }

    /** 固定指定规则；传入 null 恢复自动选择。 */
    fun setPreferredRule(feedId: String, ruleId: String?, ruleName: String? = null) {
        preferenceRepository.setPreferredRule(feedId, ruleId, ruleName)
    }

    /** 判断指定网址当前是否存在已启用的解析规则。 */
    fun hasRule(url: String): Boolean = ruleRepository.findRules(url).isNotEmpty()

    /**
     * 抓取并解析普通网站文章列表。
     */
    suspend fun fetchArticles(feed: Feed, fetchedAt: Date = Date()): List<Article> =
        if (preferenceRepository.get(feed.id)?.dynamicRenderingEnabled == true) {
            val rendered =
                dynamicHtmlRenderer.render(feed.url, articleWebSessionManager.desktopHttpUserAgent)
            withContext(ioDispatcher) {
                parseAndRecordSelection(
                    feed = feed,
                    document = Jsoup.parse(rendered.html, rendered.finalUrl),
                    fetchedAt = fetchedAt,
                    allowLowConfidenceFallback = true,
                )
            }
        } else {
            withContext(ioDispatcher) {
                val request = websiteRequest(feed.url)
                okHttpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "网站请求失败：HTTP ${response.code}" }
                    val html = decodeHtmlResponse(response)
                    ensureAutomaticParsingAllowed(feed, html)
                    parseAndRecordSelection(
                        feed = feed,
                        document = Jsoup.parse(html, response.request.url.toString()),
                        fetchedAt = fetchedAt,
                    )
                }
            }
        }

    /** 将当前页面解析结果记录为来源级规则、稳定性历史和最近选择。 */
    private fun parseAndRecordSelection(
        feed: Feed,
        document: org.jsoup.nodes.Document,
        fetchedAt: Date,
        allowLowConfidenceFallback: Boolean = false,
    ): List<Article> {
        val selection =
            selectBestCandidate(
                feed = feed,
                document = document,
                fetchedAt = fetchedAt,
                allowLowConfidenceFallback = allowLowConfidenceFallback,
            )
        val candidate = selection.candidate
        selectedRuleIds[feed.id] = candidate.rule.id
        if (AutomaticWebsiteListDetector.isReusableRule(candidate.rule)) {
            val cachedRuleId = preferenceRepository.get(feed.id)?.cachedAutomaticRule?.id
            if (cachedRuleId != candidate.rule.id) {
                preferenceRepository.saveAutomaticRule(feed.id, candidate.rule)
            }
            preferenceRepository.recordAutomaticSelection(
                feedId = feed.id,
                selectedRuleId = candidate.rule.id,
                observedRuleIds = selection.batch.candidates.mapTo(linkedSetOf()) { it.rule.id },
                fullScan = selection.batch.automaticFullScan,
                observedAt = fetchedAt.time,
            )
        }
        preferenceRepository.saveLastSelection(feed.id, candidate)
        return candidate.articles
    }

    /** 将网站解析候选转换为统一来源健康评分可消费的 SyndFeed。 */
    private fun buildInspectableFeed(
        sourceUrl: String,
        documentBaseUrl: String,
        html: String,
        fetchedAt: Date,
        allowLowConfidenceFallback: Boolean = false,
    ): SyndFeed {
        val probeFeed =
            Feed(
                id = "website-probe:${sourceUrl.hashCode().toUInt().toString(16)}",
                name = "Website Probe",
                url = sourceUrl,
                groupId = "website-probe",
                accountId = 0,
                sourceType = SourceType.WEBSITE,
            )
        ensureAutomaticParsingAllowed(probeFeed, html)
        val document = Jsoup.parse(html, documentBaseUrl)
        val selection =
            try {
                selectBestCandidate(
                    feed = probeFeed,
                    document = document,
                    fetchedAt = fetchedAt,
                    forceAutomaticFullScan = true,
                    allowLowConfidenceFallback = allowLowConfidenceFallback,
                )
            } catch (error: IllegalStateException) {
                if (!allowLowConfidenceFallback || !error.message.orEmpty().startsWith("当前网站的解析规则均未通过健康检查：")) {
                    throw error
                }
                // WebView 本身已经成功渲染，只是当前 DOM 没有可靠文章列表。保留一个空的动态
                // 兜底 Feed 交给订阅页展示风险提示，让用户自行决定是否继续添加。
                null
            }
        val iconUrl =
            document
                .selectFirst("link[rel~=(?i)^(shortcut )?icon$]")
                ?.absUrl("href")
                ?.takeIf { it.isNotBlank() }

        return SyndFeedImpl().apply {
            feedType = "website"
            title = document.title().ifBlank { URI(sourceUrl).host ?: document.location() }
            link = sourceUrl
            description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
            entries = selection?.candidate?.articles.orEmpty().map { article ->
                SyndEntryImpl().apply {
                    this.title = article.title
                    this.link = article.link
                    this.author = article.author
                    this.publishedDate = article.date
                    this.description = SyndContentImpl().apply { value = article.rawDescription }
                }
            }
            if (iconUrl != null) {
                icon = SyndImageImpl().apply { this.url = iconUrl }
            }
        }
    }

    /** 按站点解析规则识别本次刷新后应清理的误收文章。 */
    fun findObsoleteArticleIds(
        feed: Feed,
        existingArticles: List<Article>,
        fetchedArticles: List<Article>,
    ): List<String> =
        findSelectedParser(feed).findObsoleteArticleIds(existingArticles, fetchedArticles)

    /** 使用当前规则抓取指定网址，并返回解析出的文章数量。 */
    suspend fun testRule(url: String): Int = withContext(ioDispatcher) {
        val request = websiteRequest(url)
        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "网站请求失败：HTTP ${response.code}" }
            val document = Jsoup.parse(decodeHtmlResponse(response), url)
            selectBestCandidate(
                document = document,
                feed = Feed(
                    id = "rule-test",
                    name = "Rule Test",
                    url = url,
                    groupId = "rule-test",
                    accountId = 0,
                    sourceType = SourceType.WEBSITE,
                ),
                fetchedAt = Date(),
                forceAutomaticFullScan = true,
            ).candidate.articles.size
        }
    }

    /**
     * 网站 HTML 本质上是浏览器页面。使用 OrigRead/版本号 作为 UA 会被部分站点直接识别为机器人并返回 418/403。
     * 这里只复用设备 WebView 的浏览器风格 UA，不携带用户 Cookie，避免把登录态扩散到后台来源同步。
     */
    private fun websiteRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", articleWebSessionManager.desktopHttpUserAgent)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            .header("Upgrade-Insecure-Requests", "1")
            .build()

    /** 静态网页统一按 HTTP charset / HTML meta / BOM 解码，避免非 UTF-8 站点乱码。 */
    private fun decodeHtmlResponse(response: Response): String =
        HttpTextDecoder.decode(
            bytes = response.body.bytes(),
            contentType = response.header("Content-Type"),
            kind = HttpTextKind.HTML,
        )

    /** 对同一页面执行全部匹配规则，并选择通过健康检查且得分最高的结果。 */
    private fun selectBestCandidate(
        feed: Feed,
        document: org.jsoup.nodes.Document,
        fetchedAt: Date,
        forceAutomaticFullScan: Boolean = false,
        allowLowConfidenceFallback: Boolean = false,
    ): CandidateSelection {
        val batch =
            buildCandidateBatch(
                feed = feed,
                document = document,
                fetchedAt = fetchedAt,
                forceAutomaticFullScan = forceAutomaticFullScan,
                includeRejectedAutomatic = allowLowConfidenceFallback,
            )
        val candidates = batch.candidates
        val acceptedCandidates = candidates.filter { it.diagnostics.accepted }
        val preferredRuleId = preferenceRepository.get(feed.id)?.preferredRuleId
        val selected = acceptedCandidates.firstOrNull { it.rule.id == preferredRuleId }
            ?: acceptedCandidates.maxByOrNull { it.diagnostics.rankingScore }
            ?: candidates
                .filter { allowLowConfidenceFallback && WebsiteCandidateScorer.isSafeDynamicFallback(it.diagnostics) }
                .maxByOrNull { it.diagnostics.rankingScore }
            ?: error("当前网站的解析规则均未通过健康检查：${feed.url}")
        return CandidateSelection(candidate = selected, batch = batch)
    }

    /** 对当前页面执行全部匹配规则，解析失败的规则会转为失败候选而不是中断整体流程。 */
    private fun buildCandidateBatch(
        feed: Feed,
        document: org.jsoup.nodes.Document,
        fetchedAt: Date,
        forceAutomaticFullScan: Boolean = false,
        includeRejectedAutomatic: Boolean = false,
    ): CandidateBatch {
        val rules = ruleRepository.findRules(feed.url)
        if (rules.isNotEmpty()) {
            return CandidateBatch(
                candidates = rules.map { rule -> parseRuleCandidate(rule, document, feed, fetchedAt) },
                automaticFullScan = false,
            )
        }

        val preference = preferenceRepository.get(feed.id)
        val cachedRule = preference?.cachedAutomaticRule
        if (cachedRule != null) {
            if (AutomaticWebsiteListDetector.isReusableRule(cachedRule)) {
                val cachedCandidate = parseRuleCandidate(cachedRule, document, feed, fetchedAt, preference)
                if (
                    cachedCandidate.diagnostics.accepted &&
                    !forceAutomaticFullScan &&
                    !AutomaticRuleStabilityScorer.shouldRunFullScan(preference)
                ) {
                    return CandidateBatch(
                        candidates = listOf(cachedCandidate),
                        automaticFullScan = false,
                    )
                }
                if (cachedCandidate.diagnostics.accepted) {
                    val detected =
                        detectAutomaticCandidates(
                            document = document,
                            feed = feed,
                            fetchedAt = fetchedAt,
                            preference = preference,
                            includeRejected = includeRejectedAutomatic,
                        )
                    return CandidateBatch(
                        candidates = (detected + cachedCandidate).distinctBy { it.rule.id },
                        automaticFullScan = true,
                    )
                }
            }
            // 缓存规则格式过期、选择器失效或内容质量下降时，当次刷新立即重新分析。
            preferenceRepository.clearAutomaticRule(feed.id)
        }

        return CandidateBatch(
            candidates =
                detectAutomaticCandidates(
                    document = document,
                    feed = feed,
                    fetchedAt = fetchedAt,
                    preference = preference,
                    includeRejected = includeRejectedAutomatic,
                ),
            automaticFullScan = true,
        )
    }

    /** 执行完整自动 DOM 检测，并在截断候选前应用来源级历史稳定性权重。 */
    private fun detectAutomaticCandidates(
        document: org.jsoup.nodes.Document,
        feed: Feed,
        fetchedAt: Date,
        preference: WebsiteParsePreference?,
        includeRejected: Boolean = false,
    ): List<WebsiteParseCandidate> =
        AutomaticWebsiteListDetector.detect(
            document = document,
            feed = feed,
            fetchedAt = fetchedAt,
            historyScoreProvider = { ruleId -> AutomaticRuleStabilityScorer.score(preference, ruleId) },
            includeRejected = includeRejected,
        )

    /** 统一执行固定规则与缓存自动规则，并将异常转换为失败候选。 */
    private fun parseRuleCandidate(
        rule: WebsiteRule,
        document: org.jsoup.nodes.Document,
        feed: Feed,
        fetchedAt: Date,
        preference: WebsiteParsePreference? = null,
    ): WebsiteParseCandidate =
        runCatching {
            val articles = ConfigurableWebsiteParser(rule).parse(document, feed, fetchedAt)
            val diagnostics = WebsiteCandidateScorer.score(articles, fetchedAt.time).copy(
                regionScore = rule.automaticRegionScore,
                historyScore =
                    if (rule.id.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX)) {
                        AutomaticRuleStabilityScorer.score(preference, rule.id)
                    } else {
                        0
                    },
            )
            WebsiteParseCandidate(
                rule = rule,
                articles = articles,
                diagnostics = diagnostics,
            )
        }.getOrElse { error ->
            WebsiteParseCandidate(
                rule = rule,
                articles = emptyList(),
                diagnostics = WebsiteCandidateScorer.rejected(error.message ?: "Parsing failed"),
            )
        }

    /** 优先使用本次抓取选中的规则，缺失时再回退到域名匹配的第一条。 */
    private fun findSelectedParser(feed: Feed): WebsiteParser {
        val selectedRuleId = selectedRuleIds.remove(feed.id)
        if (selectedRuleId?.startsWith(AutomaticWebsiteListDetector.RULE_ID_PREFIX) == true) {
            return NoCleanupWebsiteParser
        }
        val selectedRule = selectedRuleId?.let(ruleRepository::findRuleById)
        val rule = selectedRule ?: ruleRepository.findRule(feed.url)
        return rule?.let(::ConfigurableWebsiteParser)
            ?: NoCleanupWebsiteParser
    }

    /** 自动识别候选不执行范围清理，避免启发式结果变化时误删文章。 */
    private object NoCleanupWebsiteParser : WebsiteParser {
        override fun supports(url: String): Boolean = true

        override fun parse(
            document: org.jsoup.nodes.Document,
            feed: Feed,
            fetchedAt: Date,
        ): List<Article> = emptyList()

        override fun findObsoleteArticleIds(
            existingArticles: List<Article>,
            fetchedArticles: List<Article>,
        ): List<String> = emptyList()
    }
}
