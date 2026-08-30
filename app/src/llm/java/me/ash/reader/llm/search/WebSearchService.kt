package me.ash.reader.llm.search

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.ai.AiPerfTracer

/**
 * Dedicated Search 的统一执行入口。
 *
 * 这里只处理用户明确配置的 Dedicated Search Provider，不接管模型原生联网能力，也不把 MCP Search Tool
 * 映射为 Web Search backend；上层 Chat 仍不直接依赖某个供应商 Adapter。
 */
@Singleton
class WebSearchService @Inject constructor(
    private val repository: WebSearchRepository,
    exa: ExaWebSearchProvider,
    tavily: TavilyWebSearchProvider,
    brave: BraveWebSearchProvider,
    perplexity: PerplexityWebSearchProvider,
    linkup: LinkupWebSearchProvider,
    firecrawl: FirecrawlWebSearchProvider,
    keenable: KeenableWebSearchProvider,
    searxng: SearxngWebSearchProvider,
) {
    private val adapters: Map<WebSearchProviderKind, WebSearchProviderAdapter> =
        listOf<WebSearchProviderAdapter>(
                exa,
                tavily,
                brave,
                perplexity,
                linkup,
                firecrawl,
                keenable,
                searxng,
            )
            .associateBy(WebSearchProviderAdapter::kind)

    suspend fun search(
        request: WebSearchRequest,
        providerId: String? = null,
    ): WebSearchResponse {
        val profile = configuredProfile(providerId)
        return searchWithProfile(
            request = request,
            profile = profile,
            apiKey = repository.getApiKey(profile.id),
        )
    }

    /** 执行 Router 已冻结的请求级 Provider；此路径禁止重新选默认项或重新读取 Secret。 */
    suspend fun searchPrepared(
        request: WebSearchRequest,
        snapshot: WebSearchProviderSnapshot,
    ): WebSearchResponse =
        searchWithProfile(
            request = request,
            profile = snapshot.profile,
            apiKey = snapshot.apiKey,
        )

    private suspend fun searchWithProfile(
        request: WebSearchRequest,
        profile: WebSearchProviderProfile,
        apiKey: String,
    ): WebSearchResponse {
        validateRuntimeProfile(profile, apiKey)
        val adapter = adapterFor(profile)
        request.perfTrace?.let { trace ->
            AiPerfTracer.mark(
                trace,
                "search_provider_selected",
                "providerId" to profile.id,
                "providerKind" to profile.kind.name,
                "maxResults" to request.maxResults,
            )
        }
        val response =
            adapter.search(
                profile = profile,
                apiKey = apiKey,
                request = request,
            )
        request.perfTrace?.let { trace ->
            AiPerfTracer.mark(
                trace,
                "search_complete",
                "providerKind" to profile.kind.name,
                "resultCount" to response.results.size,
            )
        }
        return response
    }

    /**
     * 对指定 Provider 做一次真实最小请求，用于设置页“测活”。
     *
     * 多数 Search API 没有独立健康端点，单纯 HEAD/GET 无法验证 API Key 和请求格式；因此这里真实搜索
     * 1 条结果且不主动抓取全文，以较低开销覆盖 Endpoint、认证与响应解析。
     */
    suspend fun checkHealth(providerId: String): WebSearchHealthCheckResult {
        val profile = configuredProfile(providerId)
        val adapter = adapterFor(profile)
        val startedAt = System.nanoTime()
        val response =
            adapter.search(
                profile = profile,
                apiKey = repository.getApiKey(profile.id),
                request =
                    WebSearchRequest(
                        query = HEALTH_CHECK_QUERY,
                        maxResults = 1,
                        includeContent = false,
                    ),
            )
        val latencyMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
        return WebSearchHealthCheckResult(
            providerId = profile.id,
            providerName = profile.name,
            latencyMs = latencyMs,
            resultCount = response.results.size,
        )
    }

    private fun configuredProfile(providerId: String?): WebSearchProviderProfile {
        val settings = repository.current()
        val profile =
            if (providerId == null) {
                settings.defaultProvider()
            } else {
                settings.providers.firstOrNull { it.id == providerId }
            }
                ?: throw WebSearchException("没有配置 Web Search Provider")
        if (!repository.isConfigured(profile.id)) {
            throw WebSearchException("Web Search Provider 未完成配置：${profile.name}")
        }
        return profile
    }

    private fun validateRuntimeProfile(profile: WebSearchProviderProfile, apiKey: String) {
        if (!profile.enabled || profile.endpoint.isBlank()) {
            throw WebSearchException("Web Search Provider 未完成配置：${profile.name}")
        }
        if (profile.kind.requiresApiKey && apiKey.isBlank()) {
            throw WebSearchException("Web Search Provider 缺少 API Key：${profile.name}")
        }
    }

    private fun adapterFor(profile: WebSearchProviderProfile): WebSearchProviderAdapter =
        adapters[profile.kind]
            ?: throw WebSearchException("暂不支持搜索服务：${profile.kind.name}")

    companion object {
        internal const val HEALTH_CHECK_QUERY = "OrigRead connectivity test"
    }
}

