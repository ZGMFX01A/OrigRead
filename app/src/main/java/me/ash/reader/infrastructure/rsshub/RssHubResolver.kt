package me.ash.reader.infrastructure.rsshub

import com.rometools.rome.feed.synd.SyndFeed
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ash.reader.infrastructure.di.IODispatcher
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
) {
    val available: Boolean
        get() = state == CandidateState.AVAILABLE && feed != null
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
    private val shortTimeoutClient =
        okHttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /** 匹配并依次验证有限数量的 RSSHub 路由，任一路由失败都不会中断后续候选。 */
    suspend fun probe(
        inputUrl: String,
        instanceBaseUrl: String? = null,
    ): List<RssHubProbeResult> = withContext(ioDispatcher) {
        val settings = settingsRepository.current()
        if (!settings.enabled) return@withContext emptyList()
        val instances =
            instanceBaseUrl?.let {
                RssHubSettingsRepository.orderInstances(it)
            } ?: settingsRepository.candidateInstances()
        if (instances.isEmpty()) return@withContext emptyList()

        withTimeoutOrNull(TOTAL_PROBE_TIMEOUT_MILLIS) {
            val failures = mutableListOf<RssHubProbeResult>()
            for (instance in instances) {
                val results = probeInstance(inputUrl, instance)
                val available = results.filter { it.available }
                if (available.isNotEmpty()) {
                    settingsRepository.recordSuccess(instance)
                    return@withTimeoutOrNull available
                }
                if (results.isNotEmpty()) {
                    failures += results
                    if (
                        results.all {
                            it.state == CandidateState.TIMEOUT ||
                                it.state == CandidateState.NETWORK_UNAVAILABLE
                        }
                    ) {
                        settingsRepository.recordFailure(instance)
                    }
                }
            }
            failures.distinctBy { result ->
                "${result.match.route.id}:${result.state}:${result.match.missingParameters}"
            }
        } ?: emptyList()
    }

    /** 单个实例内部并发验证有限数量路由，实例之间则按优先级串行。 */
    private suspend fun probeInstance(inputUrl: String, instanceBaseUrl: String): List<RssHubProbeResult> {
        val matches = routeMatcher.match(inputUrl, instanceBaseUrl, MAX_ROUTE_CANDIDATES)
        if (matches.isEmpty()) return emptyList()
        return supervisorScope {
            matches.map { match ->
                if (match.resolved) {
                    async { probeOne(match, inputUrl) }
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
                shortTimeoutClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                }
            }
        }

    /** 单个路由失败只转换为诊断状态，不向上抛出。 */
    private suspend fun probeOne(match: RssHubRouteMatch, inputUrl: String): RssHubProbeResult =
        try {
            val feed =
                rssHelper.parseFeedDirect(
                    feedUrl = requireNotNull(match.feedUrl),
                    iconSourceUrl = inputUrl,
                    client = shortTimeoutClient,
                )
            RssHubProbeResult(match = match, state = CandidateState.AVAILABLE, feed = feed)
        } catch (error: CancellationException) {
            // 协程总时间预算触发时必须继续传播取消，避免被识别为普通内容错误。
            throw error
        } catch (error: SocketTimeoutException) {
            RssHubProbeResult(
                match = match,
                state = CandidateState.TIMEOUT,
                message = "RSSHub connection timed out and was skipped",
            )
        } catch (error: IOException) {
            RssHubProbeResult(
                match = match,
                state = CandidateState.NETWORK_UNAVAILABLE,
                message = "RSSHub is unavailable on the current network and was skipped",
            )
        } catch (error: Exception) {
            RssHubProbeResult(
                match = match,
                state = CandidateState.INVALID_CONTENT,
                message = error.message ?: "RSSHub returned invalid content",
            )
        }

    companion object {
        const val DEFAULT_INSTANCE = "https://rsshub.app"

        private const val MAX_ROUTE_CANDIDATES = 3
        private const val CONNECT_TIMEOUT_SECONDS = 2L
        private const val READ_TIMEOUT_SECONDS = 4L
        private const val CALL_TIMEOUT_SECONDS = 5L
        private const val TOTAL_PROBE_TIMEOUT_MILLIS = 9_000L
    }
}
