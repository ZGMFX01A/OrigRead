package me.ash.reader.infrastructure.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** 零云端密钥的设备端翻译实现，语言模型按需下载。 */
@Singleton
class MlKitTranslationProvider @Inject constructor() : TranslationProvider {
    override val type: TranslationProviderType = TranslationProviderType.ML_KIT
    override val maxBatchItems: Int = 50
    override val maxBatchCharacters: Int = 20_000
    override val maxSegmentCharacters: Int = 4_000

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        config: TranslationRuntimeConfig,
    ): TranslationBatchResult {
        if (texts.isEmpty()) return TranslationBatchResult(emptyList())
        val detectedSource = sourceLanguage ?: detectLanguage(texts.joinToString(" ").take(1_000))
        val source = toMlKitLanguage(detectedSource)
        val target = toMlKitLanguage(targetLanguage)
        if (source == target) return TranslationBatchResult(texts, detectedSource)

        val translator =
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        try {
            runCatching {
                    translator
                        .downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .awaitResult()
                }
                .getOrElse {
                    throw TranslationException(
                        TranslationErrorCode.MODEL_DOWNLOAD,
                        "语言模型下载失败",
                        it,
                    )
                }
            val translated =
                texts.map { text ->
                    if (text.isBlank()) text else translator.translate(text).awaitResult()
                }
            return TranslationBatchResult(translated, detectedSource)
        } finally {
            translator.close()
        }
    }

    private suspend fun detectLanguage(text: String): String {
        if (text.isBlank()) {
            throw TranslationException(TranslationErrorCode.EMPTY_CONTENT, "没有可翻译的文本")
        }
        val identifier = LanguageIdentification.getClient()
        return try {
            val language = identifier.identifyLanguage(text).awaitResult()
            if (language == "und") {
                throw TranslationException(
                    TranslationErrorCode.UNSUPPORTED_LANGUAGE,
                    "无法识别原文语言",
                )
            }
            language
        } finally {
            identifier.close()
        }
    }

    private fun toMlKitLanguage(languageTag: String): String {
        val normalized =
            when (languageTag.lowercase()) {
                "zh-cn", "zh-hans", "zh-sg" -> "zh"
                "zh-tw", "zh-hant", "zh-hk" -> "zh"
                else -> languageTag.substringBefore('-').lowercase()
            }
        return TranslateLanguage.fromLanguageTag(normalized)
            ?: throw TranslationException(
                TranslationErrorCode.UNSUPPORTED_LANGUAGE,
                "ML Kit 不支持语言：$languageTag",
            )
    }
}

private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> continuation.resume(result) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

