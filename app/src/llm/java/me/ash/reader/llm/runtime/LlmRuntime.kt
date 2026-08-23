package me.ash.reader.llm.runtime

import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM edition 的统一执行入口。
 *
 * P2 只负责把 Provider/Model/Reasoning/Tool/Context/Skill 解析成稳定执行计划；
 * 真正的多轮会话、流式 HTTP 与消息持久化在 P3 接入，避免反向改造现有摘要主链。
 */
@Singleton
class LlmRuntime @Inject constructor(
    private val providerAdapter: OpenAiCompatibleLlmAdapter,
    private val contextComposer: LlmContextComposer,
    private val toolRuntime: LlmToolRuntime,
) {

    fun prepare(
        profile: LlmExecutionProfile,
        contextItems: List<LlmContextItem> = emptyList(),
    ): LlmExecutionPlan {
        val provider = providerAdapter.resolveProvider(profile.providerId)
        val model = providerAdapter.resolveModel(provider, profile.model)
        val runtimeConfig = providerAdapter.runtimeConfig(provider, model)
        val capability =
            providerAdapter.capability(
                provider = provider,
                model = model,
                override = profile.capabilityOverride,
            )
        val reasoning =
            providerAdapter.reasoningParameter(
                capability = capability,
                requested = profile.reasoningEffort,
            )
        val tools = toolRuntime.resolveAllowed(profile.enabledToolIds)
        val context = contextComposer.compose(contextItems, profile.contextPolicy)

        return LlmExecutionPlan(
            providerId = provider.id,
            providerName = provider.name,
            runtimeConfig = runtimeConfig,
            capability = capability,
            reasoningParameter = reasoning,
            tools = tools,
            // 不支持 Tool Calling 的模型仍保留手动 Tool 能力，但绝不伪造自动 Function Calling。
            automaticToolCalling = capability.supportsToolCalling && tools.isNotEmpty(),
            context = context,
            skillId = profile.skillId,
        )
    }
}
