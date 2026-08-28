package me.ash.reader.llm.search

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.ai.AiPerfTracer
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Keenable Dedicated Search Provider，支持无 Key 公共端点与可选 API Key。 */
@Singleton
class KeenableWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.KEENABLE

    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse = withContext(ioDispatcher) {
        require(profile.kind == kind) { "Keenable adapter 收到错误 Provider 类型" }
        val normalizedApiKey = apiKey.trim()
        val endpoint = resolveKeenableEndpoint(profile.endpoint, normalizedApiKey.isNotBlank())
        val body = JSONObject()
            .put("query", request.query.trim())
            .put("max_results", request.maxResults)
        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(KEENABLE_JSON_MEDIA_TYPE))
        if (normalizedApiKey.isNotBlank()) {
            builder.header("X-API-Key", normalizedApiKey)
        } else {
            // 公共端点要求应用标识；缺失时 Keenable 会返回 400。
            builder.header("X-Keenable-Title", KEENABLE_APP_TITLE)
        }
        httpClient.newWebSearchCall(builder.build(), request).execute().use { response ->
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
                    "Keenable 搜索失败：HTTP ${response.code}${keenableErrorSuffix(payload)}"
                )
            }
            parseKeenableSearchResponse(profile, payload)
        }
    }
}

/** 将 Keenable `results` 归一化为 OrigRead SearchResult。 */
internal fun parseKeenableSearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
): WebSearchResponse {
    val root = runCatching { JSONObject(payload) }
        .getOrElse { throw WebSearchException("Keenable 返回了无效 JSON", it) }
    val array = root.optJSONArray("results")
    val results = buildList {
        if (array != null) {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val url = item.optString("url").trim()
                if (url.isBlank()) return@repeat
                val snippet = item.optString("snippet").trim().ifBlank {
                    item.optString("description").trim()
                }
                add(
                    WebSearchResult(
                        title = item.optString("title").trim().ifBlank { url },
                        url = url,
                        snippet = snippet,
                        publishedAt = item.optString("published_at").trim().takeIf(String::isNotBlank),
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

/**
 * 官方端点在有 Key / 无 Key 时分别使用 `/v1/search` 与 `/v1/search/public`。
 * 仅自动改写官方 Host；用户自定义网关保持原路径，避免破坏代理兼容性。
 */
internal fun resolveKeenableEndpoint(endpoint: String, hasApiKey: Boolean): String {
    val url = endpoint.trim().toHttpUrl()
    if (url.host != KEENABLE_OFFICIAL_HOST) return url.toString()
    return when (url.encodedPath.trimEnd('/')) {
        "/v1/search", "/v1/search/public" ->
            url.newBuilder()
                .encodedPath(if (hasApiKey) "/v1/search" else "/v1/search/public")
                .build()
                .toString()
        else -> url.toString()
    }
}

private fun keenableErrorSuffix(payload: String): String {
    val message = runCatching {
        val root = JSONObject(payload)
        root.optString("detail")
            .ifBlank { root.optString("message") }
            .ifBlank { root.optString("error") }
    }.getOrDefault("").trim()
    return if (message.isBlank()) "" else "：${message.take(240)}"
}

private const val KEENABLE_OFFICIAL_HOST = "api.keenable.ai"
private const val KEENABLE_APP_TITLE = "OrigRead"
private val KEENABLE_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
