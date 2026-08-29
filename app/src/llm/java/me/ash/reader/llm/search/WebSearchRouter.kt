package me.ash.reader.llm.search

import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import me.ash.reader.infrastructure.ai.AiPerfTracer
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
    /**
     * 在网络请求前冻结本次 Dedicated Search 的真实 query、Provider 和 timeout。
     *
     * 该阶段不访问 Search Provider；上层可以先把返回计划持久化到 Assistant 消息，再调用
     * [executePreparedSearch]，从而保证 UI 展示内容与真正执行请求完全一致。
     */
    fun prepareSearch(
        enabled: Boolean,
        mode: WebSearchMode,
        userInput: String,
        articleTitle: String?,
    ): WebSearchPreparedRequest {
        val decision = resolveWebSearchDecision(enabled, mode, userInput)
        if (!decision.triggered) {
            return WebSearchPreparedRequest(decision = decision)
        }
        val perfTrace = AiPerfTracer.start("web-search")
        val required = decision.required
        AiPerfTracer.mark(
            perfTrace,
            "search_decision_complete",
            "mode" to mode.name,
            "triggered" to true,
        )
        val settings = repository.current()
        val prepared =
            buildWebSearchPreparedRequest(
                decision = decision,
                articleTitle = articleTitle,
                userInput = userInput,
                configuredProviders = repository.configuredProviders(),
                defaultProviderId = settings.defaultProviderId,
                maxResults = settings.maxResults,
                perfTrace = perfTrace,
            )
        if (prepared.preflightErrorMessage != null) {
            AiPerfTracer.mark(perfTrace, "search_no_provider", "required" to required)
        } else {
            prepared.request?.let { request ->
                AiPerfTracer.mark(
                    perfTrace,
                    "search_budget_resolved",
                    "timeoutMs" to request.timeoutMillis,
                    "required" to required,
                )
            }
        }
        return prepared
    }

    /** 执行已经冻结的 Search 计划；禁止在这里重新生成 query、重选 Provider 或改 timeout。 */
    suspend fun executePreparedSearch(prepared: WebSearchPreparedRequest): WebSearchRouteResult {
        if (!prepared.triggered) {
            return WebSearchRouteResult(status = WebSearchRequestStatus.NOT_NEEDED)
        }
        prepared.preflightErrorMessage?.let { message ->
            return WebSearchRouteResult(
                status =
                    if (prepared.required) WebSearchRequestStatus.FAILED_REQUIRED
                    else WebSearchRequestStatus.FAILED_FALLBACK,
                providerName = prepared.providerName,
                errorMessage = message,
                requiredFailure = prepared.required,
            )
        }
        val request = prepared.request ?: error("已触发 Web Search 但缺少冻结请求")
        val providerId = prepared.providerId ?: error("已触发 Web Search 但缺少 Provider ID")
        val providerName = prepared.providerName ?: error("已触发 Web Search 但缺少 Provider 名称")
        return try {
            val response = service.search(request, providerId = providerId)
            buildWebSearchSuccessResult(response)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val failure =
                buildWebSearchFailureResult(
                    required = prepared.required,
                    providerName = providerName,
                    error = error,
                )
            prepared.perfTrace?.let { trace ->
                AiPerfTracer.mark(
                    trace,
                    if (prepared.required) "force_search_failed" else "auto_search_failed_fallback",
                    "providerKind" to (prepared.providerKind?.name ?: "UNKNOWN"),
                    "error" to error.javaClass.simpleName,
                )
            }
            failure
        }
    }
}

/**
 * 纯函数构造 Search 执行计划，供 Router 与 JVM 回归测试共同验证。
 *
 * Query 在这里仅生成一次，并直接写入 [WebSearchRequest.query]；后续 UI/Room 读取同一个 [query]，避免
 * ViewModel 与 Router 各自拼装导致“显示的搜索词”和真正发出的请求不一致。
 */
internal fun buildWebSearchPreparedRequest(
    decision: WebSearchDecision,
    articleTitle: String?,
    userInput: String,
    configuredProviders: List<WebSearchProviderProfile>,
    defaultProviderId: String?,
    maxResults: Int = DEFAULT_WEB_SEARCH_MAX_RESULTS,
    perfTrace: me.ash.reader.infrastructure.ai.AiPerfTrace? = null,
): WebSearchPreparedRequest {
    if (!decision.triggered) return WebSearchPreparedRequest(decision = decision)
    val query = buildSearchQuery(articleTitle, userInput)
    val selectedProvider =
        selectConfiguredSearchProvider(
            configuredProviders = configuredProviders,
            defaultProviderId = defaultProviderId,
        )
        ?: return WebSearchPreparedRequest(
            decision = decision,
            query = query,
            preflightErrorMessage = "尚未配置可用的 Web Search Provider",
            perfTrace = perfTrace,
        )
    val request =
        WebSearchRequest(
            query = query,
            maxResults = normalizeWebSearchMaxResults(maxResults),
            includeContent = false,
            // AUTO 优先保证 Chat 可用性；FORCE 是用户明确联网，允许更长等待后再暴露失败。
            timeoutMillis =
                if (decision.required) FORCE_SEARCH_TIMEOUT_MILLIS else AUTO_SEARCH_TIMEOUT_MILLIS,
            perfTrace = perfTrace,
        )
    return WebSearchPreparedRequest(
        decision = decision,
        query = query,
        providerId = selectedProvider.id,
        providerName = selectedProvider.name,
        providerKind = selectedProvider.kind,
        request = request,
        perfTrace = perfTrace,
    )
}

/**
 * Dedicated Search 只允许从真正完成配置的 Provider 中选默认项。
 *
 * 设置里可能仍把一个“已启用但缺 Key”的 Provider 记作 default；这种情况下必须回退到第一个 configured
 * Provider，不能让不完整默认项把本来可工作的 AUTO/FORCE 搜索误判成失败。
 */
internal fun selectConfiguredSearchProvider(
    configuredProviders: List<WebSearchProviderProfile>,
    defaultProviderId: String?,
): WebSearchProviderProfile? =
    configuredProviders.firstOrNull { it.id == defaultProviderId }
        ?: configuredProviders.firstOrNull()

/**
 * 计算 AUTO/FORCE 是否应触发 Dedicated Search。
 *
 * 该函数不访问 Provider，供 ViewModel 在网络请求前立即把 TRIGGERED 状态写入对应 Assistant 消息。
 */
internal fun resolveWebSearchDecision(
    enabled: Boolean,
    mode: WebSearchMode,
    userInput: String,
): WebSearchDecision {
    if (!enabled || mode == WebSearchMode.OFF) {
        return WebSearchDecision(WebSearchRequestStatus.NOT_NEEDED, required = false)
    }
    val required = mode == WebSearchMode.FORCE
    return WebSearchDecision(
        status =
            if (required || shouldAutoSearch(userInput)) {
                WebSearchRequestStatus.TRIGGERED
            } else {
                WebSearchRequestStatus.NOT_NEEDED
            },
        required = required,
    )
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

/** 把底层网络/协议异常收敛为带 Provider 名称的 FORCE 可读错误；AUTO UI 只展示通用软降级提示。 */
internal fun webSearchUserError(
    providerName: String,
    error: Throwable,
): String {
    val existing = error.message?.trim().orEmpty()
    if (error is InterruptedIOException) return "$providerName 搜索超时"
    if (error is WebSearchException && existing.isNotBlank()) {
        return if (existing.contains(providerName, ignoreCase = true)) existing else "$providerName：$existing"
    }
    return if (existing.isBlank()) {
        "$providerName 搜索失败"
    } else {
        "$providerName 搜索失败：$existing"
    }
}

/** 把 Provider 异常映射成 AUTO 软降级或 FORCE 必须失败的稳定业务结果。 */
internal fun buildWebSearchFailureResult(
    required: Boolean,
    providerName: String,
    error: Throwable,
): WebSearchRouteResult =
    WebSearchRouteResult(
        status =
            if (required) WebSearchRequestStatus.FAILED_REQUIRED
            else WebSearchRequestStatus.FAILED_FALLBACK,
        providerName = providerName,
        errorMessage = webSearchUserError(providerName, error),
        requiredFailure = required,
    )

/** 将已完成的 Provider 响应映射为稳定 SUCCESS 状态，供 Router 与回归测试共同使用。 */
internal fun buildWebSearchSuccessResult(response: WebSearchResponse): WebSearchRouteResult =
    WebSearchRouteResult(
        status = WebSearchRequestStatus.SUCCESS,
        response = response,
        providerName = response.providerName,
    )

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

private const val MAX_SEARCH_QUERY_LENGTH = 500
private const val AUTO_SEARCH_TIMEOUT_MILLIS = 3_000L
private const val FORCE_SEARCH_TIMEOUT_MILLIS = 12_000L
// 搜索资料是阅读辅助：排在摘要(130)/当前译文(120)之后、长原文(100)之前。
private const val SEARCH_CONTEXT_PRIORITY = 110

