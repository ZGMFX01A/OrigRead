package me.ash.reader.llm.runtime

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.ai.AiException
import me.ash.reader.infrastructure.ai.AiErrorCode
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiRuntimeConfig
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.resolvedDefaultModel

@Singleton
class OpenAiCompatibleLlmAdapter @Inject constructor(
    private val settingsRepository: AiSettingsRepository,
    private val capabilityResolver: ModelCapabilityResolver,
) {

    fun resolveProvider(providerId: String?): AiProviderProfile {
        val settings = settingsRepository.current()
        val provider =
            if (providerId.isNullOrBlank()) {
                settings.defaultProvider()
            } else {
                settings.providers.firstOrNull { it.id == providerId }
            }
        return provider
            ?: throw AiException(AiErrorCode.NOT_CONFIGURED, "没有可用的 AI 服务")
    }

    fun resolveModel(provider: AiProviderProfile, requestedModel: String?): String {
        val model =
            requestedModel
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: provider.resolvedDefaultModel().orEmpty()
        if (model.isBlank()) {
            throw AiException(AiErrorCode.NOT_CONFIGURED, "所选 AI 服务没有可用模型")
        }
        return model
    }

    fun runtimeConfig(provider: AiProviderProfile, model: String): AiRuntimeConfig {
        if (!provider.enabled) {
            throw AiException(AiErrorCode.DISABLED, "所选 AI 服务未启用")
        }
        if (provider.endpoint.isBlank()) {
            throw AiException(AiErrorCode.NOT_CONFIGURED, "所选 AI 服务地址为空")
        }
        return settingsRepository.runtimeConfig(
            providerId = provider.id,
            modelOverride = model,
        )
    }

    fun capability(
        provider: AiProviderProfile,
        model: String,
        override: ModelCapabilityOverride?,
    ): ModelCapability = capabilityResolver.resolve(provider, model, override)

    fun reasoningParameter(
        capability: ModelCapability,
        requested: LlmReasoningEffort,
    ): ProviderReasoningParameter? {
        if (requested == LlmReasoningEffort.AUTO) return null
        if (requested !in capability.supportedReasoningEfforts) return null

        return when (capability.reasoningParameterStyle) {
            ReasoningParameterStyle.NONE -> null
            ReasoningParameterStyle.OPENAI_REASONING_EFFORT ->
                ProviderReasoningParameter(
                    key = "reasoning_effort",
                    value = requested.toOpenAiReasoningValue() ?: return null,
                )
        }
    }
}

private fun LlmReasoningEffort.toOpenAiReasoningValue(): String? =
    when (this) {
        LlmReasoningEffort.LOW -> "low"
        LlmReasoningEffort.MEDIUM -> "medium"
        LlmReasoningEffort.HIGH -> "high"
        LlmReasoningEffort.AUTO,
        LlmReasoningEffort.MINIMAL,
        LlmReasoningEffort.MAXIMUM -> null
    }
