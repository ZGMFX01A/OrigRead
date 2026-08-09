package me.ash.reader.infrastructure.ai

import java.util.Locale

const val DEFAULT_AI_PROVIDER_ID = "default"

/** 当前文章摘要支持的输出详细程度。 */
enum class AiSummaryLength {
    BRIEF,
    STANDARD,
    DETAILED,
}

/**
 * 单个 OpenAI Compatible 服务配置。
 * 供应商本身只描述地址、凭据和可用模型集合；defaultModel 只是该供应商的默认选择，
 * 阅读页重新生成时可以从 models 中临时切换其他模型而不改写默认值。
 */
data class AiProviderProfile(
    val id: String = DEFAULT_AI_PROVIDER_ID,
    val name: String = "默认服务",
    val enabled: Boolean = true,
    val endpoint: String = "https://api.openai.com/v1",
    val defaultModel: String = "",
    val models: List<String> = emptyList(),
)

/** 统一清理服务端模型列表，并确保默认模型不会因为列表接口遗漏而消失。 */
fun AiProviderProfile.availableModels(): List<String> =
    (listOf(defaultModel) + models)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

/** 默认模型为空时回退到已发现模型的第一项，避免可用服务因为配置迁移而被整个阅读页隐藏。 */
fun AiProviderProfile.resolvedDefaultModel(): String? =
    defaultModel.trim().takeIf(String::isNotBlank)
        ?: availableModels().firstOrNull()

/** AI 阅读全局配置；具体服务参数由 providers 管理。 */
data class AiSettings(
    val enabled: Boolean = false,
    val providers: List<AiProviderProfile> = listOf(AiProviderProfile()),
    val defaultProviderId: String = DEFAULT_AI_PROVIDER_ID,
    val outputLanguage: String = defaultOutputLanguage(),
    val summaryLength: AiSummaryLength = AiSummaryLength.STANDARD,
) {
    fun defaultProvider(): AiProviderProfile? =
        providers.firstOrNull { it.id == defaultProviderId && it.enabled }
            ?: providers.firstOrNull { it.enabled }
            ?: providers.firstOrNull { it.id == defaultProviderId }
            ?: providers.firstOrNull()

    companion object {
        fun defaultOutputLanguage(): String =
            Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" }
    }
}

data class AiRuntimeConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
)

/** 非流式请求下可观测的摘要生成阶段，用于向用户反馈服务仍在工作。 */
enum class AiSummaryProgressStage {
    PREPARING,
    GENERATING,
    FINALIZING,
}

/** 单篇文章摘要结果。 */
data class AiSummaryDocument(
    val articleId: String,
    val providerId: String,
    val providerName: String,
    val model: String,
    val outputLanguage: String,
    val length: AiSummaryLength,
    val summary: String,
    /** 供应商明确返回给客户端的推理/思考文本；为空时 UI 不展示相关入口。 */
    val reasoning: String? = null,
)

enum class AiErrorCode {
    DISABLED,
    NOT_CONFIGURED,
    INVALID_REQUEST,
    AUTHENTICATION,
    RATE_LIMIT,
    NETWORK,
    SERVICE_UNAVAILABLE,
    INVALID_RESPONSE,
}

class AiException(
    val code: AiErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

