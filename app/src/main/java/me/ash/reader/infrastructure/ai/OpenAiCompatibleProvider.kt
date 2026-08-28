package me.ash.reader.infrastructure.ai

import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val DEFAULT_COMPLETION_TEMPERATURE = 0.2

/** OpenAI Compatible 一次文本生成的结构化结果。reasoning 只保存供应商明确返回给客户端的内容。 */
data class AiCompletionResult(
    val content: String,
    val reasoning: String? = null,
)

/** OpenAI-compatible SSE 的单个增量；只包含服务端明确返回给客户端的字段。 */
data class AiCompletionDelta(
    val content: String = "",
    val reasoning: String = "",
)

/**
 * 兼容部分推理模型把思考过程包在 `<think>...</think>` 中返回的格式。
 * 仅拆分模型实际返回文本，不推断、不补写任何隐藏思维链。
 */
internal fun splitThinkContent(content: String, explicitReasoning: String? = null): AiCompletionResult {
    val thinkRegex = Regex("<think>(.*?)</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val inlineReasoning =
        thinkRegex.findAll(content)
            .map { it.groupValues[1].trim() }
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .ifBlank { null }
    val finalContent = thinkRegex.replace(content, "").trim()
    val reasoning = explicitReasoning?.trim()?.takeIf(String::isNotBlank) ?: inlineReasoning
    return AiCompletionResult(content = finalContent, reasoning = reasoning)
}

/** 将 Base URL 或完整接口地址统一转换为 Chat Completions 地址。 */
internal fun resolveChatCompletionsEndpoint(endpoint: String): String {
    val url = endpoint.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw AiException(AiErrorCode.INVALID_REQUEST, "AI 服务地址无效")
    val path = url.encodedPath.trimEnd('/')
    val resolvedPath =
        when {
            path.endsWith("/chat/completions") -> path
            path.endsWith("/v1") -> "$path/chat/completions"
            path.isBlank() || path == "/" -> "/v1/chat/completions"
            else -> "$path/v1/chat/completions"
        }

    return url.newBuilder().encodedPath(resolvedPath).build().toString()
}

/**
 * 根据用户填写的 Base URL 或完整 Chat Completions 地址推导模型列表接口。
 * 例如：
 * - https://api.openai.com/v1 -> https://api.openai.com/v1/models
 * - https://api.example.com/v1/chat/completions -> https://api.example.com/v1/models
 * - https://api.deepseek.com/chat/completions -> https://api.deepseek.com/models
 */
internal fun resolveModelsEndpoint(endpoint: String): String {
    val url = endpoint.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw AiException(AiErrorCode.INVALID_REQUEST, "AI 服务地址无效")
    val path = url.encodedPath.trimEnd('/')
    val resolvedPath =
        when {
            path.endsWith("/models") -> path
            path.endsWith("/chat/completions") -> {
                val apiPrefix = path.removeSuffix("/chat/completions")
                if (apiPrefix.isBlank()) "/models" else "$apiPrefix/models"
            }
            path.endsWith("/v1") -> "$path/models"
            path.isBlank() || path == "/" -> "/v1/models"
            else -> "$path/v1/models"
        }
    return url.newBuilder().encodedPath(resolvedPath).build().toString()
}

@Singleton
class OpenAiCompatibleProvider @Inject constructor(
    private val httpClient: AiHttpClient,
) {
    /** 调用 OpenAI Chat Completions 兼容接口并返回首个文本结果。 */
    fun complete(
        systemPrompt: String,
        userPrompt: String,
        config: AiRuntimeConfig,
    ): String = completeDetailed(systemPrompt, userPrompt, config).content

    /**
     * 调用 OpenAI Chat Completions 兼容接口并保留供应商显式返回的 reasoning。
     * 支持 DeepSeek 等常见 `reasoning_content`，同时兼容 `reasoning` 与 `<think>` 文本格式。
     */
    fun completeDetailed(
        systemPrompt: String,
        userPrompt: String,
        config: AiRuntimeConfig,
        temperature: Double = DEFAULT_COMPLETION_TEMPERATURE,
    ): AiCompletionResult {
        val responseText =
            execute(
                buildCompletionRequest(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    config = config,
                    temperature = temperature,
                )
            )
        return parseCompletionResponse(responseText)
    }

    /**
     * 阅读页摘要专用的可取消调用。
     * Coroutine 被取消时会同步取消底层 OkHttp Call，避免“UI 已停止但网络仍跑到超时”的假取消。
     */
    suspend fun completeDetailedCancellable(
        systemPrompt: String,
        userPrompt: String,
        config: AiRuntimeConfig,
        temperature: Double = DEFAULT_COMPLETION_TEMPERATURE,
        perfTrace: AiPerfTrace? = null,
    ): AiCompletionResult {
        val trace = perfTrace ?: AiPerfTracer.start("ai-completion")
        val responseText =
            executeCancellable(
                buildCompletionRequest(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    config = config,
                    temperature = temperature,
                    perfTrace = trace,
                )
            )
        val result = parseCompletionResponse(responseText)
        AiPerfTracer.mark(
            trace,
            "completion_parse_complete",
            "contentChars" to result.content.length,
            "reasoningChars" to result.reasoning.orEmpty().length,
        )
        return result
    }

    /**
     * 阅读页摘要专用的真流式 Chat Completions。
     *
     * 不依赖响应 Content-Type，而是直接消费 OpenAI-compatible `data:` SSE；若兼容服务忽略
     * `stream=true` 并返回完整 JSON，则回退到现有完整响应解析。Coroutine 取消时会取消底层 Call。
     */
    suspend fun streamDetailedCancellable(
        systemPrompt: String,
        userPrompt: String,
        config: AiRuntimeConfig,
        temperature: Double = DEFAULT_COMPLETION_TEMPERATURE,
        perfTrace: AiPerfTrace? = null,
        onDelta: (AiCompletionDelta) -> Unit,
    ): AiCompletionResult {
        val trace = perfTrace ?: AiPerfTracer.start("ai-completion-stream")
        val request =
            buildCompletionRequest(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                config = config,
                temperature = temperature,
                perfTrace = trace,
                stream = true,
            )
        return executeStreamingCancellable(request, trace, onDelta)
    }

    private fun buildCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        config: AiRuntimeConfig,
        temperature: Double = DEFAULT_COMPLETION_TEMPERATURE,
        perfTrace: AiPerfTrace? = null,
        stream: Boolean = false,
    ): Request {
        val bodyJson =
            JSONObject()
                .put("model", config.model)
                .put("stream", stream)
                .put("temperature", temperature)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt)),
                )
                .toString()
        perfTrace?.let { trace ->
            AiPerfTracer.mark(
                trace,
                "request_json_built",
                "requestChars" to bodyJson.length,
                "systemChars" to systemPrompt.length,
                "userChars" to userPrompt.length,
                "stream" to stream,
            )
        }
        val body = bodyJson.toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder =
            Request.Builder()
                .url(resolveChatCompletionsEndpoint(config.endpoint))
                .header(
                    "Accept",
                    if (stream) "text/event-stream, application/json" else "application/json",
                )
                .post(body)
        perfTrace?.let { trace ->
            requestBuilder.tag(AiPerfRequestTag::class.java, AiPerfRequestTag(trace))
        }
        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }
        return requestBuilder.build()
    }

    private fun parseCompletionResponse(responseText: String): AiCompletionResult {
        val root = runCatching { JSONObject(responseText) }
            .getOrElse {
                throw AiException(AiErrorCode.INVALID_RESPONSE, "AI 服务返回了无效 JSON", it)
            }
        val choices = root.optJSONArray("choices")
            ?: throw AiException(AiErrorCode.INVALID_RESPONSE, "AI 响应缺少 choices")
        val first = choices.optJSONObject(0)
            ?: throw AiException(AiErrorCode.INVALID_RESPONSE, "AI 响应没有可用结果")
        val message = first.optJSONObject("message")
        val content = message?.opt("content")
        val text = parseContent(content).ifBlank { first.optString("text") }.trim()
        val explicitReasoning =
            parseContent(message?.opt("reasoning_content"))
                .ifBlank { parseContent(message?.opt("reasoning")) }
                .trim()
                .takeIf(String::isNotBlank)
        val result = splitThinkContent(text, explicitReasoning)
        if (result.content.isBlank()) {
            throw AiException(AiErrorCode.INVALID_RESPONSE, "AI 服务返回了空内容")
        }
        return result
    }

    /**
     * 获取 OpenAI Compatible 服务公开的模型列表。
     * 标准格式优先读取 {"data":[{"id":"..."}]}，同时兼容常见的 models 数组和根数组格式。
     */
    fun listModels(config: AiRuntimeConfig): List<String> {
        val requestBuilder =
            Request.Builder()
                .url(resolveModelsEndpoint(config.endpoint))
                .header("Accept", "application/json")
                .get()
        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }

        val responseText = execute(requestBuilder.build())
        val models = parseModelList(responseText)
            .map(::normalizeAiModelName)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        if (models.isEmpty()) {
            throw AiException(AiErrorCode.INVALID_RESPONSE, "AI 服务未返回可用模型")
        }
        return models
    }

    private fun parseContent(value: Any?): String =
        when (value) {
            is String -> value
            is JSONArray ->
                buildList {
                        for (index in 0 until value.length()) {
                            val item = value.optJSONObject(index) ?: continue
                            item.optString("text").takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                    .joinToString("\n")
            else -> ""
        }

    private fun parseModelList(responseText: String): List<String> {
        fun parseArray(array: JSONArray): List<String> =
            buildList {
                for (index in 0 until array.length()) {
                    when (val item = array.opt(index)) {
                        is String -> item.takeIf(String::isNotBlank)?.let(::add)
                        is JSONObject -> {
                            sequenceOf("id", "model", "name")
                                .map { key -> item.optString(key) }
                                .firstOrNull(String::isNotBlank)
                                ?.let(::add)
                        }
                    }
                }
            }

        runCatching { JSONObject(responseText) }.getOrNull()?.let { root ->
            root.optJSONArray("data")?.let { return parseArray(it) }
            root.optJSONArray("models")?.let { return parseArray(it) }
        }
        runCatching { JSONArray(responseText) }.getOrNull()?.let { return parseArray(it) }
        throw AiException(AiErrorCode.INVALID_RESPONSE, "AI 模型列表响应格式无法识别")
    }

    private fun execute(request: Request): String {
        val response =
            try {
                httpClient.client.newCall(request).execute()
            } catch (error: Throwable) {
                throw classifyNetworkError(error)
            }
        return readResponse(response)
    }

    private suspend fun executeCancellable(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        if (!continuation.isActive) return
                        continuation.resumeWith(Result.failure(classifyNetworkError(e)))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        continuation.resumeWith(
                            runCatching { readResponse(response) },
                        )
                    }
                }
            )
        }

    /** 真正消费 SSE 的可取消请求；最终仍返回完整结果，供既有摘要解析和缓存链复用。 */
    private suspend fun executeStreamingCancellable(
        request: Request,
        perfTrace: AiPerfTrace,
        onDelta: (AiCompletionDelta) -> Unit,
    ): AiCompletionResult =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        if (!continuation.isActive) return
                        continuation.resumeWith(Result.failure(classifyNetworkError(e)))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        val parsed = runCatching { readStreamingResponse(call, response, perfTrace, onDelta) }
                        if (!continuation.isActive) return
                        continuation.resumeWith(
                            parsed.fold(
                                onSuccess = { Result.success(it) },
                                onFailure = { error ->
                                    Result.failure(
                                        if (error is AiException) error else classifyNetworkError(error)
                                    )
                                },
                            )
                        )
                    }
                }
            )
        }

    /**
     * 顺序读取 OpenAI-compatible SSE；`data:` 之外的行只在尚未看到 SSE 时作为完整 JSON 回退缓冲。
     * 这样既兼容标准 `text/event-stream`，也兼容部分中转错误标成 `text/plain` 的流。
     */
    private fun readStreamingResponse(
        call: Call,
        response: Response,
        perfTrace: AiPerfTrace,
        onDelta: (AiCompletionDelta) -> Unit,
    ): AiCompletionResult =
        response.use { httpResponse ->
            ensureStreamingResponseSuccessful(httpResponse)
            val source =
                httpResponse.body?.source()
                    ?: throw AiException(AiErrorCode.INVALID_RESPONSE, "AI 服务返回了空响应")
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val fallbackBody = StringBuilder()
            var sawSseData = false
            var sawReasoning = false
            var sawContent = false

            while (!call.isCanceled()) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data:")) {
                    if (!sawSseData) {
                        sawSseData = true
                        AiPerfTracer.mark(perfTrace, "first_sse_event")
                    }
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isBlank()) continue
                    if (payload == "[DONE]") break
                    val delta = parseCompletionStreamPayload(payload) ?: continue
                    if (delta.reasoning.isNotEmpty()) {
                        reasoning.append(delta.reasoning)
                        if (!sawReasoning) {
                            sawReasoning = true
                            AiPerfTracer.mark(perfTrace, "first_reasoning_delta")
                        }
                    }
                    if (delta.content.isNotEmpty()) {
                        content.append(delta.content)
                        if (!sawContent) {
                            sawContent = true
                            AiPerfTracer.mark(perfTrace, "first_content_delta")
                        }
                    }
                    if (delta.content.isNotEmpty() || delta.reasoning.isNotEmpty()) {
                        onDelta(delta)
                    }
                } else if (!sawSseData && line.isNotBlank()) {
                    if (fallbackBody.isNotEmpty()) fallbackBody.append('\n')
                    fallbackBody.append(line)
                }
            }

            if (call.isCanceled()) {
                throw java.io.IOException("AI 请求已取消")
            }

            val completion =
                if (!sawSseData) {
                    AiPerfTracer.mark(
                        perfTrace,
                        "non_streaming_fallback",
                        "responseChars" to fallbackBody.length,
                    )
                    parseCompletionResponse(fallbackBody.toString()).also { parsed ->
                        onDelta(
                            AiCompletionDelta(
                                content = parsed.content,
                                reasoning = parsed.reasoning.orEmpty(),
                            )
                        )
                    }
                } else {
                    splitThinkContent(
                        content = content.toString(),
                        explicitReasoning = reasoning.toString().takeIf(String::isNotBlank),
                    ).also { parsed ->
                        if (parsed.content.isBlank()) {
                            throw AiException(AiErrorCode.INVALID_RESPONSE, "AI 服务返回了空内容")
                        }
                    }
                }
            AiPerfTracer.mark(
                perfTrace,
                "stream_complete",
                "contentChars" to completion.content.length,
                "reasoningChars" to completion.reasoning.orEmpty().length,
            )
            completion
        }

    /** 解析 Chat Completions 的单个 SSE `data:` JSON。 */
    private fun parseCompletionStreamPayload(payload: String): AiCompletionDelta? {
        val root =
            runCatching { JSONObject(payload) }
                .getOrElse {
                    throw AiException(AiErrorCode.INVALID_RESPONSE, "AI 流式响应不是有效 JSON", it)
                }
        root.optJSONObject("error")?.let { error ->
            throw AiException(
                AiErrorCode.INVALID_RESPONSE,
                error.optString("message").ifBlank { "AI 服务返回错误" },
            )
        }
        val first = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = first.optJSONObject("delta") ?: first.optJSONObject("message") ?: return null
        return AiCompletionDelta(
            content = parseContent(delta.opt("content")),
            reasoning =
                parseContent(delta.opt("reasoning_content"))
                    .ifBlank { parseContent(delta.opt("reasoning")) },
        ).takeIf { it.content.isNotEmpty() || it.reasoning.isNotEmpty() }
    }

    /** Streaming 请求非 2xx 时沿用现有错误分类，并保留服务端错误正文中的 message。 */
    private fun ensureStreamingResponseSuccessful(response: Response) {
        if (response.isSuccessful) return
        val body = response.body?.string().orEmpty()
        val detail = extractErrorDetail(body)
        val message =
            "HTTP ${response.code}" +
                detail.takeIf(String::isNotBlank)?.let { value -> ": $value" }.orEmpty()
        val code =
            when (response.code) {
                400, 404, 405, 422 -> AiErrorCode.INVALID_REQUEST
                401, 403 -> AiErrorCode.AUTHENTICATION
                429 -> AiErrorCode.RATE_LIMIT
                in 500..599 -> AiErrorCode.SERVICE_UNAVAILABLE
                else -> AiErrorCode.NETWORK
            }
        throw AiException(code, message)
    }

    private fun readResponse(response: Response): String {
        response.use {
            val perfTrace = it.request.tag(AiPerfRequestTag::class.java)?.trace
            perfTrace?.let { trace -> AiPerfTracer.mark(trace, "response_read_start") }
            val body = it.body?.string().orEmpty()
            perfTrace?.let { trace ->
                AiPerfTracer.mark(
                    trace,
                    "response_read_complete",
                    "responseChars" to body.length,
                )
            }
            if (!it.isSuccessful) {
                val detail = extractErrorDetail(body)
                val message = "HTTP ${it.code}" + detail.takeIf(String::isNotBlank)?.let { value -> ": $value" }.orEmpty()
                val code =
                    when (it.code) {
                        400, 404, 405, 422 -> AiErrorCode.INVALID_REQUEST
                        401, 403 -> AiErrorCode.AUTHENTICATION
                        429 -> AiErrorCode.RATE_LIMIT
                        in 500..599 -> AiErrorCode.SERVICE_UNAVAILABLE
                        else -> AiErrorCode.NETWORK
                    }
                throw AiException(code, message)
            }
            return body
        }
    }

    private fun classifyNetworkError(error: Throwable): AiException =
        when (error) {
            is UnknownHostException -> AiException(AiErrorCode.NETWORK, "无法解析 AI 服务域名", error)
            is ConnectException -> AiException(AiErrorCode.NETWORK, "无法连接 AI 服务", error)
            is SocketTimeoutException -> AiException(AiErrorCode.NETWORK, "AI 请求超时", error)
            is SSLHandshakeException -> AiException(AiErrorCode.NETWORK, "AI 服务 TLS 握手失败", error)
            is SSLPeerUnverifiedException -> AiException(AiErrorCode.NETWORK, "AI 服务证书校验失败", error)
            else ->
                AiException(
                    AiErrorCode.NETWORK,
                    error.message?.takeIf(String::isNotBlank)
                        ?: "AI 网络请求失败（${error.javaClass.simpleName}）",
                    error,
                )
        }

    private fun extractErrorDetail(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
                val json = JSONObject(body)
                val error = json.opt("error")
                when (error) {
                    is JSONObject -> error.optString("message")
                    is String -> error
                    else -> json.optString("message")
                }
            }
            .getOrDefault(body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim())
            .take(240)
    }
}

