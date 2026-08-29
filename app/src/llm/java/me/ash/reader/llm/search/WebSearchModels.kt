package me.ash.reader.llm.search

import java.util.UUID
import me.ash.reader.infrastructure.ai.AiPerfTrace

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

/**
 * 单次 Assistant 请求的 Dedicated Web Search 状态。
 *
 * 该状态会持久化到对应 Assistant 消息，用于区分“没有搜索必要”“已经触发”“成功”和
 * “AUTO 搜索失败后继续回答”，避免用户只能通过模型回答内容猜测是否真正联网。
 */
enum class WebSearchRequestStatus {
    NOT_NEEDED,
    TRIGGERED,
    SUCCESS,
    FAILED_FALLBACK,
}

/** Router 对一次 Chat 请求做出的纯业务决策；不包含 Provider I/O。 */
data class WebSearchDecision(
    val status: WebSearchRequestStatus,
    val required: Boolean,
) {
    val triggered: Boolean
        get() = status == WebSearchRequestStatus.TRIGGERED
}

/**
 * 一次 Chat Dedicated Search 在真正发起网络请求前冻结的执行计划。
 *
 * [query]、Provider 和 [request] 一旦生成就不再重新计算，确保 Chat UI 展示的搜索词与真正发送给
 * Search Provider 的请求完全一致；NOT_NEEDED 时这些字段保持 null。
 */
data class WebSearchPreparedRequest(
    val decision: WebSearchDecision,
    val query: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val providerKind: WebSearchProviderKind? = null,
    val request: WebSearchRequest? = null,
    val preflightErrorMessage: String? = null,
    val perfTrace: AiPerfTrace? = null,
) {
    val triggered: Boolean
        get() = decision.triggered

    val required: Boolean
        get() = decision.required
}

/**
 * Dedicated Search 执行结果。
 *
 * FORCE 失败仍返回结构化结果，让上层先持久化搜索状态，再把 [errorMessage] 作为本轮明确错误暴露；
 * AUTO 失败则使用 [FAILED_FALLBACK] 并继续模型链。
 */
data class WebSearchRouteResult(
    val status: WebSearchRequestStatus,
    val response: WebSearchResponse? = null,
    val providerName: String? = null,
    val errorMessage: String? = null,
    val requiredFailure: Boolean = false,
)

/** P5-A 首期内置 Dedicated Search Provider。 */
enum class WebSearchProviderKind(
    val defaultDisplayName: String,
    val defaultEndpoint: String,
    val backendKind: WebSearchBackendKind,
    val requiresApiKey: Boolean = true,
    val supportsApiKey: Boolean = true,
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
    KEENABLE(
        "Keenable",
        "https://api.keenable.ai/v1/search/public",
        WebSearchBackendKind.RAW_SEARCH,
        requiresApiKey = false,
        supportsApiKey = true,
    ),
    SEARXNG(
        "SearXNG",
        "",
        WebSearchBackendKind.RAW_SEARCH,
        requiresApiKey = false,
        supportsApiKey = false,
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
    /**
     * Dedicated Search 的整次 HTTP Call 预算。
     *
     * Search 当前位于 Chat 首次模型请求之前，因此不能沿用 AI 生成链 150 秒的长超时；AUTO/FORCE
     * 由 Router 按交互语义覆盖，设置页测活等独立调用使用这里的保守默认值。
     */
    val timeoutMillis: Long = DEFAULT_WEB_SEARCH_TIMEOUT_MILLIS,
    /** P0 性能追踪句柄；仅携带非敏感 trace id/起始时间，不包含 Query 或凭据。 */
    val perfTrace: AiPerfTrace? = null,
) {
    init {
        require(query.isNotBlank()) { "Search query 不能为空" }
        require(maxResults in 1..20) { "maxResults 必须在 1..20" }
        require(timeoutMillis in 250L..60_000L) { "Search timeoutMillis 必须在 250..60000" }
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

internal const val DEFAULT_WEB_SEARCH_TIMEOUT_MILLIS = 12_000L

