package me.ash.reader.llm.runtime

import me.ash.reader.infrastructure.ai.AiRuntimeConfig

enum class LlmReasoningEffort {
    AUTO,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    MAXIMUM,
}

enum class ReasoningParameterStyle {
    NONE,
    OPENAI_REASONING_EFFORT,
}

data class ModelCapability(
    val supportsStreaming: Boolean = true,
    val supportsToolCalling: Boolean = false,
    val supportsNativeWebSearch: Boolean = false,
    val supportedReasoningEfforts: Set<LlmReasoningEffort> = emptySet(),
    val reasoningParameterStyle: ReasoningParameterStyle = ReasoningParameterStyle.NONE,
    val supportsReasoningOutput: Boolean = false,
)

/**
 * 未知 OpenAI-Compatible 服务允许显式覆盖能力；null 表示继续采用内置判断。
 * 参数风格必须单独声明，避免“支持思考强度”被错误理解为所有服务都接受 reasoning_effort。
 */
data class ModelCapabilityOverride(
    val supportsStreaming: Boolean? = null,
    val supportsToolCalling: Boolean? = null,
    val supportsNativeWebSearch: Boolean? = null,
    val supportedReasoningEfforts: Set<LlmReasoningEffort>? = null,
    val reasoningParameterStyle: ReasoningParameterStyle? = null,
    val supportsReasoningOutput: Boolean? = null,
) {
    fun applyTo(base: ModelCapability): ModelCapability =
        base.copy(
            supportsStreaming = supportsStreaming ?: base.supportsStreaming,
            supportsToolCalling = supportsToolCalling ?: base.supportsToolCalling,
            supportsNativeWebSearch = supportsNativeWebSearch ?: base.supportsNativeWebSearch,
            supportedReasoningEfforts = supportedReasoningEfforts ?: base.supportedReasoningEfforts,
            reasoningParameterStyle = reasoningParameterStyle ?: base.reasoningParameterStyle,
            supportsReasoningOutput = supportsReasoningOutput ?: base.supportsReasoningOutput,
        )
}

data class ProviderReasoningParameter(
    val key: String,
    val value: String,
)

enum class LlmContextType {
    ARTICLE,
    ARTICLE_SUMMARY,
    ARTICLE_TRANSLATION,
    SELECTED_TEXT,
    MANUAL,
    TOOL_RESULT,
}

data class LlmContextItem(
    val id: String,
    val type: LlmContextType,
    val content: String,
    val title: String? = null,
    val sourceId: String? = null,
    val priority: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "上下文 id 不能为空" }
    }
}

data class LlmContextPolicy(
    /**
     * OrigRead 注入给模型的阅读材料预算，单位为近似 token。
     *
     * OpenAI-Compatible 服务并不共享同一个 tokenizer，因此这里不能伪装成供应商精确 token 数；
     * ContextComposer 使用偏保守的跨语言估算器控制正文截断，避免继续把 UTF-16 字符数误叫成 context window。
     */
    val maxTokens: Int = 128_000,
    val allowedTypes: Set<LlmContextType> = LlmContextType.entries.toSet(),
)

data class ComposedLlmContext(
    val text: String,
    val includedIds: List<String>,
    val omittedIds: List<String>,
    val truncated: Boolean,
)

data class LlmExecutionProfile(
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.AUTO,
    val capabilityOverride: ModelCapabilityOverride? = null,
    val skillId: String? = null,
    val enabledToolIds: Set<String> = emptySet(),
    val contextPolicy: LlmContextPolicy = LlmContextPolicy(),
)

data class LlmExecutionPlan(
    val providerId: String,
    val providerName: String,
    val runtimeConfig: AiRuntimeConfig,
    val capability: ModelCapability,
    val reasoningParameter: ProviderReasoningParameter?,
    val tools: List<LlmToolDescriptor>,
    val automaticToolCalling: Boolean,
    val context: ComposedLlmContext,
    val skillId: String?,
)
