package me.ash.reader.infrastructure.website

import java.net.URI
import java.util.Date
import java.util.UUID
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.ui.ext.spacerDollar
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 基于重复 DOM 结构发现新闻列表候选。
 * 该检测器仅使用本地启发式规则，不依赖 AI；输出规则可由来源级缓存复用。
 */
object AutomaticWebsiteListDetector {
    const val RULE_ID_PREFIX = "auto-dom:"
    // v7 将 URL pattern 的链接语义质量纳入候选排序，旧缓存需失效后重新识别。
    const val AUTOMATIC_RULE_VERSION = 7

    private const val MIN_REPEATED_ITEMS = 3
    private const val MAX_REPEATED_ITEMS = 100
    private const val MAX_CANDIDATES = 5
    private const val MAX_CONTAINERS = 400
    private const val MAX_GROUPS = 120
    private const val MAX_ITEMS_PER_GROUP = 30
    private const val MAX_VISITED_ELEMENTS = 1_500
    private const val MAX_CHILDREN_PER_ELEMENT = 80
    private const val MAX_LINKS_PER_ITEM = 40
    private const val MAX_DETECTION_TIME_MS = 1_500L

    /** 导航、页头和页脚中的重复链接通常不是文章列表，直接跳过。 */
    private val ignoredTags = setOf("nav", "header", "footer", "script", "style", "noscript", "svg")

    /** 扫描页面中的重复直接子节点，返回评分后的智能识别候选。 */
    fun detect(
        document: Document,
        feed: Feed,
        fetchedAt: Date,
        historyScoreProvider: (String) -> Int = { 0 },
    ): List<WebsiteParseCandidate> {
        val host = runCatching { URI(feed.url).host.orEmpty() }.getOrDefault("")
        val seenLinks = hashSetOf<String>()
        val deadline = System.nanoTime() + MAX_DETECTION_TIME_MS * 1_000_000
        val dateExtractor = AutomaticArticleDateExtractor.create(document, fetchedAt)

        return boundedElements(document.body(), deadline)
            .takeWhile { System.nanoTime() < deadline }
            .filter(::isEligibleContainer)
            .take(MAX_CONTAINERS)
            .flatMap { container -> repeatedGroups(container).asSequence() }
            .takeWhile { System.nanoTime() < deadline }
            .take(MAX_GROUPS)
            .flatMap { group ->
                buildArticleClusters(group.items, feed, fetchedAt, host, dateExtractor, deadline)
                    .asSequence()
                    .mapNotNull { cluster ->
                        val articles = cluster.articles
                        if (articles.size < MIN_REPEATED_ITEMS) return@mapNotNull null

                        val uniqueKey = articles.joinToString("|") { it.link }
                        if (!seenLinks.add(uniqueKey)) return@mapNotNull null

                        // 候选 id 使用 DOM 签名与稳定 URL 模式生成，不再依赖本页具体文章编号，
                        // 避免刷新页面后同一候选被误认为全新规则。
                        if (System.nanoTime() >= deadline) return@mapNotNull null
                        val candidateIdSource = "${group.signature}|${cluster.pattern.key}"
                        val ruleId = "$RULE_ID_PREFIX${host}:${candidateIdSource.hashCode().toUInt().toString(16)}"
                        val regionScore = AutomaticWebsiteRegionScorer.score(group.container)
                        val rule = buildReusableRule(
                            document = document,
                            group = group,
                            cluster = cluster,
                            host = host,
                            ruleId = ruleId,
                            fetchedAt = fetchedAt,
                            deadline = deadline,
                            regionScore = regionScore.adjustment,
                        )
                            ?: return@mapNotNull null

                        WebsiteParseCandidate(
                            rule = rule,
                            articles = articles,
                            diagnostics = WebsiteCandidateScorer.score(articles, fetchedAt.time).copy(
                                linkQualityScore = cluster.linkQualityScore,
                                regionScore = regionScore.adjustment,
                                historyScore = historyScoreProvider(ruleId),
                            ),
                        )
                    }
            }
            .filter { it.diagnostics.accepted }
            .sortedByDescending { it.diagnostics.rankingScore }
            .distinctBy { it.rule.id }
            .take(MAX_CANDIDATES)
            .toList()
    }

    /**
     * URL pattern 聚类后仍保留早期“每张卡优先选择最像文章标题的链接”原则。
     * 品牌、作者、标签等元数据链接即使数量、日期、唯一性都与文章一致，
     * 只要在同一卡片中明显弱于标题链接，就会在最终候选竞争中被降权。
     */
    private fun calculateLinkQualityScore(
        selectedItems: List<SelectedItem>,
        itemCandidates: List<ItemLinkCandidates>,
    ): Int {
        if (selectedItems.isEmpty()) return 0
        val bestScoreByItem = itemCandidates.associate { candidate ->
            candidate.item to (candidate.links.maxOfOrNull { it.score } ?: 0)
        }
        val averageGap = selectedItems.sumOf { selected ->
            selected.link.score - (bestScoreByItem[selected.item] ?: selected.link.score)
        } / selectedItems.size
        return averageGap.coerceIn(-60, 0)
    }

    /** 判断缓存中的自动规则是否仍符合当前可执行格式。 */
    fun isReusableRule(rule: WebsiteRule): Boolean =
        rule.id.startsWith(RULE_ID_PREFIX) &&
            rule.version == AUTOMATIC_RULE_VERSION &&
            rule.articleSelectors.any(String::isNotBlank) &&
            rule.titleSelector.isNotBlank() &&
            rule.linkSelector.isNotBlank() &&
            !rule.automaticUrlPattern.isNullOrBlank()

    /**
     * 使用有界深度优先遍历替代 getAllElements()，避免超大 DOM 在进入时间检查前
     * 就一次性构建完整元素集合，导致候选分析无法及时停止。
     */
    private fun boundedElements(root: Element, deadline: Long): Sequence<Element> = sequence {
        val stack = ArrayDeque<Element>()
        root.children().asReversed().take(MAX_CHILDREN_PER_ELEMENT).forEach(stack::addLast)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_VISITED_ELEMENTS && System.nanoTime() < deadline) {
            val element = stack.removeLast()
            visited++
            yield(element)
            element.children()
                .asReversed()
                .take(MAX_CHILDREN_PER_ELEMENT)
                .forEach(stack::addLast)
        }
    }

    /** 仅分析可能承载列表的正文容器，避免对整个 DOM 做无界扫描。 */
    private fun isEligibleContainer(element: Element): Boolean {
        if (element.tagName() in ignoredTags) return false
        if (element.parents().any { it.tagName() in ignoredTags }) return false
        val childCount = element.children().size
        return childCount in MIN_REPEATED_ITEMS..MAX_REPEATED_ITEMS
    }

    /** 按标签和稳定 class 对直接子节点分组，寻找重复列表结构。 */
    private fun repeatedGroups(container: Element): List<RepeatedGroup> =
        container
            .children()
            .groupBy(::elementSignature)
            .asSequence()
            .filter { (_, items) -> items.size in MIN_REPEATED_ITEMS..MAX_REPEATED_ITEMS }
            .map { (signature, items) ->
                RepeatedGroup(
                    signature = "${elementSignature(container)} > $signature",
                    container = container,
                    items = items,
                )
            }
            .toList()

    /** class 仅保留前两个稳定结构值，避免状态、序号和哈希类名拆散同一列表。 */
    private fun elementSignature(element: Element): String {
        val classes =
            element.classNames()
                .asSequence()
                .filterNot(::looksUnstableClass)
                .sorted()
                .take(2)
                .toList()
        return buildString {
            append(element.tagName())
            classes.forEach { append('.').append(it) }
        }
    }

    /**
     * 忽略不代表 DOM 结构的临时 class：
     * 奇偶行、首尾项、当前状态和按位置生成的 item-1/row-2 等值都会随刷新变化。
     */
    private fun looksUnstableClass(value: String): Boolean {
        val normalized = value.lowercase()
        if (normalized.length > 32 || normalized.count(Char::isDigit) > normalized.length / 2) return true
        if (normalized in TRANSIENT_CLASS_NAMES) return true
        if (POSITION_CLASS_REGEX.matches(normalized)) return true
        if (RESPONSIVE_UTILITY_CLASS_REGEX.matches(normalized)) return true
        return STATE_SUFFIX_REGEX.matches(normalized)
    }

    /**
     * 先收集每个重复节点中的候选链接，再按归一化 URL 模式聚类。
     * 只有至少跨三个列表项重复出现的模式才会进入文章候选，减少分类页和外链污染。
     */
    private fun buildArticleClusters(
        items: List<Element>,
        feed: Feed,
        fetchedAt: Date,
        host: String,
        dateExtractor: AutomaticArticleDateExtractor,
        deadline: Long,
    ): List<ArticleCluster> {
        val itemCandidates =
            items.asSequence()
                .take(MAX_ITEMS_PER_GROUP)
                .takeWhile { System.nanoTime() < deadline }
                .map { item ->
                    ItemLinkCandidates(
                        item = item,
                        links = collectLinkCandidates(item, host, deadline),
                    )
                }
                .filter { it.links.isNotEmpty() }
                .toList()

        val patternCounts =
            itemCandidates
                .flatMap { item -> item.links.map { it.pattern.key }.distinct() }
                .groupingBy { it }
                .eachCount()

        return patternCounts.asSequence()
            .filter { (_, count) -> count >= MIN_REPEATED_ITEMS }
            .sortedByDescending { (_, count) -> count }
            .take(5)
            .mapNotNull { (patternKey, _) ->
                val selectedItems =
                    itemCandidates.mapNotNull { itemCandidate ->
                        val link = itemCandidate.links
                            .asSequence()
                            .filter { it.pattern.key == patternKey }
                            .maxByOrNull { it.score }
                            ?: return@mapNotNull null
                        SelectedItem(itemCandidate.item, link)
                    }
                    .distinctBy { it.link.url }
                    .take(50)

                if (selectedItems.size < MIN_REPEATED_ITEMS) return@mapNotNull null
                val pattern = itemCandidates.asSequence()
                    .flatMap { it.links.asSequence() }
                    .first { it.pattern.key == patternKey }
                    .pattern
                ArticleCluster(
                    pattern = pattern,
                    articles = selectedItems.map { selected ->
                        buildArticle(selected.item, selected.link, feed, fetchedAt, dateExtractor)
                    },
                    selectedItems = selectedItems,
                    linkQualityScore = calculateLinkQualityScore(selectedItems, itemCandidates),
                )
            }
            .toList()
    }

    /**
     * 将本次识别到的 DOM 结构固化为可执行规则。
     * 选择器必须在当前文档中重新命中同一 URL 模式，否则放弃缓存该候选。
     */
    private fun buildReusableRule(
        document: Document,
        group: RepeatedGroup,
        cluster: ArticleCluster,
        host: String,
        ruleId: String,
        fetchedAt: Date,
        deadline: Long,
        regionScore: Int,
    ): WebsiteRule? {
        if (System.nanoTime() >= deadline) return null
        val itemSegment = selectorSegment(group.items.first())
        val articleSelector = buildArticleSelector(document, group.container, itemSegment)
            ?.takeIf { selector -> document.select(selector).size >= MIN_REPEATED_ITEMS }
            ?: return null
        val linkSelector = buildLinkSelector(cluster, host) ?: return null
        val titleSelector = buildTitleSelector(cluster, linkSelector) ?: return null
        if (System.nanoTime() >= deadline) return null
        val hasImage = cluster.selectedItems.any { it.item.selectFirst("img") != null }

        val rule = WebsiteRule(
            id = ruleId,
            name = "Smart detection · ${cluster.pattern.key.take(96)}",
            version = AUTOMATIC_RULE_VERSION,
            hosts = listOf(host),
            articleSelectors = listOf(articleSelector),
            titleSelector = titleSelector,
            linkSelector = linkSelector,
            imageSelector = "img".takeIf { hasImage },
            imageAttributes = listOf("data-original", "data-src", "src"),
            automaticUrlPattern = cluster.pattern.key,
            automaticDateExtraction = true,
            automaticRegionScore = regionScore,
            maxItems = 50,
        )

        if (System.nanoTime() >= deadline) return null
        val reparsed = runCatching {
            val sampleFeed = cluster.articles.first()
            val feed = Feed(
                id = sampleFeed.feedId,
                name = "Automatic rule validation",
                url = document.baseUri(),
                groupId = "automatic-rule-validation",
                accountId = sampleFeed.accountId,
            )
            ConfigurableWebsiteParser(rule).parse(document.clone(), feed, fetchedAt)
        }.getOrDefault(emptyList())
        return rule.takeIf {
            reparsed.size >= MIN_REPEATED_ITEMS &&
                reparsed.map(Article::link).toSet().intersect(cluster.articles.map(Article::link).toSet()).size >= MIN_REPEATED_ITEMS
        }
    }

    /** 优先使用唯一 id，其次构造不超过五层的稳定父子选择器。 */
    private fun buildArticleSelector(document: Document, container: Element, itemSegment: String): String? {
        validCssToken(container.id())
            ?.let { id -> "#$id > $itemSegment" }
            ?.takeIf { document.select(it).size >= MIN_REPEATED_ITEMS }
            ?.let { return it }

        val segments = ArrayDeque<String>()
        var current: Element? = container
        repeat(5) {
            val element = current ?: return@repeat
            if (element.tagName() == "body") return@repeat
            segments.addFirst(selectorSegment(element))
            val candidate = segments.joinToString(" > ") + " > $itemSegment"
            if (document.select(candidate).size >= MIN_REPEATED_ITEMS) return candidate
            current = element.parent()
        }

        return runCatching { "${container.cssSelector()} > $itemSegment" }
            .getOrNull()
            ?.takeIf { document.select(it).size >= MIN_REPEATED_ITEMS }
    }

    /** 从重复节点到标题链接生成最短且能稳定命中同类 URL 的相对选择器。 */
    private fun buildLinkSelector(cluster: ArticleCluster, host: String): String? {
        val candidates = linkedSetOf<String>()
        cluster.selectedItems.forEach { selected ->
            addRelativeSelectorCandidates(candidates, selected.item, selected.link.element, includePosition = false)
            addRelativeSelectorCandidates(candidates, selected.item, selected.link.element, includePosition = true)
            candidates += selectorSegment(selected.link.element)
        }
        candidates += "a[href]"

        return candidates.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .filter { selector ->
                cluster.selectedItems.all { selected ->
                    val matched = selected.item.selectFirst(selector) ?: return@all false
                    // 同一张卡片可能同时包含封面、标题和“阅读全文”链接，并且三者 URL 完全相同。
                    // 自动规则必须命中首次评分选出的标题链接本身，不能仅凭 URL 模式相同退化为 a[href]，
                    // 否则缓存复用时 selectFirst() 可能先拿到无文字的封面链接并丢失整条文章。
                    matched === selected.link.element
                }
            }
            .minWithOrNull(compareBy<String> { it.count { char -> char == '>' } }.thenBy(String::length))
    }

    /**
     * 现代卡片流常用外层 a 包住标题、摘要和作者，链接全文可能远超标题长度。
     * 此时链接选择器仍指向外层 a，标题选择器单独指向内部 heading，避免缓存复用后
     * 把整张卡片的全部文本当作文章标题。
     */
    private fun buildTitleSelector(cluster: ArticleCluster, linkSelector: String): String? {
        if (cluster.selectedItems.all { it.link.titleElement === it.link.element }) {
            return linkSelector
        }

        val candidates = linkedSetOf<String>()
        cluster.selectedItems.forEach { selected ->
            addRelativeSelectorCandidates(
                candidates,
                selected.item,
                selected.link.titleElement,
                includePosition = false,
            )
            addRelativeSelectorCandidates(
                candidates,
                selected.item,
                selected.link.titleElement,
                includePosition = true,
            )
            candidates += selectorSegment(selected.link.titleElement)
        }

        return candidates.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .filter { selector ->
                cluster.selectedItems.all { selected ->
                    selected.item.selectFirst(selector) === selected.link.titleElement
                }
            }
            .minWithOrNull(compareBy<String> { it.count { char -> char == '>' } }.thenBy(String::length))
    }

    private fun addRelativeSelectorCandidates(
        output: MutableSet<String>,
        item: Element,
        target: Element,
        includePosition: Boolean,
    ) {
        val segments = mutableListOf<String>()
        var current: Element? = target
        while (current != null && current != item) {
            segments += selectorSegment(current, includePosition)
            current = current.parent()
        }
        if (current != item || segments.isEmpty()) return
        val ordered = segments.asReversed()
        ordered.indices.forEach { start -> output += ordered.drop(start).joinToString(" > ") }
    }

    /** 收集单个列表项中的同站候选链接，跨站广告和推荐链接在此阶段直接排除。 */
    private fun collectLinkCandidates(item: Element, host: String, deadline: Long): List<LinkCandidate> =
        item.select("a[href]")
                .asSequence()
                .take(MAX_LINKS_PER_ITEM)
                .takeWhile { System.nanoTime() < deadline }
                .mapNotNull { link ->
                    val (titleElement, title) = extractLinkTitle(link) ?: return@mapNotNull null
                    val url = link.absUrl("href")
                    if (url.isBlank()) return@mapNotNull null
                    if (ArticleLinkHeuristics.shouldReject(link, title, url)) return@mapNotNull null
                    val pattern = ArticleUrlPatternNormalizer.normalize(url, host) ?: return@mapNotNull null
                    LinkCandidate(
                        element = link,
                        titleElement = titleElement,
                        title = title,
                        url = url,
                        pattern = pattern,
                        score = calculateLinkScore(link, title, url, pattern),
                    )
                }
                .toList()

    /** 优先取卡片链接内部的标题节点，普通文本链接继续使用链接自身文本。 */
    private fun extractLinkTitle(link: Element): Pair<Element, String>? {
        link.selectFirst("h1, h2, h3, h4, h5, h6")?.let { heading ->
            val headingText = heading.text().trim()
            if (headingText.length in 4..200) return heading to headingText
        }
        val linkText = link.text().trim()
        return link.takeIf { linkText.length in 4..200 }?.let { it to linkText }
    }

    /** 将已选中的真实链接转换为文章，URL 模式只参与筛选，不替换最终地址。 */
    private fun buildArticle(
        item: Element,
        anchor: LinkCandidate,
        feed: Feed,
        fetchedAt: Date,
        dateExtractor: AutomaticArticleDateExtractor,
    ): Article {

        val image =
            item.selectFirst("img")?.let { imageElement ->
                sequenceOf("data-original", "data-src", "src")
                    .map { imageElement.absUrl(it) }
                    .firstOrNull { it.isNotBlank() }
            }

        return Article(
            id = feed.accountId.spacerDollar(UUID.randomUUID().toString()),
            date = dateExtractor.extract(item, anchor.url),
            title = anchor.title,
            rawDescription = "",
            shortDescription = "",
            img = image,
            link = anchor.url,
            feedId = feed.id,
            accountId = feed.accountId,
            updateAt = fetchedAt,
        )
    }

    /** 标题标签、较长文本、较深路径和动态文章标识获得更高优先级。 */
    private fun calculateLinkScore(
        element: Element,
        title: String,
        url: String,
        pattern: ArticleUrlPattern,
    ): Int {
        var score = title.length.coerceAtMost(80)
        if (
            element.parents().any { it.tagName() in setOf("h1", "h2", "h3", "h4") } ||
                element.selectFirst("h1, h2, h3, h4") != null
        ) score += 60
        if (element.classNames().any { it.contains("title", true) }) score += 30
        if (url.count { it == '/' } >= 4) score += 20
        score += pattern.pathDepth.coerceAtMost(5) * 5
        score += pattern.dynamicPartCount.coerceAtMost(3) * 15
        return score
    }

    private data class RepeatedGroup(
        val signature: String,
        val container: Element,
        val items: List<Element>,
    )

    private data class LinkCandidate(
        val element: Element,
        val titleElement: Element,
        val title: String,
        val url: String,
        val pattern: ArticleUrlPattern,
        val score: Int,
    )

    private data class ItemLinkCandidates(
        val item: Element,
        val links: List<LinkCandidate>,
    )

    private data class ArticleCluster(
        val pattern: ArticleUrlPattern,
        val articles: List<Article>,
        val selectedItems: List<SelectedItem>,
        val linkQualityScore: Int,
    )

    private data class SelectedItem(
        val item: Element,
        val link: LinkCandidate,
    )

    private fun selectorSegment(element: Element, includePosition: Boolean = false): String {
        val classes = stableClasses(element)
        return buildString {
            append(element.tagName())
            classes.forEach { append('.').append(it) }
            if (includePosition && classes.isEmpty()) {
                val sameTagSiblings = element.parent()?.children()?.filter { it.tagName() == element.tagName() }.orEmpty()
                if (sameTagSiblings.size > 1) append(":nth-of-type(${sameTagSiblings.indexOf(element) + 1})")
            }
        }
    }

    private fun stableClasses(element: Element): List<String> =
        element.classNames()
            .asSequence()
            .filterNot(::looksUnstableClass)
            .mapNotNull(::validCssToken)
            .sorted()
            .take(2)
            .toList()

    private val TRANSIENT_CLASS_NAMES = setOf(
        "odd",
        "even",
        "first",
        "last",
        "active",
        "current",
        "selected",
        "is-active",
        "is-current",
        "is-selected",
        "sticky",
        "pinned",
        "featured",
        "is-sticky",
        "is-pinned",
        "is-featured",
    )

    private val POSITION_CLASS_REGEX = Regex(
        "^(?:item|row|entry|article|post|news|card|index|position|pos|order)[_-]?\\d+$"
    )

    private val STATE_SUFFIX_REGEX = Regex(
        "^.+(?:--|__|-)(?:odd|even|first|last|active|current|selected|sticky|pinned|featured)$"
    )

    /**
     * 响应式断点 utility class 会随模板或设备布局调整，不能固化进来源级选择器。
     * 仅忽略断点前缀加常见布局词，避免把正常业务类名误判为临时 class。
     */
    private val RESPONSIVE_UTILITY_CLASS_REGEX = Regex(
        "^(?:xs|sm|md|lg|xl|xxl)[:_-](?:card|grid|list|column|col|row|compact|wide|stacked|horizontal|vertical|hidden|visible|block|flex).*$"
    )

    private fun validCssToken(value: String): String? =
        value.takeIf { CSS_TOKEN_REGEX.matches(it) }

    private val CSS_TOKEN_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_-]*$")
}
