package me.ash.reader.infrastructure.translation

import java.util.Locale

/** 原读当前支持的传统翻译实现。 */
enum class TranslationProviderType {
    ML_KIT,
    MICROSOFT,
    DEEPL,
    GOOGLE_CLOUD,
    DLX,
}

/** Provider 品牌名称不参与本地化，统一供设置页和阅读页菜单展示。 */
val TranslationProviderType.displayName: String
    get() =
        when (this) {
            TranslationProviderType.ML_KIT -> "Google ML Kit"
            TranslationProviderType.MICROSOFT -> "Microsoft Translator"
            TranslationProviderType.DEEPL -> "DeepL"
            TranslationProviderType.GOOGLE_CLOUD -> "Google Cloud Translation"
            TranslationProviderType.DLX -> "DeepLX / DLX"
        }

/**
 * 阅读页统一的“翻译目标”。
 * 传统 Provider 可以立即执行；AI Provider + Model 先共用同一选择模型，
 * 后续接入 AI 全文翻译时无需再次推翻阅读页和选择器的数据结构。
 */
sealed interface TranslationTarget {
    data class Traditional(
        val provider: TranslationProviderType,
    ) : TranslationTarget

    data class Ai(
        val providerId: String,
        val providerName: String,
        val model: String,
    ) : TranslationTarget
}

/** 统一给阅读页选择器展示的名称。 */
val TranslationTarget.displayName: String
    get() =
        when (this) {
            is TranslationTarget.Traditional -> provider.displayName
            is TranslationTarget.Ai -> "$providerName · $model"
        }

/** 阅读页译文展示方式。 */
enum class TranslationDisplayMode {
    TRANSLATED,
    BILINGUAL,
}

data class TranslationProviderSettings(
    val enabled: Boolean,
    val endpoint: String,
    val region: String = "",
)

data class TranslationSettings(
    val defaultProvider: TranslationProviderType = TranslationProviderType.ML_KIT,
    val defaultTarget: TranslationTarget =
        TranslationTarget.Traditional(TranslationProviderType.ML_KIT),
    val targetLanguage: String = defaultTargetLanguage(),
    val displayMode: TranslationDisplayMode = TranslationDisplayMode.TRANSLATED,
    val providers: Map<TranslationProviderType, TranslationProviderSettings> = defaultProviderSettings(),
) {
    fun provider(type: TranslationProviderType): TranslationProviderSettings =
        providers[type] ?: defaultProviderSettings().getValue(type)

    companion object {
        fun defaultTargetLanguage(): String =
            Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "zh-CN"

        fun defaultProviderSettings(): Map<TranslationProviderType, TranslationProviderSettings> =
            mapOf(
                TranslationProviderType.ML_KIT to
                    TranslationProviderSettings(enabled = true, endpoint = ""),
                TranslationProviderType.MICROSOFT to
                    TranslationProviderSettings(
                        enabled = false,
                        endpoint = "https://api.cognitive.microsofttranslator.com",
                    ),
                TranslationProviderType.DEEPL to
                    TranslationProviderSettings(
                        enabled = false,
                        endpoint = "https://api-free.deepl.com/v2/translate",
                    ),
                TranslationProviderType.GOOGLE_CLOUD to
                    TranslationProviderSettings(
                        enabled = false,
                        endpoint = "https://translation.googleapis.com/language/translate/v2",
                    ),
                TranslationProviderType.DLX to
                    TranslationProviderSettings(enabled = false, endpoint = ""),
            )
    }
}

data class TranslationRuntimeConfig(
    val endpoint: String,
    val region: String,
    val apiKey: String,
)

data class TranslationBatchResult(
    val texts: List<String>,
    val detectedSourceLanguage: String? = null,
)

/** DeepL 当前计费周期的字符用量。DeepL API 不返回货币余额，只返回字符额度。 */
data class DeepLUsage(
    val characterCount: Long,
    val characterLimit: Long,
) {
    val remainingCharacters: Long
        get() = (characterLimit - characterCount).coerceAtLeast(0L)

    val usagePercent: Double
        get() =
            if (characterLimit <= 0L) 0.0
            else characterCount.toDouble() / characterLimit.toDouble() * 100.0
}

data class TranslationDocument(
    val articleId: String,
    val target: TranslationTarget,
    val targetLanguage: String,
    val sourceLanguage: String?,
    val displayMode: TranslationDisplayMode,
    val translatedTitle: String,
    val translatedContent: String,
) {
    /** 兼容只关心传统 Provider 的旧调用点。AI 翻译时返回 null。 */
    val provider: TranslationProviderType?
        get() = (target as? TranslationTarget.Traditional)?.provider
}

enum class TranslationErrorCode {
    EMPTY_CONTENT,
    PROVIDER_DISABLED,
    PROVIDER_NOT_CONFIGURED,
    UNSUPPORTED_LANGUAGE,
    NETWORK,
    AUTHENTICATION,
    RATE_LIMITED,
    INVALID_RESPONSE,
    MODEL_DOWNLOAD,
    UNKNOWN,
}

class TranslationException(
    val code: TranslationErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

