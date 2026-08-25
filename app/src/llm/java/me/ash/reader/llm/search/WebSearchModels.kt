package me.ash.reader.llm.search

import java.util.UUID

/** Web Search 后端的产品语义类型。 */
enum class WebSearchBackendKind {
    RAW_SEARCH,
    ANSWER_ENGINE,
}

/** 单次 Chat 的联网策略；总开关关闭时无论该值为何都不会搜索。 */
enum class WebSearchMode {
    OFF,
    AUTO,
    FORCE,
}

/** P5-A 首期内置 Dedicated Search Provider。 */
enum class WebSearchProviderKind(
    val defaultDisplayName: String,
    val defaultEndpoint: String,
    val backendKind: WebSearchBackendKind,
    val requiresApiKey: Boolean = true,
) {
    EXA("Exa", "https://api.exa.ai/search", WebSearchBackendKind.RAW_SEARCH),
    TAVILY("Tavily", "https://api.tavily.com/search", WebSearchBackendKind.RAW_SEARCH),
    BRAVE(
        "Brave Search",
        "https://api.search.brave.com/res/v1/web/search",
        WebSearchBackendKind.RAW_SEARCH,
    ),
    PERPLEXITY(
        "Perplexity Search",
        "https://api.perplexity.ai/search",
        WebSearchBackendKind.RAW_SEARCH,
    ),
    LINKUP("Linkup", "https://api.linkup.so/v1/search", WebSearchBackendKind.RAW_SEARCH),
    FIRECRAWL(
        "Firecrawl",
        "https://api.firecrawl.dev/v2/search",
        WebSearchBackendKind.RAW_SEARCH,
    ),
    SEARXNG(
        "SearXNG",
        "",
        WebSearchBackendKind.RAW_SEARCH,
        requiresApiKey = false,
    ),
}

/** 用户保存的一条 Web Search Provider 配置；API Key 由 SecureSecretStore 独立保存。 */
data class WebSearchProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val kind: WebSearchProviderKind,
    val name: String = kind.defaultDisplayName,
    val endpoint: String = kind.defaultEndpoint,
    val enabled: Boolean = true,
)

/** Web Search Provider 列表与默认项。 */
data class WebSearchSettings(
    val providers: List<WebSearchProviderProfile> = emptyList(),
    val defaultProviderId: String? = null,
) {
    fun defaultProvider(): WebSearchProviderProfile? =
        providers.firstOrNull { it.id == defaultProviderId && it.enabled }
            ?: providers.firstOrNull(WebSearchProviderProfile::enabled)
}

/** 单次 Web Search 请求。 */
data class WebSearchRequest(
    val query: String,
    val maxResults: Int = 5,
    val includeContent: Boolean = false,
) {
    init {
        require(query.isNotBlank()) { "Search query 不能为空" }
        require(maxResults in 1..20) { "maxResults 必须在 1..20" }
    }
}

/** 上层 Chat / ContextRef 使用的统一搜索结果。 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String = "",
    val publishedAt: String? = null,
    val source: String? = null,
    val content: String? = null,
)

/** 一次搜索的归一化响应。 */
data class WebSearchResponse(
    val providerId: String,
    val providerName: String,
    val backendKind: WebSearchBackendKind,
    val results: List<WebSearchResult>,
    val answer: String? = null,
)

/**
 * 用户主动执行一次 Search Provider 测活后的结果。
 *
 * 测活使用真实最小搜索请求，因此不仅验证 DNS/TLS，还会同时验证 Endpoint、认证和响应解析链路。
 */
data class WebSearchHealthCheckResult(
    val providerId: String,
    val providerName: String,
    val latencyMs: Long,
    val resultCount: Int,
)

/** Web Search 层对上游暴露的稳定异常。 */
class WebSearchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

