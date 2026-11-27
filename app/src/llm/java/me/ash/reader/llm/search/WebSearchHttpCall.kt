package me.ash.reader.llm.search

import java.util.concurrent.TimeUnit
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.ai.AiPerfRequestTag
import me.ash.reader.infrastructure.ai.AiPerfTracer
import me.ash.reader.infrastructure.ai.awaitResponseAndUse
import me.ash.reader.infrastructure.network.EndpointPolicy
import me.ash.reader.infrastructure.network.readBoundedUtf8
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

internal const val MAX_WEB_SEARCH_RESPONSE_BYTES = 4L * 1024L * 1024L

/**
 * 创建 Dedicated Search 专用 Call。
 *
 * 继续复用 AI Client 的 TLS、连接池和 User-Agent，但覆盖整次 Call 的时间预算，并强制启用
 * [EndpointPolicy]（禁用自动重定向、限制明文与内网/环回访问、在建连 DNS 阶段校验全部解析结果），
 * 防止搜索凭据经 30x 或 DNS rebinding 泄露。
 */
internal fun AiHttpClient.newWebSearchCall(
    httpRequest: Request,
    searchRequest: WebSearchRequest,
): Call {
    val url = httpRequest.url
    val allowLocal = EndpointPolicy.isExplicitLocal(url)
    try {
        EndpointPolicy.requireUrl(url.toString(), allowLocal = allowLocal, requireHttps = !allowLocal)
    } catch (error: IllegalArgumentException) {
        throw WebSearchException("搜索服务地址不安全：${error.message}", error)
    }
    val taggedRequest =
        searchRequest.perfTrace?.let { trace ->
            httpRequest.newBuilder()
                .tag(AiPerfRequestTag::class.java, AiPerfRequestTag(trace))
                .build()
        } ?: httpRequest
    val call = EndpointPolicy.client(client, allowLocal = allowLocal).newCall(taggedRequest)
    call.timeout().timeout(searchRequest.timeoutMillis, TimeUnit.MILLISECONDS)
    searchRequest.perfTrace?.let { trace ->
        AiPerfTracer.mark(
            trace,
            "search_http_budget",
            "timeoutMs" to searchRequest.timeoutMillis,
        )
    }
    return call
}

/** 保留 Dedicated Search 的短 timeout，并让取消覆盖响应体读取与关闭。 */
internal suspend fun <T> AiHttpClient.executeWebSearchCall(
    httpRequest: Request,
    searchRequest: WebSearchRequest,
    block: (Response) -> T,
): T = newWebSearchCall(httpRequest, searchRequest).awaitResponseAndUse(block)

/** 按上限有界读取搜索响应文本，防止异常大包体导致 OOM。 */
internal fun Response.readWebSearchPayload(): String = readBoundedUtf8(MAX_WEB_SEARCH_RESPONSE_BYTES)
