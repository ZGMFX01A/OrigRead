package me.ash.reader.llm.search

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.ai.AiPerfTracer
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Tavily Dedicated Search Provider。 */
@Singleton
class TavilyWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.TAVILY

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse =
        withContext(ioDispatcher) {
            require(profile.kind == kind) { "Tavily adapter 收到错误 Provider 类型" }
            require(apiKey.isNotBlank()) { "Tavily API Key 为空" }
            val body =
                JSONObject()
                    .put("query", request.query.trim())
                    .put("search_depth", "basic")
                    .put("max_results", request.maxResults)
                    .put("include_answer", false)
                    .put("include_raw_content", request.includeContent)
            val httpRequest =
                Request.Builder()
                    .url(profile.endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .post(body.toString().toRequestBody(TAVILY_JSON_MEDIA_TYPE))
                    .build()
            httpClient.executeWebSearchCall(httpRequest, request) { response ->
                val payload = response.body?.string().orEmpty()
                request.perfTrace?.let { trace ->
                    AiPerfTracer.mark(
                        trace,
                        "search_response_read_complete",
                        "providerKind" to kind.name,
                        "responseChars" to payload.length,
                    )
                }
                if (!response.isSuccessful) {
                    throw WebSearchException(
                        "Tavily 搜索失败：HTTP ${response.code}${tavilyErrorSuffix(payload)}"
                    )
                }
                parseTavilySearchResponse(profile, payload)
            }
        }
}

/** 将 Tavily 响应归一化成 OrigRead SearchResult。 */
internal fun parseTavilySearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
): WebSearchResponse {
    val root = runCatching { JSONObject(payload) }
        .getOrElse { throw WebSearchException("Tavily 返回了无效 JSON", it) }
    val array = root.optJSONArray("results")
    val results =
        buildList {
            if (array != null) {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val url = item.optString("url").trim()
                    if (url.isBlank()) return@repeat
                    val rawContent = item.optString("raw_content").trim()
                    add(
                        WebSearchResult(
                            title = item.optString("title").trim().ifBlank { url },
                            url = url,
                            snippet = item.optString("content").trim(),
                            publishedAt =
                                item.optString("published_date").trim().takeIf(String::isNotBlank),
                            source = runCatching { java.net.URI(url).host }.getOrNull(),
                            content = rawContent.takeIf(String::isNotBlank),
                        )
                    )
                }
            }
        }
    return WebSearchResponse(
        providerId = profile.id,
        providerName = profile.name,
        backendKind = profile.kind.backendKind,
        results = results,
        answer = root.optString("answer").trim().takeIf(String::isNotBlank),
    )
}

private fun tavilyErrorSuffix(payload: String): String {
    val message =
        runCatching {
                val root = JSONObject(payload)
                root.optString("detail").ifBlank { root.optString("error") }
            }
            .getOrDefault("")
            .trim()
    return if (message.isBlank()) "" else "：${message.take(240)}"
}

private val TAVILY_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
