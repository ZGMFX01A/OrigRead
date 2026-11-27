package me.ash.reader.llm.search

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.json.optNullableString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** Brave Search Dedicated Provider。 */
@Singleton
class BraveWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.BRAVE

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse =
        withContext(ioDispatcher) {
            require(profile.kind == kind) { "Brave adapter 收到错误 Provider 类型" }
            require(apiKey.isNotBlank()) { "Brave API Key 为空" }
            val url =
                profile.endpoint.toHttpUrl().newBuilder()
                    .addQueryParameter("q", request.query.trim())
                    .addQueryParameter("count", request.maxResults.toString())
                    .addQueryParameter("text_decorations", "false")
                    // 普通 Chat grounding 使用主 description 即可；全文/扩展上下文模式才请求额外摘录。
                    .addQueryParameter("extra_snippets", request.includeContent.toString())
                    .build()
            val httpRequest =
                Request.Builder()
                    .url(url)
                    .header("X-Subscription-Token", apiKey)
                    .header("Accept", "application/json")
                    .get()
                    .build()
            httpClient.executeWebSearchCall(httpRequest, request) { response ->
                val payload = response.readWebSearchPayload()
                if (!response.isSuccessful) {
                    throw WebSearchException("Brave 搜索失败：HTTP ${response.code}")
                }
                parseBraveSearchResponse(profile, payload)
            }
        }
}

/** 将 Brave `web.results` 归一化为 OrigRead SearchResult。 */
internal fun parseBraveSearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
): WebSearchResponse {
    val root = runCatching { JSONObject(payload) }
        .getOrElse { throw WebSearchException("Brave 返回了无效 JSON", it) }
    val array = root.optJSONObject("web")?.optJSONArray("results")
    val results =
        buildList {
            if (array != null) {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val url = item.optNullableString("url") ?: return@repeat
                    val extra = item.optJSONArray("extra_snippets")
                    val snippets =
                        buildList {
                            item.optNullableString("description")?.let(::add)
                            if (extra != null) {
                                repeat(extra.length()) { i ->
                                    extra.optNullableString(i)?.let(::add)
                                }
                            }
                        }
                    add(
                        WebSearchResult(
                            title = item.optNullableString("title") ?: url,
                            url = url,
                            snippet = snippets.distinct().joinToString("\n"),
                            publishedAt = item.optNullableString("page_age"),
                            source = runCatching { url.toHttpUrl().host }.getOrNull(),
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
