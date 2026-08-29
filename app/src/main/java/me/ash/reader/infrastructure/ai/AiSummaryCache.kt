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
        promptVariant: String = AiTaskPromptCustomization.DEFAULT_CACHE_VARIANT,
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
                            promptVariant = promptVariant,
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
        promptVariant: String = AiTaskPromptCustomization.DEFAULT_CACHE_VARIANT,
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
                        promptVariant = promptVariant,
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
        promptVariant: String,
    ): File {
        val keyParts =
            mutableListOf(
                CACHE_VERSION,
                articleId,
                sha256(title),
                sha256(content),
                sha256(endpoint.trim()),
                model.trim(),
                outputLanguage.trim(),
                length.name,
            )
        // 默认 Prompt 必须保持 P4 前完全相同的缓存 Key，避免 Standard/未绑定 Skill 的用户无故丢失旧摘要缓存。
        if (promptVariant != AiTaskPromptCustomization.DEFAULT_CACHE_VARIANT) {
            keyParts += promptVariant
        }
        val rawKey = keyParts.joinToString(":")
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
            .put("status", document.status.name)
            .put("articleForm", document.articleForm?.name ?: JSONObject.NULL)
            .put("domain", document.domain ?: JSONObject.NULL)
            .put("skipReason", document.skipReason?.name ?: JSONObject.NULL)
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
            status =
                json.optString("status")
                    .takeIf(String::isNotBlank)
                    ?.let { runCatching { AiSummaryStatus.valueOf(it) }.getOrNull() }
                    ?: AiSummaryStatus.GENERATED,
            articleForm =
                json.optString("articleForm")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { runCatching { AiArticleForm.valueOf(it) }.getOrNull() },
            domain = json.optString("domain").takeIf { it.isNotBlank() && it != "null" },
            skipReason =
                json.optString("skipReason")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { runCatching { AiSummarySkipReason.valueOf(it) }.getOrNull() },
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        // v9 对应当前英文摘要 Prompt 与 v2 metadata 协议。
        private const val CACHE_VERSION = "9"
    }
}

