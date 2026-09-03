package me.ash.reader.llm.runtime

import me.ash.reader.infrastructure.ai.AiRuntimeConfig
import me.ash.reader.infrastructure.ai.AiOutputTokenLimitStyle

enum class LlmReasoningEffort {
    AUTO,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    MAXIMUM,
}

/** 单次 LLM 请求的受控任务类型；CHAT 为普通文章追问，ARTICLE_ANALYSIS 为阅读页一键深度分析。 */
enum class LlmExecutionTask {
    CHAT,
    ARTICLE_ANALYSIS,
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
    /** Provider/Model 可用的总上下文窗口；用于请求发送前的统一 Prompt 门。 */
    val contextWindowTokens: Int = DEFAULT_LLM_CONTEXT_WINDOW_TOKENS,
    /** 默认采用兼容面最广的 max_tokens；新推理模型可由能力覆盖为 max_completion_tokens。 */
    val outputTokenLimitStyle: AiOutputTokenLimitStyle = AiOutputTokenLimitStyle.MAX_TOKENS,
    /** 默认要求明确终止标记；false 仅兼容正文已完成但网关直接正常 EOF 的服务。 */
    val strictStreamTermination: Boolean = true,
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
    val contextWindowTokens: Int? = null,
    val outputTokenLimitStyle: AiOutputTokenLimitStyle? = null,
    val strictStreamTermination: Boolean? = null,
) {
    fun applyTo(base: ModelCapability): ModelCapability =
        base.copy(
            supportsStreaming = supportsStreaming ?: base.supportsStreaming,
            supportsToolCalling = supportsToolCalling ?: base.supportsToolCalling,
            supportsNativeWebSearch = supportsNativeWebSearch ?: base.supportsNativeWebSearch,
            supportedReasoningEfforts = supportedReasoningEfforts ?: base.supportedReasoningEfforts,
            reasoningParameterStyle = reasoningParameterStyle ?: base.reasoningParameterStyle,
            supportsReasoningOutput = supportsReasoningOutput ?: base.supportsReasoningOutput,
            contextWindowTokens = contextWindowTokens ?: base.contextWindowTokens,
            outputTokenLimitStyle = outputTokenLimitStyle ?: base.outputTokenLimitStyle,
            strictStreamTermination = strictStreamTermination ?: base.strictStreamTermination,
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
    WEB_SEARCH_RESULT,
    TOOL_RESULT,
}

data class LlmContextItem(
    val id: String,
    val type: LlmContextType,
    val content: String,
    val title: String? = null,
    val sourceId: String? = null,
    /** OrigRead 内部文章 ID；只用于文章派生 Context 的应用内导航。 */
    val internalArticleId: String? = null,
    /** 关键原始证据可要求 Composer 为其预留最低预算，避免被摘要/译文等辅助 Context 完全挤出。 */
    val reserveEvidenceBudget: Boolean = false,
    val evidenceBlocks: List<LlmContextEvidenceBlock> = emptyList(),
    val priority: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "上下文 id 不能为空" }
    }
}

data class LlmContextEvidenceBlock(
    val stableLocatorKey: String,
    val content: String,
)

internal fun llmEvidenceRequestIdentity(
    contextId: String,
    stableLocatorKey: String,
): String =
    "${contextId.length}:$contextId${stableLocatorKey.length}:$stableLocatorKey"

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
    /** 每个已纳入 Context 的实际正文片段；不包含 OrigRead 安全边界 wrapper。 */
    val renderedItems: List<LlmRenderedContextItem> = emptyList(),
)

/** ContextComposer 在预算处理后真正送入模型的单项正文，用于来源快照与历史可复现。 */
data class LlmRenderedContextItem(
    val id: String,
    val content: String,
    val truncated: Boolean,
    val evidenceBlockKeys: List<String> = emptyList(),
)

/**
 * 单次请求的只读引用元数据。
 *
 * index/contextId 由 OrigRead 自己生成；toolCallId 仅用于把历史 Tool Result 与已冻结的 [R#] 对齐，
 * 不参与 Tool 权限、调用许可或执行策略判断。
 */
data class LlmCitationReference(
    val index: Int,
    val contextId: String,
    val type: LlmContextType,
    val toolCallId: String? = null,
)

data class LlmExecutionProfile(
    val task: LlmExecutionTask = LlmExecutionTask.CHAT,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.AUTO,
    val capabilityOverride: ModelCapabilityOverride? = null,
    val skillId: String? = null,
    val customInstructions: String? = null,
    val enabledToolIds: Set<String> = emptySet(),
    val contextPolicy: LlmContextPolicy = LlmContextPolicy(),
)

data class LlmExecutionPlan(
    val task: LlmExecutionTask = LlmExecutionTask.CHAT,
    val providerId: String,
    val providerName: String,
    val runtimeConfig: AiRuntimeConfig,
    val capability: ModelCapability,
    val reasoningParameter: ProviderReasoningParameter?,
    val tools: List<LlmToolDescriptor>,
    val automaticToolCalling: Boolean,
    val context: ComposedLlmContext,
    val skillId: String?,
    val citationProtocolInstruction: String? = null,
    /** 已启用 Skill 的受控指令正文；null 表示本次执行使用 OrigRead 默认工作流。 */
    val skillInstructions: String? = null,
    /** 用户长期回答偏好；固定低于任务/Skill、高于外部 Context Data。 */
    val customInstructions: String? = null,
    /** P6.6 请求级引用映射；由 Chat 在 ContextRef 冻结后注入，不由 Runtime/Provider 推断。 */
    val citations: List<LlmCitationReference> = emptyList(),
)

/** 保守默认值与现有 128k Context 设置一致，避免常规请求升级后大面积失败。 */
const val DEFAULT_LLM_CONTEXT_WINDOW_TOKENS = 128_000
