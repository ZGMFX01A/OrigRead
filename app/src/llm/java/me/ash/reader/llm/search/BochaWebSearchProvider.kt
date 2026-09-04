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

/** 博查 Web Search Dedicated Provider。只取原始搜索结果，不请求博查生成 AI Answer。 */
@Singleton
class BochaWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.BOCHA

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse =
        withContext(ioDispatcher) {
            require(profile.kind == kind) { "Bocha adapter 收到错误 Provider 类型" }
            require(apiKey.isNotBlank()) { "Bocha API Key 为空" }
            val body =
                JSONObject()
                    .put("query", request.query.trim())
                    .put("freshness", "noLimit")
                    .put("summary", request.includeContent)
                    .put("count", request.maxResults)
            val httpRequest =
                Request.Builder()
                    .url(profile.endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Accept", "application/json")
                    .post(body.toString().toRequestBody(BOCHA_JSON_MEDIA_TYPE))
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
                        "Bocha 搜索失败：HTTP ${response.code}${bochaErrorSuffix(payload)}"
                    )
                }
                parseBochaSearchResponse(profile, payload)
            }
        }
}

/** 将博查 Bing-compatible `data.webPages.value` 归一化为 OrigRead SearchResult。 */
internal fun parseBochaSearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
): WebSearchResponse {
    val root =
        runCatching { JSONObject(payload) }
            .getOrElse { throw WebSearchException("Bocha 返回了无效 JSON", it) }
    val code = root.optInt("code", 200)
    if (code != 200) {
        throw WebSearchException("Bocha 搜索失败：$code${bochaErrorSuffix(payload)}")
    }
    val array = root.optJSONObject("data")?.optJSONObject("webPages")?.optJSONArray("value")
    val results =
        buildList {
            if (array != null) {
                repeat(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@repeat
                    val url = item.optString("url").trim()
                    if (url.isBlank()) return@repeat
                    val summary = item.optString("summary").trim()
                    add(
                        WebSearchResult(
                            title = item.optString("name").trim().ifBlank { url },
                            url = url,
                            snippet = item.optString("snippet").trim().ifBlank { summary },
                            publishedAt =
                                item.optString("datePublished").trim().takeIf(String::isNotBlank),
                            source =
                                item.optString("siteName").trim().takeIf(String::isNotBlank)
                                    ?: runCatching { java.net.URI(url).host }.getOrNull(),
                            content = summary.takeIf(String::isNotBlank),
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

private fun bochaErrorSuffix(payload: String): String {
    val message =
        runCatching {
                val root = JSONObject(payload)
                root.optString("msg")
                    .ifBlank { root.optString("message") }
                    .ifBlank { root.optString("error") }
            }
            .getOrDefault("")
            .trim()
    return if (message.isBlank()) "" else "：${message.take(240)}"
}

private val BOCHA_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
