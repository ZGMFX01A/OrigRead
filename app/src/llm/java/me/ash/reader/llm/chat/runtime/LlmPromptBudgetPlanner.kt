package me.ash.reader.llm.chat.runtime

import me.ash.reader.infrastructure.ai.AiErrorCode
import me.ash.reader.infrastructure.ai.AiException
import me.ash.reader.llm.runtime.LlmExecutionPlan
import me.ash.reader.llm.runtime.estimateLlmTokens

/** 请求发送前的统一总 Prompt 预算门。 */
internal class LlmPromptBudgetPlanner {
    /**
     * 覆盖 system（Skill/Custom/Context）、完整 history、Tool schema 与输出预留。
     *
     * Tool assistant/tool 拓扑不能按单条消息静默裁剪；当前无法安全保留完整链时明确拒绝。
     */
    fun validate(
        plan: LlmExecutionPlan,
        history: List<LlmChatRequestMessage>,
    ): LlmPromptBudget {
        val systemTokens =
            buildLlmChatSystemPrompt(plan)?.let { estimateLlmTokens(it) + MESSAGE_OVERHEAD_TOKENS } ?: 0
        val historyTokens =
            history.sumOf { message ->
                estimateLlmTokens(renderLlmChatMessageContent(plan, message)) +
                    MESSAGE_OVERHEAD_TOKENS +
                    message.toolCalls.sumOf { call ->
                        estimateLlmTokens(call.id) +
                            estimateLlmTokens(call.name) +
                            estimateLlmTokens(call.argumentsJson)
                    } +
                    (message.toolCallId?.let(::estimateLlmTokens) ?: 0)
            }
        val toolSchemaTokens =
            if (plan.automaticToolCalling) {
                plan.tools.sumOf { tool ->
                    estimateLlmTokens(tool.name) +
                        estimateLlmTokens(tool.description) +
                        estimateLlmTokens(tool.inputSchemaJson) +
                        TOOL_SCHEMA_OVERHEAD_TOKENS
                }
            } else {
                0
            }
        val outputReserveTokens =
            (plan.capability.contextWindowTokens / OUTPUT_RESERVE_DIVISOR)
                .coerceIn(MIN_OUTPUT_RESERVE_TOKENS, MAX_OUTPUT_RESERVE_TOKENS)
        val promptTokens = systemTokens + historyTokens + toolSchemaTokens
        val requiredTokens = promptTokens + outputReserveTokens
        if (requiredTokens > plan.capability.contextWindowTokens) {
            throw AiException(
                AiErrorCode.INVALID_REQUEST,
                "请求超过模型上下文窗口：Prompt 约 $promptTokens tokens，输出预留 $outputReserveTokens tokens，窗口 ${plan.capability.contextWindowTokens} tokens。完整 Tool 历史无法安全裁剪，请减少会话历史、Context、Skill/Custom Instructions 或 Tool。",
            )
        }
        return LlmPromptBudget(promptTokens, outputReserveTokens, requiredTokens)
    }

    private companion object {
        /** OpenAI-compatible 消息角色与 JSON 包装的保守开销。 */
        const val MESSAGE_OVERHEAD_TOKENS = 6
        /** Function schema 外层 type/function/parameters 包装的保守开销。 */
        const val TOOL_SCHEMA_OVERHEAD_TOKENS = 12
        const val OUTPUT_RESERVE_DIVISOR = 8
        const val MIN_OUTPUT_RESERVE_TOKENS = 1_024
        const val MAX_OUTPUT_RESERVE_TOKENS = 8_192
    }
}

/** 便于性能追踪和测试断言的请求预算快照。 */
internal data class LlmPromptBudget(
    val promptTokens: Int,
    val outputReserveTokens: Int,
    val requiredTokens: Int,
)
