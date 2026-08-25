package me.ash.reader.llm.search

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextType

/**
 * P5-A Chat Search Router。
 *
 * 只路由 Dedicated Search Provider。模型原生联网能力由对应 Provider 自身处理，MCP Search Tool 继续作为
 * 普通 MCP Tool 走 Tool Calling 链，不在这里做跨协议统一路由。
 * AUTO 故意保守，只在用户明确表达“最新/当前/联网/搜索”等时效或搜索意图时触发，避免无意义消耗搜索额度。
 */
@Singleton
class WebSearchRouter @Inject constructor(
    private val repository: WebSearchRepository,
    private val service: WebSearchService,
) {
    suspend fun searchIfNeeded(
        enabled: Boolean,
        mode: WebSearchMode,
        userInput: String,
        articleTitle: String?,
    ): WebSearchResponse? {
        if (!enabled || mode == WebSearchMode.OFF) return null
        val required = mode == WebSearchMode.FORCE
        if (!required && !shouldAutoSearch(userInput)) return null

        if (repository.configuredProviders().isEmpty()) {
            if (required) throw WebSearchException("尚未配置可用的 Web Search Provider")
            return null
        }

        val request =
            WebSearchRequest(
                query = buildSearchQuery(articleTitle, userInput),
                maxResults = DEFAULT_CHAT_SEARCH_RESULTS,
                includeContent = false,
            )
        return if (required) {
            service.search(request)
        } else {
            // AUTO 搜索失败时继续使用文章上下文回答；FORCE 才把搜索失败暴露为本轮错误。
            runCatching { service.search(request) }.getOrNull()
        }
    }
}

/** AUTO 模式的保守联网意图判定；只识别明确时效/搜索表达，不尝试做通用自然语言分类。 */
internal fun shouldAutoSearch(userInput: String): Boolean {
    val normalized = userInput.trim().lowercase()
    if (normalized.isBlank()) return false
    return AUTO_SEARCH_MARKERS.any(normalized::contains)
}

/** 将“这件事后来怎样”等指代问题和当前文章标题组合为可独立检索的 query。 */
internal fun buildSearchQuery(
    articleTitle: String?,
    userInput: String,
): String {
    val title = articleTitle.orEmpty().trim()
    val input = userInput.trim()
    return when {
        title.isBlank() -> input
        input.contains(title, ignoreCase = true) -> input
        else -> "$title — $input"
    }.take(MAX_SEARCH_QUERY_LENGTH)
}

/** 将外部搜索资料转换为明确的 reference-data Context，而不是 system instructions。 */
internal fun WebSearchResponse.toContextItems(): List<LlmContextItem> =
    results.mapIndexed { index, result ->
        val content =
            buildString {
                result.publishedAt?.takeIf(String::isNotBlank)?.let { append("Published: $it\n") }
                result.snippet.takeIf(String::isNotBlank)?.let { append(it) }
                result.content?.takeIf(String::isNotBlank)?.let {
                    if (isNotEmpty()) append("\n\n")
                    append(it)
                }
            }
        LlmContextItem(
            id = "web-search:${providerId}:${index + 1}:${result.url.hashCode().toUInt()}",
            type = LlmContextType.WEB_SEARCH_RESULT,
            title = result.title,
            sourceId = result.url,
            content = content.ifBlank { result.url },
            priority = SEARCH_CONTEXT_PRIORITY - index,
        )
    }

private val AUTO_SEARCH_MARKERS =
    listOf(
        "最新",
        "当前",
        "目前",
        "今天",
        "最近",
        "现在",
        "后来",
        "后续",
        "进展",
        "更新",
        "联网",
        "网上",
        "搜索",
        "搜一下",
        "查一下",
        "查最新",
        "截至",
        "latest",
        "current",
        "today",
        "recent",
        "recently",
        "right now",
        "what happened since",
        "follow-up",
        "update",
        "search the web",
        "search online",
        "look up",
    )

private const val DEFAULT_CHAT_SEARCH_RESULTS = 5
private const val MAX_SEARCH_QUERY_LENGTH = 500
// 搜索资料是阅读辅助：排在摘要(130)/当前译文(120)之后、长原文(100)之前。
private const val SEARCH_CONTEXT_PRIORITY = 110

