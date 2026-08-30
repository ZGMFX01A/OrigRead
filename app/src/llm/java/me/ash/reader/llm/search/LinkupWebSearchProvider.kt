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
import org.json.JSONObject

/** Linkup Search Dedicated Provider。 */
@Singleton
class LinkupWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.LINKUP

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse =
        withContext(ioDispatcher) {
            require(profile.kind == kind) { "Linkup adapter 收到错误 Provider 类型" }
            require(apiKey.isNotBlank()) { "Linkup API Key 为空" }
            val body =
                JSONObject()
                    .put("q", request.query.trim())
                    .put("depth", "standard")
                    .put("outputType", "searchResults")
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
                    throw WebSearchException("Linkup 搜索失败：HTTP ${response.code}")
                }
                parseLinkupSearchResponse(profile, payload, request.maxResults)
            }
        }
}

/** 将 Linkup `results` 归一化；其 content 本身就是检索到的网页上下文。 */
internal fun parseLinkupSearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
    maxResults: Int = 20,
): WebSearchResponse {
    val root = runCatching { JSONObject(payload) }
        .getOrElse { throw WebSearchException("Linkup 返回了无效 JSON", it) }
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
                            title = item.optString("name").ifBlank { url },
                            url = url,
                            snippet = item.optString("content"),
                            source = runCatching { url.toHttpUrl().host }.getOrNull(),
                        )
                    )
                }
            }
        }
    return WebSearchResponse(profile.id, profile.name, profile.kind.backendKind, results)
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
