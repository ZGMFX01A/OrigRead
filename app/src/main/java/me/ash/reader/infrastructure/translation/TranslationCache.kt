package me.ash.reader.infrastructure.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.ai.AiTaskPromptCustomization
import me.ash.reader.infrastructure.di.IODispatcher
import org.json.JSONObject

/** 译文是可再生成数据，按账户写入缓存目录，不进入文章同步数据库。 */
@Singleton
class TranslationCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountService: AccountService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun read(
        articleId: String,
        title: String,
        content: String,
        target: TranslationTarget,
        targetLanguage: String,
        mode: TranslationDisplayMode,
        promptVariant: String = AiTaskPromptCustomization.DEFAULT_CACHE_VARIANT,
    ): TranslationDocument? =
        withContext(ioDispatcher) {
            runCatching {
                    val file =
                        cacheFile(
                            articleId,
                            title,
                            content,
                            target,
                            targetLanguage,
                            mode,
                            promptVariant,
                        )
                    if (!file.exists()) return@withContext null
                    decode(file.readText())
                }
                .getOrNull()
        }

    suspend fun write(
        title: String,
        content: String,
        document: TranslationDocument,
        promptVariant: String = AiTaskPromptCustomization.DEFAULT_CACHE_VARIANT,
    ) =
        withContext(ioDispatcher) {
            runCatching {
                val file =
                    cacheFile(
                        document.articleId,
                        title,
                        content,
                        document.target,
                        document.targetLanguage,
                        document.displayMode,
                        promptVariant,
                    )
                file.parentFile?.mkdirs()
                file.writeText(encode(document))
            }
        }

    private fun cacheFile(
        articleId: String,
        title: String,
        content: String,
        target: TranslationTarget,
        targetLanguage: String,
        mode: TranslationDisplayMode,
        promptVariant: String,
    ): File {
        val keyParts =
            mutableListOf(
                CACHE_VERSION,
                articleId,
                sha256(title),
                sha256(content),
                target.cacheKey(),
                targetLanguage,
                mode.name,
            )
        // 默认 Prompt 保留既有缓存 Key；只有实际使用 Skill 时才追加变体，避免普通译文缓存整体失效。
        if (promptVariant != AiTaskPromptCustomization.DEFAULT_CACHE_VARIANT) {
            keyParts += promptVariant
        }
        val rawKey = keyParts.joinToString(":")
        val fileName = sha256(rawKey) + ".json"
        return context.cacheDir
            .resolve("translations")
            .resolve(accountService.getCurrentAccountId().toString())
            .resolve(fileName)
    }

    private fun encode(document: TranslationDocument): String =
        JSONObject()
            .put("articleId", document.articleId)
            .put("target", encodeTarget(document.target))
            .put("targetLanguage", document.targetLanguage)
            .put("sourceLanguage", document.sourceLanguage)
            .put("displayMode", document.displayMode.name)
            .put("translatedTitle", document.translatedTitle)
            .put("translatedContent", document.translatedContent)
            .toString()

    private fun decode(value: String): TranslationDocument {
        val json = JSONObject(value)
        return TranslationDocument(
            articleId = json.getString("articleId"),
            target = decodeTarget(json.getJSONObject("target")),
            targetLanguage = json.getString("targetLanguage"),
            sourceLanguage = json.optString("sourceLanguage").takeIf { it.isNotBlank() },
            displayMode = TranslationDisplayMode.valueOf(json.getString("displayMode")),
            translatedTitle = json.getString("translatedTitle"),
            translatedContent = json.getString("translatedContent"),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun TranslationTarget.cacheKey(): String =
        when (this) {
            is TranslationTarget.Traditional -> "traditional:${provider.name}"
            is TranslationTarget.Ai -> "ai:$providerId:$model"
        }

    private fun encodeTarget(target: TranslationTarget): JSONObject =
        when (target) {
            is TranslationTarget.Traditional ->
                JSONObject()
                    .put("type", "traditional")
                    .put("provider", target.provider.name)
            is TranslationTarget.Ai ->
                JSONObject()
                    .put("type", "ai")
                    .put("providerId", target.providerId)
                    .put("providerName", target.providerName)
                    .put("model", target.model)
        }

    private fun decodeTarget(json: JSONObject): TranslationTarget =
        when (json.getString("type")) {
            "traditional" ->
                TranslationTarget.Traditional(
                    TranslationProviderType.valueOf(json.getString("provider"))
                )
            "ai" ->
                TranslationTarget.Ai(
                    providerId = json.getString("providerId"),
                    providerName = json.optString("providerName").ifBlank { "AI" },
                    model = json.getString("model"),
                )
            else -> error("未知翻译目标类型")
        }

    companion object {
        private const val CACHE_VERSION = "2"
    }
}

