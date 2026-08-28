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

/** Exa Dedicated Search Provider。 */
@Singleton
class ExaWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.EXA

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse =
        withContext(ioDispatcher) {
            require(profile.kind == kind) { "Exa adapter 收到错误 Provider 类型" }
            require(apiKey.isNotBlank()) { "Exa API Key 为空" }
            val body =
                JSONObject()
                    .put("query", request.query.trim())
                    // Exa 官方将 instant 定位为 chat/voice/autocomplete 的最低延迟模式；全文检索仍保留 auto。
                    .put("type", if (request.includeContent) "auto" else "instant")
                    .put("numResults", request.maxResults)
                    .put(
                        "contents",
                        JSONObject().put("highlights", true).apply {
                            if (request.includeContent) put("text", true)
                        },
                    )
            val httpRequest =
                Request.Builder()
                    .url(profile.endpoint)
                    .header("x-api-key", apiKey)
                    .header("Accept", "application/json")
                    .post(body.toString().toRequestBody(EXA_JSON_MEDIA_TYPE))
                    .build()
            httpClient.newWebSearchCall(httpRequest, request).execute().use { response ->
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
                        "Exa 搜索失败：HTTP ${response.code}${exaErrorSuffix(payload)}"
                    )
                }
                parseExaSearchResponse(profile, payload)
            }
        }
}

/** 将 Exa 响应归一化成 OrigRead SearchResult。 */
internal fun parseExaSearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
): WebSearchResponse {
    val root = runCatching { JSONObject(payload) }
        .getOrElse { throw WebSearchException("Exa 返回了无效 JSON", it) }
    val array = root.optJSONArray("results")
    val results =
        buildList {
            if (array != null) {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val url = item.optString("url").trim()
                    if (url.isBlank()) return@repeat
                    val highlights = item.optJSONArray("highlights")
                    val highlight =
                        buildList {
                            if (highlights != null) {
                                repeat(highlights.length()) { i ->
                                    highlights.optString(i).trim().takeIf(String::isNotBlank)?.let(::add)
                                }
                            }
                        }.joinToString(" … ")
                    val text = item.optString("text").trim()
                    add(
                        WebSearchResult(
                            title = item.optString("title").trim().ifBlank { url },
                            url = url,
                            snippet = highlight.ifBlank { text.take(MAX_EXA_SNIPPET_LENGTH) },
                            publishedAt = item.optString("publishedDate").trim().takeIf(String::isNotBlank),
                            source = runCatching { java.net.URI(url).host }.getOrNull(),
                            content = text.takeIf(String::isNotBlank),
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

private fun exaErrorSuffix(payload: String): String {
    val message =
        runCatching {
                val root = JSONObject(payload)
                root.optString("error").ifBlank { root.optString("message") }
            }
            .getOrDefault("")
            .trim()
    return if (message.isBlank()) "" else "：${message.take(240)}"
}

private val EXA_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MAX_EXA_SNIPPET_LENGTH = 1_500

