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

/** Perplexity Search API Dedicated Provider；使用原始 ranked results，不调用 Sonar 生成答案。 */
@Singleton
class PerplexityWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.PERPLEXITY

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse =
        withContext(ioDispatcher) {
            require(profile.kind == kind) { "Perplexity adapter 收到错误 Provider 类型" }
            require(apiKey.isNotBlank()) { "Perplexity API Key 为空" }
            val body =
                JSONObject()
                    .put("query", request.query.trim())
                    .put("max_results", request.maxResults)
                    // 交互式 grounding 只需要短 snippet，使用较小的每页 token 预算；需要正文上下文时再恢复 4096。
                    .put("max_tokens_per_page", if (request.includeContent) 4096 else 512)
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
                    throw WebSearchException("Perplexity 搜索失败：HTTP ${response.code}")
                }
                parsePerplexitySearchResponse(profile, payload)
            }
        }
}

/** 将 Perplexity Search API `results` 归一化。 */
internal fun parsePerplexitySearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
): WebSearchResponse {
    val root = runCatching { JSONObject(payload) }
        .getOrElse { throw WebSearchException("Perplexity 返回了无效 JSON", it) }
    val array = root.optJSONArray("results")
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
                            snippet = item.optString("snippet"),
                            publishedAt = item.optString("date").takeIf(String::isNotBlank),
                            source = runCatching { url.toHttpUrl().host }.getOrNull(),
                        )
                    )
                }
            }
        }
    return WebSearchResponse(profile.id, profile.name, profile.kind.backendKind, results)
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
