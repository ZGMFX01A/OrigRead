package me.ash.reader.infrastructure.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.di.IODispatcher
import org.json.JSONObject

/** AI 摘要是可再生成数据，只写入按账户隔离的缓存目录。 */
@Singleton
class AiSummaryCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountService: AccountService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun read(
        articleId: String,
        title: String,
        content: String,
        provider: AiProviderProfile,
        model: String,
        outputLanguage: String,
        length: AiSummaryLength,
    ): AiSummaryDocument? =
        withContext(ioDispatcher) {
            runCatching {
                    val file =
                        cacheFile(
                            articleId = articleId,
                            title = title,
                            content = content,
                            endpoint = provider.endpoint,
                            model = model,
                            outputLanguage = outputLanguage,
                            length = length,
                        )
                    if (!file.exists()) return@withContext null
                    decode(file.readText())
                }
                .getOrNull()
        }

    suspend fun write(
        title: String,
        content: String,
        provider: AiProviderProfile,
        document: AiSummaryDocument,
    ) =
        withContext(ioDispatcher) {
            runCatching {
                val file =
                    cacheFile(
                        articleId = document.articleId,
                        title = title,
                        content = content,
                        endpoint = provider.endpoint,
                        model = document.model,
                        outputLanguage = document.outputLanguage,
                        length = document.length,
                    )
                file.parentFile?.mkdirs()
                file.writeText(encode(document))
            }
        }

    private fun cacheFile(
        articleId: String,
        title: String,
        content: String,
        endpoint: String,
        model: String,
        outputLanguage: String,
        length: AiSummaryLength,
    ): File {
        val rawKey =
            listOf(
                    CACHE_VERSION,
                    articleId,
                    sha256(title),
                    sha256(content),
                    sha256(endpoint.trim()),
                    model.trim(),
                    outputLanguage.trim(),
                    length.name,
                )
                .joinToString(":")
        return context.cacheDir
            .resolve("ai_summaries")
            .resolve(accountService.getCurrentAccountId().toString())
            .resolve(sha256(rawKey) + ".json")
    }

    private fun encode(document: AiSummaryDocument): String =
        JSONObject()
            .put("articleId", document.articleId)
            .put("providerId", document.providerId)
            .put("providerName", document.providerName)
            .put("model", document.model)
            .put("outputLanguage", document.outputLanguage)
            .put("length", document.length.name)
            .put("summary", document.summary)
            .put("reasoning", document.reasoning ?: JSONObject.NULL)
            .toString()

    private fun decode(value: String): AiSummaryDocument {
        val json = JSONObject(value)
        return AiSummaryDocument(
            articleId = json.getString("articleId"),
            providerId = json.optString("providerId", DEFAULT_AI_PROVIDER_ID),
            providerName = json.optString("providerName", "AI 服务"),
            model = json.getString("model"),
            outputLanguage = json.getString("outputLanguage"),
            length = AiSummaryLength.valueOf(json.getString("length")),
            summary = json.getString("summary"),
            reasoning = json.optString("reasoning").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        // v4 开始缓存供应商显式返回的 reasoning，旧摘要安全失效后重新生成。
        private const val CACHE_VERSION = "4"
    }
}

