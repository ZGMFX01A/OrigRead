package me.ash.reader.llm.search

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.ai.AiHttpClient
import me.ash.reader.infrastructure.ai.AiPerfTracer
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/** DeepSeek 官方独立网页搜索适配器，直接获取结构化结果，不调用对话模型。 */
@Singleton
class DeepSeekWebSearchProvider @Inject constructor(
    private val httpClient: AiHttpClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : WebSearchProviderAdapter {
    override val kind: WebSearchProviderKind = WebSearchProviderKind.DEEPSEEK

    /** 按官方 Search API 契约发送请求，复用统一的 HTTP 超时、取消和凭据隔离。 */
    override suspend fun search(
        profile: WebSearchProviderProfile,
        apiKey: String,
        request: WebSearchRequest,
    ): WebSearchResponse = withContext(ioDispatcher) {
        require(profile.kind == kind) { "DeepSeek adapter 收到错误 Provider 类型" }
        require(apiKey.isNotBlank()) { "DeepSeek API Key 为空" }
        val body = buildDeepSeekSearchBody(request)
        val httpRequest = Request.Builder()
            .url(profile.endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(DEEPSEEK_JSON_MEDIA_TYPE))
            .build()
        // 使用用户保存的独立搜索端点；不依赖 DeepSeek 对话模型配置。
        httpClient.executeWebSearchCall(httpRequest, request) { response ->
            val payload = response.readWebSearchPayload()
            request.perfTrace?.let { trace ->
                AiPerfTracer.mark(
                    trace,
                    "search_response_read_complete",
                    "providerKind" to kind.name,
                    "responseChars" to payload.length,
                )
            }
            if (!response.isSuccessful) {
                throw WebSearchException("DeepSeek 搜索失败：HTTP ${response.code}")
            }
            parseDeepSeekSearchResponse(profile, payload, request.includeContent)
        }
    }
}

/** 按 Unicode 字符数校验官方长度限制，避免截断查询导致实际搜索词与界面记录不一致。 */
internal fun buildDeepSeekSearchBody(request: WebSearchRequest): JSONObject {
    val query = request.query.trim()
    require(query.codePointCount(0, query.length) <= DEEPSEEK_MAX_QUERY_CODE_POINTS) {
        "DeepSeek 搜索词不能超过 $DEEPSEEK_MAX_QUERY_CODE_POINTS 个 Unicode 字符"
    }
    return JSONObject()
        .put("query", query)
        .put("count", request.maxResults)
        .put("need_content", request.includeContent)
}

/** 保留官方结果排序；缺失 results 或字段类型错误时明确失败，不能伪装成无搜索结果。 */
internal fun parseDeepSeekSearchResponse(
    profile: WebSearchProviderProfile,
    payload: String,
    includeContent: Boolean = false,
): WebSearchResponse {
    try {
        val array = JSONObject(payload).getJSONArray("results")
        val results = List(array.length()) { index ->
            parseDeepSeekSearchResult(array.getJSONObject(index), includeContent)
        }
        return WebSearchResponse(
            providerId = profile.id,
            providerName = profile.name,
            backendKind = profile.kind.backendKind,
            results = results,
        )
    } catch (error: JSONException) {
        // 协议错误直接暴露给设置页与搜索调用方，保留原始解析异常以便排查。
        throw WebSearchException("DeepSeek 返回了无效的搜索结果格式", error)
    }
}

/** 短摘要用于展示，长摘要和按需请求的正文作为模型证据；官方可空日期保持为 null。 */
private fun parseDeepSeekSearchResult(item: JSONObject, includeContent: Boolean): WebSearchResult {
    val resultUrl = item.deepSeekString("url").trim()
    val url = resultUrl.toHttpUrlOrNull()
        ?: throw WebSearchException("DeepSeek 返回了无效的搜索结果 URL")
    val summary = item.deepSeekString("summary").trim()
    val content = item.deepSeekString("content").trim()
    val evidence = listOf(summary, content.takeIf { includeContent }.orEmpty())
        .filter(String::isNotBlank)
        .joinToString("\n\n")
    return WebSearchResult(
        title = item.deepSeekString("title"),
        url = resultUrl,
        snippet = item.deepSeekString("snippet"),
        publishedAt = when (val dateVal = item.opt("date")) {
            null, JSONObject.NULL -> null
            is String -> dateVal
            else -> throw JSONException("DeepSeek 字段 date 必须是字符串或 null")
        },
        source = url.host,
        content = evidence.takeIf(String::isNotBlank),
    )
}

/** Android getString 会将数字、对象和 JSON null 转成文本，必须检查原始类型以免污染模型证据。 */
private fun JSONObject.deepSeekString(name: String): String =
    get(name) as? String ?: throw JSONException("DeepSeek 字段 $name 必须是字符串")

/** DeepSeek 官方 Search API 的搜索词上限，按 Unicode 码点计数。 */
internal const val DEEPSEEK_MAX_QUERY_CODE_POINTS = 128

/** DeepSeek Search API 使用 UTF-8 JSON 请求体。 */
private val DEEPSEEK_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
