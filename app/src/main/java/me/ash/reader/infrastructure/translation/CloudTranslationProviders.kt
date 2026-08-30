package me.ash.reader.infrastructure.translation

import java.net.URLEncoder
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import me.ash.reader.infrastructure.ai.awaitResponseAndUse

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

abstract class BaseHttpTranslationProvider(
    private val httpClient: TranslationHttpClient,
) : TranslationProvider {
    protected fun executeJson(request: Request): String =
        runCatching {
                httpClient.client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    when {
                        response.code == 401 || response.code == 403 ->
                            throw TranslationException(
                                TranslationErrorCode.AUTHENTICATION,
                                responseMessage("翻译服务鉴权失败", response.code, body),
                            )
                        response.code == 429 || response.code == 456 ->
                            throw TranslationException(
                                TranslationErrorCode.RATE_LIMITED,
                                responseMessage("翻译服务请求过于频繁或额度已用尽", response.code, body),
                            )
                        response.code == 404 || response.code == 405 ->
                            throw TranslationException(
                                TranslationErrorCode.PROVIDER_NOT_CONFIGURED,
                                responseMessage("翻译服务地址或接口路径不正确", response.code, body),
                            )
                        response.code == 400 || response.code == 422 ->
                            throw TranslationException(
                                TranslationErrorCode.INVALID_RESPONSE,
                                responseMessage("翻译服务拒绝了当前请求参数", response.code, body),
                            )
                        !response.isSuccessful ->
                            throw TranslationException(
                                TranslationErrorCode.NETWORK,
                                responseMessage("翻译服务请求失败", response.code, body),
                            )
                    }
                    body
                }
            }
            .getOrElse { error ->
                if (error is TranslationException) throw error
                throw TranslationException(
                    TranslationErrorCode.NETWORK,
                    networkErrorMessage(error, request.url.host),
                    error,
                )
            }

    /** 协程路径使用真取消，且保持既有 timeout 和错误分类。 */
    protected suspend fun executeJsonCancellable(request: Request): String =
        try {
            httpClient.client.newCall(request).awaitResponseAndUse { response ->
                val body = response.body.string()
                when {
                    response.code == 401 || response.code == 403 ->
                        throw TranslationException(
                            TranslationErrorCode.AUTHENTICATION,
                            responseMessage("翻译服务鉴权失败", response.code, body),
                        )
                    response.code == 429 || response.code == 456 ->
                        throw TranslationException(
                            TranslationErrorCode.RATE_LIMITED,
                            responseMessage("翻译服务请求过于频繁或额度已用尽", response.code, body),
                        )
                    response.code == 404 || response.code == 405 ->
                        throw TranslationException(
                            TranslationErrorCode.PROVIDER_NOT_CONFIGURED,
                            responseMessage("翻译服务地址或接口路径不正确", response.code, body),
                        )
                    response.code == 400 || response.code == 422 ->
                        throw TranslationException(
                            TranslationErrorCode.INVALID_RESPONSE,
                            responseMessage("翻译服务拒绝了当前请求参数", response.code, body),
                        )
                    !response.isSuccessful ->
                        throw TranslationException(
                            TranslationErrorCode.NETWORK,
                            responseMessage("翻译服务请求失败", response.code, body),
                        )
                }
                body
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (error is TranslationException) throw error
            throw TranslationException(
                TranslationErrorCode.NETWORK,
                networkErrorMessage(error, request.url.host),
                error,
            )
        }

    /** 尽可能保留服务端返回的短错误信息，避免所有问题都只显示为“网络失败”。 */
    private fun responseMessage(prefix: String, status: Int, body: String): String {
        val detail = extractErrorDetail(body)
        return buildString {
            append(prefix)
            append("（HTTP ")
            append(status)
            append('）')
            if (!detail.isNullOrBlank()) {
                append("：")
                append(detail)
            }
        }
    }

    private fun extractErrorDetail(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return null
        val jsonDetail =
            runCatching {
                    val json = JSONObject(trimmed)
                    json.optString("message").ifBlank {
                        json.optString("detail").ifBlank {
                            json.optString("description").ifBlank {
                                json.optJSONObject("error")?.optString("message").orEmpty()
                            }
                        }
                    }
                }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        if (jsonDetail != null) return jsonDetail.take(MAX_ERROR_DETAIL_LENGTH)
        return Jsoup.parse(trimmed).text().trim().take(MAX_ERROR_DETAIL_LENGTH).ifBlank { null }
    }

    private fun networkErrorMessage(error: Throwable, host: String): String =
        when (error) {
            is UnknownHostException -> "无法解析翻译服务域名：$host"
            is ConnectException -> "无法连接翻译服务：$host"
            is SocketTimeoutException -> "连接翻译服务超时：$host"
            is SSLPeerUnverifiedException -> "翻译服务证书校验失败：$host"
            is SSLHandshakeException -> "与翻译服务建立 TLS 连接失败：$host"
            else ->
                buildString {
                    append("翻译服务网络请求失败")
                    error.message?.takeIf { it.isNotBlank() }?.let {
                        append("：")
                        append(it.take(MAX_ERROR_DETAIL_LENGTH))
                    }
                }
        }

    protected fun requireApiKey(config: TranslationRuntimeConfig): String =
        config.apiKey.ifBlank {
            throw TranslationException(
                TranslationErrorCode.PROVIDER_NOT_CONFIGURED,
                "尚未配置 API Key",
            )
        }

    protected fun requireEndpoint(config: TranslationRuntimeConfig): String =
        config.endpoint.trimEnd('/').ifBlank {
            throw TranslationException(
                TranslationErrorCode.PROVIDER_NOT_CONFIGURED,
                "尚未配置服务地址",
            )
        }

    companion object {
        private const val MAX_ERROR_DETAIL_LENGTH = 240
    }
}

/**
 * DeepL Free Key 以 `:fx` 结尾。仅在用户仍使用官方默认域名时自动纠正 Free/Pro 域名，
 * 自定义代理和区域端点保持原样。
 */
internal fun resolveDeepLEndpoint(endpoint: String, apiKey: String): String {
    val url = endpoint.toHttpUrlOrNull() ?: return endpoint
    val freeKey = apiKey.trim().endsWith(":fx", ignoreCase = true)
    val correctedHost =
        when {
            freeKey && url.host == "api.deepl.com" -> "api-free.deepl.com"
            !freeKey && url.host == "api-free.deepl.com" -> "api.deepl.com"
            else -> url.host
        }
    val builder = url.newBuilder().host(correctedHost)
    if (url.encodedPath == "/") {
        builder.encodedPath("/v2/translate")
    }
    return builder.build().toString()
}

/** 根据翻译接口地址推导 DeepL 用量接口，同时保留 Free/Pro 域名自动纠正。 */
internal fun resolveDeepLUsageEndpoint(endpoint: String, apiKey: String): String {
    val translateEndpoint = resolveDeepLEndpoint(endpoint, apiKey)
    val url = translateEndpoint.toHttpUrlOrNull()
        ?: return translateEndpoint.substringBefore('?').trimEnd('/')
            .removeSuffix("/translate") + "/usage"
    val path = url.encodedPath.trimEnd('/')
    val usagePath =
        when {
            path.endsWith("/translate") -> path.removeSuffix("/translate") + "/usage"
            path.endsWith("/usage") -> path
            path.isBlank() -> "/v2/usage"
            else -> "$path/v2/usage"
        }
    return url.newBuilder().encodedPath(usagePath).build().toString()
}

/** 在 URL path 中追加接口路径，确保 `?token=...` 等查询参数不会被错误拼接。 */
internal fun resolveDlxEndpoint(endpoint: String): String {
    val url = endpoint.toHttpUrlOrNull()
        ?: return if (endpoint.substringBefore('?').trimEnd('/').endsWith("/translate")) {
            endpoint
        } else {
            endpoint.substringBefore('?').trimEnd('/') + "/translate" +
                endpoint.substringAfter('?', "").takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        }
    val path = url.encodedPath.trimEnd('/')
    if (path.endsWith("/translate")) return url.toString()
    val translatedPath = if (path.isBlank()) "/translate" else "$path/translate"
    return url.newBuilder().encodedPath(translatedPath).build().toString()
}

@Singleton
class MicrosoftTranslationProvider @Inject constructor(
    httpClient: TranslationHttpClient,
) : BaseHttpTranslationProvider(httpClient) {
    override val type = TranslationProviderType.MICROSOFT
    override val maxBatchItems = 100
    override val maxBatchCharacters = 40_000

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        config: TranslationRuntimeConfig,
    ): TranslationBatchResult {
        val target = microsoftLanguage(targetLanguage)
        val source = sourceLanguage?.let(::microsoftLanguage)
        val query =
            buildString {
                append("?api-version=3.0&to=")
                append(URLEncoder.encode(target, Charsets.UTF_8.name()))
                source?.let {
                    append("&from=")
                    append(URLEncoder.encode(it, Charsets.UTF_8.name()))
                }
            }
        val body = JSONArray().apply { texts.forEach { put(JSONObject().put("Text", it)) } }
        val builder =
            Request.Builder()
                .url(requireEndpoint(config) + "/translate" + query)
                .header("Ocp-Apim-Subscription-Key", requireApiKey(config))
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (config.region.isNotBlank()) {
            builder.header("Ocp-Apim-Subscription-Region", config.region)
        }
        val response = JSONArray(executeJsonCancellable(builder.build()))
        val translated =
            List(response.length()) { index ->
                response
                    .getJSONObject(index)
                    .getJSONArray("translations")
                    .getJSONObject(0)
                    .getString("text")
            }
        val detected =
            sourceLanguage
                ?: response.optJSONObject(0)?.optJSONObject("detectedLanguage")?.optString("language")
        return TranslationBatchResult(translated, detected)
    }

}

@Singleton
class DeepLTranslationProvider @Inject constructor(
    httpClient: TranslationHttpClient,
) : BaseHttpTranslationProvider(httpClient) {
    override val type = TranslationProviderType.DEEPL
    override val maxBatchItems = 50

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        config: TranslationRuntimeConfig,
    ): TranslationBatchResult {
        val apiKey = requireApiKey(config)
        val body =
            JSONObject()
                .put("text", JSONArray(texts))
                .put("target_lang", deepLLanguage(targetLanguage, target = true))
        sourceLanguage?.let { body.put("source_lang", deepLLanguage(it, target = false)) }
        val request =
            Request.Builder()
                .url(resolveDeepLEndpoint(requireEndpoint(config), apiKey))
                .header("Authorization", "DeepL-Auth-Key $apiKey")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        val translations = JSONObject(executeJsonCancellable(request)).getJSONArray("translations")
        val translated =
            List(translations.length()) { index -> translations.getJSONObject(index).getString("text") }
        val detected =
            sourceLanguage
                ?: translations.optJSONObject(0)?.optString("detected_source_language")
        return TranslationBatchResult(translated, detected)
    }

    /**
     * 查询 DeepL 当前计费周期的字符用量。
     * 该接口比发送一段测试翻译更适合用于验证凭据和服务连通性。
     */
    fun getUsage(config: TranslationRuntimeConfig): DeepLUsage {
        val apiKey = requireApiKey(config)
        val request =
            Request.Builder()
                .url(resolveDeepLUsageEndpoint(requireEndpoint(config), apiKey))
                .header("Authorization", "DeepL-Auth-Key $apiKey")
                .get()
                .build()
        val response = JSONObject(executeJson(request))
        if (!response.has("character_count") || !response.has("character_limit")) {
            throw TranslationException(
                TranslationErrorCode.INVALID_RESPONSE,
                "DeepL 用量接口返回内容不完整",
            )
        }
        return DeepLUsage(
            characterCount = response.getLong("character_count"),
            characterLimit = response.getLong("character_limit"),
        )
    }
}

@Singleton
class GoogleCloudTranslationProvider @Inject constructor(
    httpClient: TranslationHttpClient,
) : BaseHttpTranslationProvider(httpClient) {
    override val type = TranslationProviderType.GOOGLE_CLOUD
    override val maxBatchItems = 100

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        config: TranslationRuntimeConfig,
    ): TranslationBatchResult {
        val endpoint = requireEndpoint(config)
        val separator = if ('?' in endpoint) '&' else '?'
        val url =
            endpoint + separator + "key=" +
                URLEncoder.encode(requireApiKey(config), Charsets.UTF_8.name())
        val body =
            JSONObject()
                .put("q", JSONArray(texts))
                .put("target", googleLanguage(targetLanguage))
                .put("format", "text")
        sourceLanguage?.let { body.put("source", googleLanguage(it)) }
        val request =
            Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        val translations =
            JSONObject(executeJsonCancellable(request))
                .getJSONObject("data")
                .getJSONArray("translations")
        val translated =
            List(translations.length()) { index ->
                decodeHtmlEntities(translations.getJSONObject(index).getString("translatedText"))
            }
        val detected =
            sourceLanguage
                ?: translations.optJSONObject(0)?.optString("detectedSourceLanguage")
        return TranslationBatchResult(translated, detected)
    }
}

@Singleton
class DlxTranslationProvider @Inject constructor(
    httpClient: TranslationHttpClient,
) : BaseHttpTranslationProvider(httpClient) {
    override val type = TranslationProviderType.DLX
    override val maxBatchItems = 1
    override val maxBatchCharacters = 5_000

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        config: TranslationRuntimeConfig,
    ): TranslationBatchResult {
        val endpoint = resolveDlxEndpoint(requireEndpoint(config))
        val output =
            texts.map { text ->
                val body =
                    JSONObject()
                        .put("text", text)
                        .put("source_lang", sourceLanguage?.let(::dlxLanguage) ?: "auto")
                        .put("target_lang", dlxLanguage(targetLanguage))
                val builder =
                    Request.Builder()
                        .url(endpoint)
                        .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                if (config.apiKey.isNotBlank()) {
                    builder.header("Authorization", "Bearer ${config.apiKey}")
                }
                parseDlxResponse(executeJsonCancellable(builder.build()))
            }
        return TranslationBatchResult(output, sourceLanguage)
    }

    private fun parseDlxResponse(body: String): String {
        val json = JSONObject(body)
        json.optString("data").takeIf { it.isNotBlank() }?.let { return it }
        json.optString("translation").takeIf { it.isNotBlank() }?.let { return it }
        val translations = json.optJSONArray("translations")
        if (translations != null && translations.length() > 0) {
            val first = translations.get(0)
            return when (first) {
                is String -> first
                is JSONObject ->
                    first.optString("text").ifBlank { first.optString("translation") }
                else -> ""
            }.ifBlank {
                throw TranslationException(
                    TranslationErrorCode.INVALID_RESPONSE,
                    "DLX 返回了无法识别的译文结构",
                )
            }
        }
        throw TranslationException(
            TranslationErrorCode.INVALID_RESPONSE,
            "DLX 返回结果中没有译文",
        )
    }
}

private fun microsoftLanguage(tag: String): String =
    when (tag.lowercase()) {
        "zh-cn", "zh-sg", "zh-hans" -> "zh-Hans"
        "zh-tw", "zh-hk", "zh-hant" -> "zh-Hant"
        else -> tag
    }

private fun googleLanguage(tag: String): String =
    when (tag.lowercase()) {
        "zh-hans" -> "zh-CN"
        "zh-hant" -> "zh-TW"
        else -> tag
    }

private fun deepLLanguage(tag: String, target: Boolean): String {
    val normalized = tag.replace('_', '-').uppercase()
    return when {
        normalized in setOf("ZH-CN", "ZH-SG", "ZH-HANS") -> if (target) "ZH-HANS" else "ZH"
        normalized in setOf("ZH-TW", "ZH-HK", "ZH-HANT") -> if (target) "ZH-HANT" else "ZH"
        target && normalized == "EN" -> "EN-US"
        else -> normalized.substringBefore('-')
    }
}

private fun dlxLanguage(tag: String): String =
    when (tag.lowercase()) {
        "zh-cn", "zh-sg", "zh-hans", "zh-tw", "zh-hk", "zh-hant" -> "ZH"
        else -> tag.substringBefore('-').uppercase()
    }

private fun decodeHtmlEntities(value: String): String = Jsoup.parse(value).text()

