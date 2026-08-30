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
        val basePrepared =
            buildWebSearchPreparedRequest(
                decision = decision,
                articleTitle = articleTitle,
                userInput = userInput,
                configuredProviders = repository.configuredProviders(),
                defaultProviderId = settings.defaultProviderId,
                maxResults = settings.maxResults,
                perfTrace = perfTrace,
            )
        val prepared =
            basePrepared.providerId?.let { providerId ->
                val profile = settings.providers.firstOrNull { it.id == providerId }
                if (profile == null) {
                    basePrepared.copy(
                        request = null,
                        preflightErrorMessage = "Web Search Provider 在请求准备期间已失效",
                    )
                } else {
                    basePrepared.copy(
                        providerSnapshot =
                            WebSearchProviderSnapshot(
                                profile = profile,
                                apiKey = repository.getApiKey(profile.id),
                            )
                    )
                }
            } ?: basePrepared
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
        val providerSnapshot =
            prepared.providerSnapshot ?: error("已触发 Web Search 但缺少 Provider 快照")
        val providerName = prepared.providerName ?: error("已触发 Web Search 但缺少 Provider 名称")
        return try {
            val response = service.searchPrepared(request, providerSnapshot)
            buildWebSearchResult(response = response, required = prepared.required)
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
    val normalized = userInput.trim()
    if (normalized.isBlank()) return false
    if (CHINESE_DIRECT_TIME_PATTERN.containsMatchIn(normalized)) return true
    if (CHINESE_FRESHNESS_PATTERN.containsMatchIn(normalized)) return true
    if (CHINESE_EXPLICIT_SEARCH_PATTERN.containsMatchIn(normalized)) return true

    // 英文不能直接 contains("current")："electric current"、"undercurrents" 等都不是时效查询。
    // 明确时效词和显式联网动作可以直接触发；current/update 只在时效语义上下文中触发。
    return ENGLISH_AUTO_SEARCH_PATTERN.containsMatchIn(normalized) ||
        ENGLISH_CURRENT_FRESHNESS_PATTERN.containsMatchIn(normalized) ||
        ENGLISH_UPDATE_FRESHNESS_PATTERN.containsMatchIn(normalized)
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

/** 将 Provider 响应去重后映射为成功、AUTO 空结果软降级或 FORCE 必须失败。 */
internal fun buildWebSearchResult(
    response: WebSearchResponse,
    required: Boolean,
): WebSearchRouteResult {
    val normalized = response.deduplicateResultsByUrl()
    if (normalized.results.isNotEmpty()) {
        return WebSearchRouteResult(
            status = WebSearchRequestStatus.SUCCESS,
            response = normalized,
            providerName = normalized.providerName,
        )
    }
    if (required) {
        return WebSearchRouteResult(
            status = WebSearchRequestStatus.FAILED_REQUIRED,
            providerName = normalized.providerName,
            errorMessage = "${normalized.providerName} 没有返回可用搜索结果",
            requiredFailure = true,
        )
    }
    return WebSearchRouteResult(
        status = WebSearchRequestStatus.EMPTY_RESULT,
        response = normalized,
        providerName = normalized.providerName,
        errorMessage = "${normalized.providerName} 没有返回可用搜索结果",
    )
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

/** 高频直接时间信号；“最新”排除“最新颖”这一常见非时效构词。 */
private val CHINESE_DIRECT_TIME_PATTERN =
    Regex("""最新(?!颖)|今天|今日|昨天|昨日|截至(?:目前|现在|今天|今日)""")

/**
 * “当前/现在/后来/最近/更新”等中文词构歧义很强，只在明确时效对象或问法附近触发。
 * 例如“当前提条件”“后来居上”“最近邻算法”都不应联网。
 */
private val CHINESE_FRESHNESS_PATTERN =
    Regex(
        """(?:目前|当前|现在)(?:的)?(?:消息|新闻|进展|近况|现状|状态|动态|版本|发布|价格|行情|政策|规则|法规|数据|排名|结果|负责人|领导人|总统|总理|CEO)|""" +
            """(?:目前|当前|现在)(?:怎么样|如何|是什么情况|发生了什么)|""" +
            """(?:最近|近期|近日)(?:的)?(?:消息|新闻|进展|近况|动态|更新|变化|发布|价格|行情|数据|结果)|""" +
            """(?:后来|后续)(?:又)?(?:有|有什么|有何|的)?(?:进展|变化|更新|消息|结果|情况|发展)|""" +
            """(?:有|有什么|有何|有哪些)(?:最新|新的)?更新""",
        RegexOption.IGNORE_CASE,
    )

/** 明确要求联网/搜索的中文动作；“网上面试”“搜索算法”等名词短语不会单独命中。 */
private val CHINESE_EXPLICIT_SEARCH_PATTERN =
    Regex(
        """(?:联网|上网|网络|网上)(?:搜索|查询|查找|检索|搜|查)|""" +
            """(?:帮我|请|麻烦|能否|可以帮我)(?:联网|上网|网络|网上)?(?:搜索|查询|查找|检索|搜|查)|""" +
            """(?:搜索|查询|查找|检索|搜|查)(?:一下|一查|最新)""",
    )

/** 英文明确信号使用词边界，避免时效关键词的字母序列命中更长单词。 */
private val ENGLISH_AUTO_SEARCH_PATTERN =
    Regex(
        pattern =
            """(?i)\b(latest|recent|recently|today|tonight|yesterday|currently|news)\b|""" +
                """\bthis\s+(week|month|year)\b|\bright\s+now\b|\bas\s+of\b|""" +
                """\bwhat\s+happened\s+since\b|\bfollow[- ]?up\b|""" +
                """\bsearch\s+(the\s+)?web\b|\bsearch\s+online\b|\blook\s+up\b""",
    )

/** current 本身语义过宽，仅和典型时效对象组合时才视为需要联网。 */
private val ENGLISH_CURRENT_FRESHNESS_PATTERN =
    Regex(
        pattern =
            """(?i)\bcurrent\s+(news|status|situation|state|events?|developments?|updates?|version|release|price|weather|forecast|president|prime\s+minister|ceo|leader|policy|rules?|law|regulations?)\b""",
    )

/** update 既可能是技术动作也可能是时效询问，只接受明确“近况/更新”语法。 */
private val ENGLISH_UPDATE_FRESHNESS_PATTERN =
    Regex(
        pattern =
            """(?i)\b(latest|recent|new|any)\s+updates?\b|\bupdates?\s+(on|about|since)\b|\bupdate\s+me\s+(on|about)\b|\bwhat(?:'s|\s+is)\s+new\b""",
    )

private const val MAX_SEARCH_QUERY_LENGTH = 500
private const val AUTO_SEARCH_TIMEOUT_MILLIS = 3_000L
private const val FORCE_SEARCH_TIMEOUT_MILLIS = 12_000L
// 搜索资料是阅读辅助：排在摘要(130)/当前译文(120)之后、长原文(100)之前。
private const val SEARCH_CONTEXT_PRIORITY = 110
