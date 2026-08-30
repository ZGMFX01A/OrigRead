package me.ash.reader.llm.runtime

import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.resolveAiOutputTokenLimitStyle

@Singleton
class ModelCapabilityResolver @Inject constructor() {

    fun resolve(
        provider: AiProviderProfile,
        model: String,
        override: ModelCapabilityOverride? = null,
    ): ModelCapability {
        val base = resolveBuiltIn(provider, model.trim())
        return override?.applyTo(base) ?: base
    }

    private fun resolveBuiltIn(provider: AiProviderProfile, model: String): ModelCapability {
        val endpointHost =
            runCatching { URI(provider.endpoint.trim()).host.orEmpty().lowercase() }
                .getOrDefault("")
        val normalizedModel = model.lowercase()

        val capability = when {
            endpointHost == "api.openai.com" ->
                openAiCapability(normalizedModel)

            endpointHost == "api.deepseek.com" ->
                deepSeekCapability(normalizedModel)

            else -> ModelCapability(supportsStreaming = true)
        }
        return capability.copy(
            outputTokenLimitStyle =
                resolveAiOutputTokenLimitStyle(
                    endpoint = provider.endpoint,
                    model = model,
                    configuredStyle = provider.outputTokenLimitStyle,
                )
        )
    }

    private fun openAiCapability(model: String): ModelCapability {
        val reasoningModel =
            model.startsWith("o1") ||
                model.startsWith("o3") ||
                model.startsWith("o4") ||
                model.startsWith("gpt-5")
        val toolCapable =
            model.startsWith("gpt-") ||
                model.startsWith("o3") ||
                model.startsWith("o4")

        return ModelCapability(
            supportsStreaming = true,
            supportsToolCalling = toolCapable,
            supportsNativeWebSearch = false,
            supportedReasoningEfforts =
                if (reasoningModel) {
                    setOf(
                        LlmReasoningEffort.LOW,
                        LlmReasoningEffort.MEDIUM,
                        LlmReasoningEffort.HIGH,
                    )
                } else {
                    emptySet()
                },
            reasoningParameterStyle =
                if (reasoningModel) {
                    ReasoningParameterStyle.OPENAI_REASONING_EFFORT
                } else {
                    ReasoningParameterStyle.NONE
                },
            // 思考强度参数与“是否返回可展示 reasoning”是两回事。
            // 官方 OpenAI 接口不默认承诺暴露隐藏推理内容，因此这里保持关闭。
            supportsReasoningOutput = false,
        )
    }

    private fun deepSeekCapability(model: String): ModelCapability =
        when {
            model.contains("reasoner") ->
                ModelCapability(
                    supportsStreaming = true,
                    supportsToolCalling = false,
                    supportsReasoningOutput = true,
                )

            else ->
                ModelCapability(
                    supportsStreaming = true,
                    supportsToolCalling = true,
                )
        }
}
