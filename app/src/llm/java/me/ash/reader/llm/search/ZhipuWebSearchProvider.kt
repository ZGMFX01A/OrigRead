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

/** 智谱 Web Search Dedicated Provider。使用基础搜索引擎返回原始网页结果。 */
@Singleton
class ZhipuWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.ZHIPU

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse =
        withContext(ioDispatcher) {
            require(profile.kind == kind) { "Zhipu adapter 收到错误 Provider 类型" }
            require(apiKey.isNotBlank()) { "Zhipu API Key 为空" }
            val query = request.query.trim().take(ZHIPU_MAX_QUERY_LENGTH)
            val body =
                JSONObject()
                    .put("search_query", query)
                    .put("search_engine", ZHIPU_DEFAULT_SEARCH_ENGINE)
                    .put("search_intent", false)
                    .put("count", request.maxResults)
                    .put("search_recency_filter", "noLimit")
                    .put("content_size", if (request.includeContent) "high" else "medium")
            val httpRequest =
                Request.Builder()
                    .url(profile.endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .post(body.toString().toRequestBody(ZHIPU_JSON_MEDIA_TYPE))
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
                        "Zhipu 搜索失败：HTTP ${response.code}${zhipuErrorSuffix(payload)}"
                    )
                }
                parseZhipuSearchResponse(profile, payload, includeContent = request.includeContent)
            }
        }
}

/** 将智谱 `search_result` 归一化为 OrigRead SearchResult。 */
internal fun parseZhipuSearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
    includeContent: Boolean = false,
): WebSearchResponse {
    val root =
        runCatching { JSONObject(payload) }
            .getOrElse { throw WebSearchException("Zhipu 返回了无效 JSON", it) }
    val array = root.optJSONArray("search_result")
    val results =
        buildList {
            if (array != null) {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val url = item.optString("link").trim()
                    if (url.isBlank()) return@repeat
                    val content = item.optString("content").trim()
                    add(
                        WebSearchResult(
                            title = item.optString("title").trim().ifBlank { url },
                            url = url,
                            snippet = content,
                            publishedAt =
                                item.optString("publish_date").trim().takeIf(String::isNotBlank),
                            source =
                                item.optString("media").trim().takeIf(String::isNotBlank)
                                    ?: runCatching { java.net.URI(url).host }.getOrNull(),
                            content = content.takeIf { includeContent && it.isNotBlank() },
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
    )
}

private fun zhipuErrorSuffix(payload: String): String {
    val message =
        runCatching {
                val root = JSONObject(payload)
                root.optString("message")
                    .ifBlank { root.optString("error") }
                    .ifBlank { root.optJSONObject("error")?.optString("message").orEmpty() }
            }
            .getOrDefault("")
            .trim()
    return if (message.isBlank()) "" else "：${message.take(240)}"
}

private const val ZHIPU_MAX_QUERY_LENGTH = 70
private const val ZHIPU_DEFAULT_SEARCH_ENGINE = "search_std"
private val ZHIPU_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
