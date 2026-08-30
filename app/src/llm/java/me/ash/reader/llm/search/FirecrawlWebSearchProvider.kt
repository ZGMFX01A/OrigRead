package me.ash.reader.llm.search

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Firecrawl v2 Search Dedicated Provider。 */
@Singleton
class FirecrawlWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.FIRECRAWL

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse =
        withContext(ioDispatcher) {
            require(profile.kind == kind) { "Firecrawl adapter 收到错误 Provider 类型" }
            require(apiKey.isNotBlank()) { "Firecrawl API Key 为空" }
            val body =
                JSONObject()
                    .put("query", request.query.trim())
                    .put("limit", request.maxResults)
                    .put("sources", JSONArray().put("web"))
                    .apply {
                        if (request.includeContent) {
                            put(
                                "scrapeOptions",
                                JSONObject().put("formats", JSONArray().put(JSONObject().put("type", "markdown"))),
                            )
                        }
                    }
            val httpRequest =
                Request.Builder()
                    .url(profile.endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            httpClient.executeWebSearchCall(httpRequest, request) { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw WebSearchException("Firecrawl 搜索失败：HTTP ${response.code}")
                }
                parseFirecrawlSearchResponse(profile, payload)
            }
        }
}

/** 将 Firecrawl v2 `data.web` 归一化。 */
internal fun parseFirecrawlSearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
): WebSearchResponse {
    val root = runCatching { JSONObject(payload) }
        .getOrElse { throw WebSearchException("Firecrawl 返回了无效 JSON", it) }
    val array = root.optJSONObject("data")?.optJSONArray("web")
    val results =
        buildList {
            if (array != null) {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val url = item.optString("url").trim()
                    if (url.isBlank()) return@repeat
                    add(
                        WebSearchResult(
                            title = item.optString("title").ifBlank { url },
                            url = url,
                            snippet = item.optString("description"),
                            content = item.optString("markdown").takeIf(String::isNotBlank),
                            source = runCatching { url.toHttpUrl().host }.getOrNull(),
                        )
                    )
                }
            }
        }
    return WebSearchResponse(profile.id, profile.name, profile.kind.backendKind, results)
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
