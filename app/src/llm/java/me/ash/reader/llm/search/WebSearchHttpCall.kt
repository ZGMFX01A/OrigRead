package me.ash.reader.llm.search

import java.util.concurrent.TimeUnit
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.ai.AiPerfRequestTag
import me.ash.reader.infrastructure.ai.AiPerfTracer
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import me.ash.reader.infrastructure.ai.awaitResponseAndUse

/**
 * 创建 Dedicated Search 专用 Call。
 *
 * 继续复用 AI Client 的 TLS、连接池和 User-Agent，但覆盖整次 Call 的时间预算。这样 DNS、建连、TLS、
 * 服务端等待和响应读取任一阶段异常变慢时，都不会继承生成链 150 秒的长 callTimeout 去阻塞 Chat 首 token。
 */
internal fun AiHttpClient.newWebSearchCall(
    httpRequest: Request,
    searchRequest: WebSearchRequest,
): Call {
    val taggedRequest =
        searchRequest.perfTrace?.let { trace ->
            httpRequest.newBuilder()
                .tag(AiPerfRequestTag::class.java, AiPerfRequestTag(trace))
                .build()
        } ?: httpRequest
    val call = client.newCall(taggedRequest)
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
