package me.ash.reader.llm.search

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** SearXNG 自托管 Search Provider；标准 JSON Search API 不要求 API Key。 */
@Singleton
class SearxngWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.SEARXNG

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse =
        withContext(ioDispatcher) {
            require(profile.kind == kind) { "SearXNG adapter 收到错误 Provider 类型" }
            val url =
                profile.endpoint.toHttpUrl().newBuilder()
                    .addQueryParameter("q", request.query.trim())
                    .addQueryParameter("format", "json")
                    .build()
            val httpRequest = Request.Builder().url(url).header("Accept", "application/json").get().build()
            httpClient.newWebSearchCall(httpRequest, request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw WebSearchException("SearXNG 搜索失败：HTTP ${response.code}")
                }
                parseSearxngSearchResponse(profile, payload, request.maxResults)
            }
        }
}

/** 将 SearXNG Search API `results` 归一化。 */
internal fun parseSearxngSearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
    maxResults: Int = 20,
): WebSearchResponse {
    val root = runCatching { JSONObject(payload) }
        .getOrElse { throw WebSearchException("SearXNG 返回了无效 JSON", it) }
    val array = root.optJSONArray("results")
    val results =
        buildList {
            if (array != null) {
                repeat(minOf(array.length(), maxResults)) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val url = item.optString("url").trim()
                    if (url.isBlank()) return@repeat
                    add(
                        WebSearchResult(
                            title = item.optString("title").ifBlank { url },
                            url = url,
                            snippet = item.optString("content"),
                            publishedAt = item.optString("publishedDate").takeIf(String::isNotBlank),
                            source = runCatching { url.toHttpUrl().host }.getOrNull(),
                        )
                    )
                }
            }
        }
    return WebSearchResponse(profile.id, profile.name, profile.kind.backendKind, results)
}
