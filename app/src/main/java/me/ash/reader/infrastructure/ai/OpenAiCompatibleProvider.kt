package me.ash.reader.infrastructure.ai

import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** OpenAI Compatible 一次文本生成的结构化结果。reasoning 只保存供应商明确返回给客户端的内容。 */
data class AiCompletionResult(
    val content: String,
    val reasoning: String? = null,
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
    ): AiCompletionResult {
        val body =
            JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("temperature", 0.2)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt)),
                )
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder =
            Request.Builder()
                .url(resolveChatCompletionsEndpoint(config.endpoint))
                .header("Accept", "application/json")
                .post(body)
        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }

        val responseText = execute(requestBuilder.build())
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
        response.use {
            val body = it.body?.string().orEmpty()
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

