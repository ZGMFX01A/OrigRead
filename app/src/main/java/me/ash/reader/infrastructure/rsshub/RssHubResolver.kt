package me.ash.reader.infrastructure.rsshub

import com.rometools.rome.feed.synd.SyndFeed
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.ProtocolException
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.FeedFetchException
import me.ash.reader.infrastructure.rss.FeedFetchFailureReason
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.website.CandidateState
import okhttp3.OkHttpClient
import okio.IOException

/** RSSHub 单个路由的探测结果。失败结果只用于提示，不参与内容评分。 */
data class RssHubProbeResult(
    val match: RssHubRouteMatch,
    val state: CandidateState,
    val feed: SyndFeed? = null,
    val message: String? = null,
    val routePath: String? = null,
    val instanceBaseUrl: String? = null,
    val failureReason: RssHubFailureReason? = null,
    val statusCode: Int? = null,
) {
    val available: Boolean
        get() = state == CandidateState.AVAILABLE && feed != null
}

enum class RssHubFailureReason {
    BLOCKED,
    HTTP_ERROR,
    INVALID_CONTENT,
    TIMEOUT,
    NETWORK_UNAVAILABLE,
    CONNECTION_CLOSED,
    DNS_FAILURE,
    TLS_ERROR,
    HTML_RESPONSE,
    DISABLED,
    NO_INSTANCES,
    PROBE_BUDGET_EXHAUSTED,
    UNSUPPORTED_FORMAT,
    AUTHENTICATION_REQUIRES_INSTANCE,
    BOUND_INSTANCE_DISABLED,
}

/**
 * 消费 RSSHub 已整理好的路由结果。
 * 使用独立短超时客户端，RSSHub 在当前网络不可达时快速退出，不阻塞网站规则等其他方案。
 */
@Singleton
class RssHubResolver @Inject constructor(
    private val routeMatcher: RssHubRouteMatcher,
    private val rssHelper: RssHelper,
    private val settingsRepository: RssHubSettingsRepository,
    okHttpClient: OkHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val rssHubHttpClients = RssHubHttpClients(okHttpClient)

    /**
     * 匹配并验证有限数量的 RSSHub 路由。
     *
     * 实例按优先级进入有界并发窗口；单个实例内部仍只并发有限数量路由。
     * 这样可以让不同公共实例真正并行竞争，同时避免“实例数 × 路由数”无限放大请求量。
     */
    suspend fun probe(
        inputUrl: String,
        instanceBaseUrl: String? = null,
    ): List<RssHubProbeResult> = withContext(ioDispatcher) {
        val settings = settingsRepository.current()
        val instances =
            instanceBaseUrl?.let {
                RssHubSettingsRepository.orderInstances(it)
            } ?: settingsRepository.candidateInstances()
        val discoveryBaseUrl = instances.firstOrNull() ?: DEFAULT_INSTANCE
        val expectedMatches = routeMatcher.match(inputUrl, discoveryBaseUrl, MAX_ROUTE_CANDIDATES)
        if (expectedMatches.isEmpty()) return@withContext emptyList()

        if (!settings.enabled) {
            return@withContext localDiagnostics(
                matches = expectedMatches,
                resolvedState = CandidateState.UNSUPPORTED,
                message = "RSSHub is disabled in settings",
                failureReason = RssHubFailureReason.DISABLED,
            )
        }
        if (instances.isEmpty()) {
            return@withContext localDiagnostics(
                matches = expectedMatches,
                resolvedState = CandidateState.UNSUPPORTED,
                message = "No RSSHub instance is enabled",
                failureReason = RssHubFailureReason.NO_INSTANCES,
            )
        }
        val expectedResolvedKeys =
            expectedMatches.filter(RssHubRouteMatch::resolved).mapTo(linkedSetOf(), ::routeKey)
        val diagnostics = linkedMapOf<String, RssHubProbeResult>()
        expectedMatches.filterNot(RssHubRouteMatch::resolved).forEach { match ->
            diagnostics.putIfAbsent(
                routeKey(match),
                RssHubProbeResult(
                    match = match,
                    state = CandidateState.NEEDS_INPUT,
                    message =
                        "RSSHub route requires parameters: " +
                            match.missingParameters.joinToString(),
                ),
            )
        }
        if (expectedResolvedKeys.isEmpty()) return@withContext diagnostics.values.toList()

        val availableByRoute = linkedMapOf<String, RssHubProbeResult>()
        var successRecorded = false
        val completed = withTimeoutOrNull(TOTAL_PROBE_TIMEOUT_MILLIS) {
            consumeInstancesInCompletionOrder(
                instances = instances,
                maxConcurrency = DISCOVERY_INSTANCE_CONCURRENCY,
                block = { instance ->
                    try {
                        probeInstance(inputUrl, instance)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        emptyList()
                    }
                },
                onResult = { instance, results ->
                    if (!successRecorded && results.any(RssHubProbeResult::available)) {
                        runCatching { settingsRepository.recordSuccess(instance) }
                        successRecorded = true
                    }
                    val attemptedRoutes = results.filter { it.match.resolved }
                    if (attemptedRoutes.isNotEmpty() && attemptedRoutes.all(::shouldCoolInstanceGlobally)) {
                        runCatching { settingsRepository.recordFailure(instance) }
                    }
                    results.forEach { result ->
                        result.routePath?.let { routePath ->
                            val routeFamily = RssHubInputParser.routeFamily(routePath)
                            if (result.available) {
                                runCatching { settingsRepository.recordRouteSuccess(instance, routeFamily) }
                            } else if (result.state != CandidateState.NEEDS_INPUT) {
                                runCatching { settingsRepository.recordRouteFailure(instance, routeFamily) }
                            }
                        }
                        val key = routeKey(result.match)
                        if (result.available) {
                            availableByRoute.putIfAbsent(key, result)
                            diagnostics.remove(key)
                        } else if (key !in availableByRoute) {
                            diagnostics.putIfAbsent(key, result)
                        }
                    }
                    availableByRoute.keys.containsAll(expectedResolvedKeys)
                },
            )
        }

        // 总预算可能在某一批实例尚未返回时触发。此时本地路由匹配已经成功，
        // 不能因为网络验证没完成就让这些路由从添加来源界面完全消失。
        val incompleteResults = mutableListOf<RssHubProbeResult>()
        expectedMatches.filter(RssHubRouteMatch::resolved).forEach { match ->
            val key = routeKey(match)
            if (key !in availableByRoute && (completed == null || key !in diagnostics)) {
                val budgetExpired = completed == null
                val result =
                    RssHubProbeResult(
                        match = match,
                        state = if (budgetExpired) CandidateState.TIMEOUT else CandidateState.NETWORK_UNAVAILABLE,
                        message =
                            if (budgetExpired) {
                                "RSSHub route matched, but no instance completed within the probe budget"
                            } else {
                                "RSSHub route matched, but no instance returned a usable result"
                            },
                        failureReason =
                            if (budgetExpired) {
                                RssHubFailureReason.PROBE_BUDGET_EXHAUSTED
                            } else {
                                RssHubFailureReason.NETWORK_UNAVAILABLE
                            },
                    )
                if (budgetExpired) incompleteResults += result else diagnostics[key] = result
            }
        }

        buildList {
            addAll(availableByRoute.values)
            diagnostics.forEach { (key, result) ->
                if (key !in availableByRoute) add(result)
            }
            addAll(incompleteResults)
        }
    }

    /** 显式 HTTP 地址作为普通 RSS 使用时仍保留统一诊断，不启动实例回退。 */
    suspend fun probeExplicitRoute(routePath: String, instanceBaseUrl: String): RssHubProbeResult =
        withContext(ioDispatcher) {
            probeDirectRoute(requireNotNull(RssHubInputParser.normalizeRoutePath(routePath)), instanceBaseUrl)
        }

    /** 自动恢复尊重当前启用列表；带访问凭证的路由仍只能请求原实例。 */
    suspend fun probeRouteForRecovery(routePath: String, boundInstance: String?): List<RssHubProbeResult> {
        if (!RssHubInputParser.requiresBoundInstance(routePath)) return probeRoute(routePath)
        val enabled = settingsRepository.current().instances.filter { it.enabled }
            .mapNotNull { RssHubInputParser.normalizeInstanceBase(it.url) }
        val bound = boundInstance?.let(RssHubInputParser::normalizeInstanceBase)
        if (bound == null || bound !in enabled) {
            return listOf(RssHubProbeResult(
                match = directRouteMatch(routePath, bound ?: DEFAULT_INSTANCE),
                state = CandidateState.UNSUPPORTED,
                routePath = routePath,
                instanceBaseUrl = bound,
                failureReason = if (bound == null) RssHubFailureReason.AUTHENTICATION_REQUIRES_INSTANCE
                    else RssHubFailureReason.BOUND_INSTANCE_DISABLED,
            ))
        }
        return probeRoute(routePath, bound)
    }

    /**
     * 直接探测一个 RSSHub logical route。
     * 普通 route 并发回退到启用实例；带 key/code 的 route 只能使用指定实例。
     */
    suspend fun probeRoute(
        routePath: String,
        preferredInstance: String? = null,
    ): List<RssHubProbeResult> = withContext(ioDispatcher) {
        val normalizedRoute = RssHubInputParser.normalizeRoutePath(routePath) ?: return@withContext emptyList()
        val settings = settingsRepository.current()
        val routeFamily = RssHubInputParser.routeFamily(normalizedRoute)
        val candidates =
            if (RssHubInputParser.requiresBoundInstance(normalizedRoute)) {
                preferredInstance?.let { RssHubSettingsRepository.orderInstances(it) }.orEmpty()
            } else RssHubSettingsRepository.orderInstances(
                preferredInstance,
                *settingsRepository.candidateInstancesForRoute(routeFamily).toTypedArray(),
            )
        val fallbackInstance = candidates.firstOrNull() ?: preferredInstance ?: DEFAULT_INSTANCE
        val fallbackMatch = directRouteMatch(normalizedRoute, fallbackInstance)
        if (!settings.enabled) {
            return@withContext listOf(
                RssHubProbeResult(
                    match = fallbackMatch,
                    state = CandidateState.UNSUPPORTED,
                    message = "RSSHub is disabled in settings",
                    failureReason = RssHubFailureReason.DISABLED,
                    routePath = normalizedRoute,
                    instanceBaseUrl = preferredInstance,
                )
            )
        }
        if (candidates.isEmpty()) {
            return@withContext listOf(
                RssHubProbeResult(
                    match = fallbackMatch,
                    state = CandidateState.UNSUPPORTED,
                    message = "No RSSHub instance is enabled",
                    failureReason = if (RssHubInputParser.requiresBoundInstance(normalizedRoute)) {
                        RssHubFailureReason.AUTHENTICATION_REQUIRES_INSTANCE
                    } else RssHubFailureReason.NO_INSTANCES,
                    routePath = normalizedRoute,
                )
            )
        }

        val candidateOrder = candidates.withIndex().associate { (index, instance) -> instance to index }
        val diagnostics = linkedMapOf<Int, RssHubProbeResult>()
        val completedInstances = linkedSetOf<String>()
        var available: RssHubProbeResult? = null
        val completed = withTimeoutOrNull(TOTAL_PROBE_TIMEOUT_MILLIS) {
            consumeInstancesInCompletionOrder(
                instances = candidates,
                maxConcurrency = DIRECT_ROUTE_INSTANCE_CONCURRENCY,
                block = { instance -> probeDirectRoute(normalizedRoute, instance) },
                onResult = { instance, result ->
                    completedInstances += instance
                    if (result.available) {
                        available = result
                        runCatching { settingsRepository.recordRouteSuccess(instance, routeFamily) }
                        runCatching { settingsRepository.recordSuccess(instance) }
                        true
                    } else {
                        diagnostics[candidateOrder.getValue(instance)] = result
                        runCatching { settingsRepository.recordRouteFailure(instance, routeFamily) }
                        if (shouldCoolInstanceGlobally(result)) {
                            runCatching { settingsRepository.recordFailure(instance) }
                        }
                        false
                    }
                },
            )
        }

        available?.let { success ->
            return@withContext buildList {
                add(success)
                addAll(diagnostics.toSortedMap().values)
            }
        }
        if (completed == null) {
            val unfinished = candidates.filterNot(completedInstances::contains)
            val firstUnfinished = unfinished.firstOrNull() ?: fallbackInstance
            diagnostics[candidateOrder[firstUnfinished] ?: Int.MAX_VALUE] = RssHubProbeResult(
                match = directRouteMatch(normalizedRoute, unfinished.firstOrNull() ?: fallbackInstance),
                state = CandidateState.TIMEOUT,
                message = "RSSHub probe budget expired; ${unfinished.size} instance(s) not completed",
                routePath = normalizedRoute,
                instanceBaseUrl = unfinished.firstOrNull(),
                failureReason = RssHubFailureReason.PROBE_BUDGET_EXHAUSTED,
            )
        }
        if (diagnostics.isNotEmpty()) return@withContext diagnostics.toSortedMap().values.toList()
        listOf(
            RssHubProbeResult(
                match = fallbackMatch,
                state = CandidateState.TIMEOUT,
                message = "RSSHub route probing timed out",
                routePath = normalizedRoute,
                instanceBaseUrl = preferredInstance,
                failureReason = RssHubFailureReason.TIMEOUT,
            )
        )
    }

    private fun localDiagnostics(
        matches: List<RssHubRouteMatch>,
        resolvedState: CandidateState,
        message: String,
        failureReason: RssHubFailureReason? = null,
    ): List<RssHubProbeResult> =
        matches.map { match ->
            if (match.resolved) {
                RssHubProbeResult(
                    match = match,
                    state = resolvedState,
                    message = message,
                    failureReason = failureReason,
                )
            } else {
                RssHubProbeResult(
                    match = match,
                    state = CandidateState.NEEDS_INPUT,
                    message =
                        "RSSHub route requires parameters: " +
                            match.missingParameters.joinToString(),
                )
            }
        }

    fun localRouteDiagnostics(inputUrl: String): List<RssHubProbeResult> =
        routeMatcher.match(inputUrl, DEFAULT_INSTANCE, MAX_ROUTE_CANDIDATES).let { matches ->
            localDiagnostics(matches, CandidateState.NETWORK_UNAVAILABLE, "RSSHub instance probing failed")
        }

    /** 单个实例内部并发验证有限数量路由；实例层也由上层有界并发调度。 */
    private suspend fun probeInstance(inputUrl: String, instanceBaseUrl: String): List<RssHubProbeResult> {
        val matches = routeMatcher.match(inputUrl, instanceBaseUrl, MAX_ROUTE_CANDIDATES)
        if (matches.isEmpty()) return emptyList()
        return supervisorScope {
            matches.map { match ->
                if (match.resolved) {
                    async { probeOne(match, instanceBaseUrl) }
                } else {
                    async {
                        RssHubProbeResult(
                            match = match,
                            state = CandidateState.NEEDS_INPUT,
                            message =
                                "RSSHub route requires parameters: " +
                                    match.missingParameters.joinToString(),
                        )
                    }
                }
            }.awaitAll()
        }
    }

    /** 使用当前实例执行轻量连接测试。 */
    suspend fun testConnection(instanceBaseUrl: String): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val normalized = RssHubSettingsRepository.normalizeInstanceUrl(instanceBaseUrl)
                val request = okhttp3.Request.Builder().url("$normalized/healthz").build()
                rssHubHttpClients.forInstance(normalized).newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                }
            }
        }

    private suspend fun probeDirectRoute(routePath: String, instanceBaseUrl: String): RssHubProbeResult {
        val match = directRouteMatch(routePath, instanceBaseUrl)
        return probeOne(match, instanceBaseUrl, routePath)
    }

    private fun directRouteMatch(routePath: String, instanceBaseUrl: String): RssHubRouteMatch {
        val normalizedInstance = RssHubSettingsRepository.normalizeInstanceUrl(instanceBaseUrl)
        val feedUrl = RssHubInputParser.buildFeedUrl(normalizedInstance, routePath)
        val routeId = routePath.substringBefore('?')
        return RssHubRouteMatch(
            route =
                RssHubRouteDefinition(
                    id = "direct:$routeId",
                    name = "RSSHub",
                    host = "rsshub",
                    pathPrefix = routeId,
                    target = routePath,
                ),
            feedUrl = feedUrl,
        )
    }

    /** 单个路由失败只转换为诊断状态，不向上抛出。 */
    private suspend fun probeOne(
        match: RssHubRouteMatch,
        instanceBaseUrl: String,
        explicitRoutePath: String? = null,
    ): RssHubProbeResult {
        val normalizedInstance = RssHubSettingsRepository.normalizeInstanceUrl(instanceBaseUrl)
        val routePath =
            explicitRoutePath
                ?: match.feedUrl?.let { feedUrl ->
                    RssHubInputParser.parseExplicit(feedUrl, listOf(normalizedInstance))?.routePath
                }
        return try {
            val feed =
                rssHelper.parseFeedDirect(
                    feedUrl = requireNotNull(match.feedUrl),
                    // 图标是装饰信息，不能占用路由验证预算或丢弃已经解析成功的 Feed。
                    // 保留 Feed XML 自带的图标；后续显示可使用原有默认图标。
                    iconSourceUrl = "",
                    client = rssHubHttpClients.forInstance(normalizedInstance),
                )
            RssHubProbeResult(
                match = match,
                state = CandidateState.AVAILABLE,
                feed = feed,
                routePath = routePath,
                instanceBaseUrl = normalizedInstance,
            )
        } catch (error: CancellationException) {
            // 协程总时间预算触发时必须继续传播取消，避免被识别为普通内容错误。
            throw error
        } catch (error: FeedFetchException) {
            val failure =
                when (error.reason) {
                    FeedFetchFailureReason.BLOCKED_RESPONSE -> RssHubFailureReason.BLOCKED
                    FeedFetchFailureReason.HTTP_ERROR -> RssHubFailureReason.HTTP_ERROR
                    FeedFetchFailureReason.HTML_RESPONSE -> RssHubFailureReason.HTML_RESPONSE
                    FeedFetchFailureReason.INVALID_CONTENT -> RssHubFailureReason.INVALID_CONTENT
                    FeedFetchFailureReason.UNSUPPORTED_FORMAT -> RssHubFailureReason.UNSUPPORTED_FORMAT
                }
            RssHubProbeResult(
                match = match,
                state =
                    if (failure == RssHubFailureReason.BLOCKED || failure == RssHubFailureReason.HTTP_ERROR) {
                        CandidateState.NETWORK_UNAVAILABLE
                    } else {
                        CandidateState.INVALID_CONTENT
                    },
                message = error.message,
                routePath = routePath,
                instanceBaseUrl = normalizedInstance,
                failureReason = failure,
                statusCode = error.statusCode,
            )
        } catch (error: InterruptedIOException) {
            RssHubProbeResult(
                match = match,
                state = CandidateState.TIMEOUT,
                message = "RSSHub connection timed out and was skipped",
                routePath = routePath,
                instanceBaseUrl = normalizedInstance,
                failureReason = RssHubFailureReason.TIMEOUT,
            )
        } catch (error: IOException) {
            RssHubProbeResult(
                match = match,
                state = CandidateState.NETWORK_UNAVAILABLE,
                message = "RSSHub is unavailable on the current network and was skipped",
                routePath = routePath,
                instanceBaseUrl = normalizedInstance,
                failureReason = when (error) {
                    is UnknownHostException -> RssHubFailureReason.DNS_FAILURE
                    is SSLException -> RssHubFailureReason.TLS_ERROR
                    is ConnectException -> RssHubFailureReason.NETWORK_UNAVAILABLE
                    is EOFException, is SocketException -> RssHubFailureReason.CONNECTION_CLOSED
                    is ProtocolException -> if (error.message.orEmpty().contains("end of stream", ignoreCase = true)) {
                        RssHubFailureReason.CONNECTION_CLOSED
                    } else RssHubFailureReason.NETWORK_UNAVAILABLE
                    else -> RssHubFailureReason.NETWORK_UNAVAILABLE
                },
            )
        } catch (error: Exception) {
            RssHubProbeResult(
                match = match,
                state = CandidateState.INVALID_CONTENT,
                message = error.message ?: "RSSHub returned invalid content",
                routePath = routePath,
                instanceBaseUrl = normalizedInstance,
                failureReason = RssHubFailureReason.INVALID_CONTENT,
            )
        }
    }

    private fun routeKey(match: RssHubRouteMatch): String =
        buildString {
            append(match.route.id)
            append('|')
            match.parameters.toSortedMap().forEach { (key, value) ->
                append(key).append('=').append(value).append('&')
            }
        }

    /**
     * 连接层失败代表整个实例当前不可达；HTTP 429 同样属于实例级限流。
     * 其它 4xx/5xx 继续只做 route-family 冷却，避免一个上游失败误伤整个公共实例。
     */
    private fun shouldCoolInstanceGlobally(result: RssHubProbeResult): Boolean =
        result.failureReason in TRANSPORT_FAILURES || result.statusCode == 429

    private data class IndexedInstanceResult<T>(
        val index: Int,
        val instance: String,
        val value: T,
    )

    /**
     * 所有候选立即排队，但最多只有 maxConcurrency 个实例同时占用网络。
     * 按完成顺序消费结果；一旦 onResult 表示目标已满足，立即取消其余探测。
     */
    private suspend fun <T> consumeInstancesInCompletionOrder(
        instances: List<String>,
        maxConcurrency: Int,
        block: suspend (String) -> T,
        onResult: (String, T) -> Boolean,
    ): Boolean = supervisorScope {
        if (instances.isEmpty()) return@supervisorScope false
        val semaphore = Semaphore(minOf(maxConcurrency, instances.size))
        val pending =
            instances.mapIndexed { index, instance ->
                index to async {
                    semaphore.withPermit {
                        IndexedInstanceResult(index, instance, block(instance))
                    }
                }
            }.toMutableList()

        try {
            while (pending.isNotEmpty()) {
                val completed =
                    select<IndexedInstanceResult<T>> {
                        pending.forEach { (_, deferred) ->
                            deferred.onAwait { it }
                        }
                    }
                pending.removeAll { (index, _) -> index == completed.index }
                if (onResult(completed.instance, completed.value)) {
                    pending.forEach { (_, deferred) -> deferred.cancel() }
                    return@supervisorScope true
                }
            }
            false
        } finally {
            pending.forEach { (_, deferred) -> deferred.cancel() }
        }
    }

    companion object {
        const val DEFAULT_INSTANCE = "https://rsshub.app"

        private const val MAX_ROUTE_CANDIDATES = 5
        private const val DISCOVERY_INSTANCE_CONCURRENCY = 4
        private const val DIRECT_ROUTE_INSTANCE_CONCURRENCY = 8
        private const val TOTAL_PROBE_TIMEOUT_MILLIS = 12_000L
        private val TRANSPORT_FAILURES = setOf(
            RssHubFailureReason.TIMEOUT,
            RssHubFailureReason.NETWORK_UNAVAILABLE,
            RssHubFailureReason.CONNECTION_CLOSED,
            RssHubFailureReason.DNS_FAILURE,
            RssHubFailureReason.TLS_ERROR,
        )
    }
}
