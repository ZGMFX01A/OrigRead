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
                            providerId = provider.id,
                            endpoint = provider.endpoint,
                            model = model,
                            outputLanguage = outputLanguage,
                            length = length,
                            contextWindowTokens = provider.contextWindowTokens,
                            promptVariant = promptVariant,
                        )
                    if (!file.exists()) return@withContext null
                    val document = decode(file.readText())
                    // reasoning-only 回归曾可能写入 GENERATED + 空 summary。摘要缓存可再生成，
                    // 直接丢弃坏缓存，避免修复后继续命中“已完成但正文空白”的旧结果。
                    if (document.status == AiSummaryStatus.GENERATED && document.summary.isBlank()) {
                        file.delete()
                        null
                    } else {
                        document
                    }
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
                        providerId = provider.id,
                        endpoint = provider.endpoint,
                        model = document.model,
                        outputLanguage = document.outputLanguage,
                        length = document.length,
                        contextWindowTokens = provider.contextWindowTokens,
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
        providerId: String,
        endpoint: String,
        model: String,
        outputLanguage: String,
        length: AiSummaryLength,
        contextWindowTokens: Int,
        promptVariant: String,
    ): File {
        val keyParts =
            mutableListOf(
                CACHE_VERSION,
                articleId,
                sha256(title),
                sha256(content),
                providerId.trim(),
                sha256(endpoint.trim()),
                model.trim(),
                outputLanguage.trim(),
                length.name,
                contextWindowTokens.toString(),
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
        // v12 纳入 Provider 窗口并隔离结构安全采样/动态输入预算，避免窗口变化命中旧摘要。
        private const val CACHE_VERSION = "12"
    }
}

