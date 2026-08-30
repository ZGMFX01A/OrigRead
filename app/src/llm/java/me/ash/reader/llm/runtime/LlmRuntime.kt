package me.ash.reader.llm.runtime

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.ai.AiPerfTrace
import me.ash.reader.infrastructure.ai.AiPerfTracer
import me.ash.reader.llm.skill.LlmSkillRepository

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
    private val skillRepository: LlmSkillRepository,
) {

    fun prepare(
        profile: LlmExecutionProfile,
        contextItems: List<LlmContextItem> = emptyList(),
        perfTrace: AiPerfTrace? = null,
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
        val contextComposeStartedAt = System.nanoTime()
        val context = contextComposer.compose(contextItems, profile.contextPolicy)
        perfTrace?.let { trace ->
            AiPerfTracer.mark(
                trace,
                "context_compose_complete",
                "durationMs" to ((System.nanoTime() - contextComposeStartedAt) / 1_000_000L).coerceAtLeast(0L),
                "candidateCount" to contextItems.size,
                "includedCount" to context.includedIds.size,
                "omittedCount" to context.omittedIds.size,
                "truncated" to context.truncated,
            )
        }
        val skill = skillRepository.activeSkill(profile.skillId)
        val skillInstructions = skill?.instructionBundle()?.takeIf(String::isNotBlank)
        skillInstructions?.let { instructions ->
            val budget =
                (profile.contextPolicy.maxTokens / SKILL_CONTEXT_BUDGET_DIVISOR)
                    .coerceIn(MIN_SKILL_PROMPT_TOKENS, MAX_SKILL_PROMPT_TOKENS)
            val estimatedTokens = estimateLlmTokens(instructions)
            if (estimatedTokens > budget) {
                throw me.ash.reader.infrastructure.ai.AiException(
                    me.ash.reader.infrastructure.ai.AiErrorCode.INVALID_REQUEST,
                    "Skill ${skill.id} 内容过大：约 $estimatedTokens tokens，当前请求最多允许 $budget tokens",
                )
            }
        }

        return LlmExecutionPlan(
            task = profile.task,
            providerId = provider.id,
            providerName = provider.name,
            runtimeConfig = runtimeConfig,
            capability = capability,
            reasoningParameter = reasoning,
            tools = tools,
            // 不支持 Tool Calling 的模型仍保留手动 Tool 能力，但绝不伪造自动 Function Calling。
            automaticToolCalling = capability.supportsToolCalling && tools.isNotEmpty(),
            context = context,
            skillId = skill?.id,
            skillInstructions = skillInstructions,
            customInstructions = profile.customInstructions?.trim()?.takeIf(String::isNotBlank),
        )
    }

    private companion object {
        const val SKILL_CONTEXT_BUDGET_DIVISOR = 4
        const val MIN_SKILL_PROMPT_TOKENS = 1_024
        const val MAX_SKILL_PROMPT_TOKENS = 16_000
    }
}
