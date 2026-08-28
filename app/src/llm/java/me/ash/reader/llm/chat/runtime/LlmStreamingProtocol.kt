package me.ash.reader.llm.chat.runtime

/**
 * Chat 生成链路统一的终止语义。
 *
 * Provider 的原始字段只能在对应协议 Adapter/Transport 中转换成这里的类型；Chat 编排与 UI 不读取
 * `finish_reason`、`finishReason` 等供应商字段，也不根据模型名或服务域名推断终止状态。
 */
sealed interface LlmFinishReason {
    /** 模型自然结束，或命中了请求中提供的 stop sequence。 */
    data object Stop : LlmFinishReason

    /** Provider 明确表示输出达到长度/token 上限，当前结果可能不完整。 */
    data object Length : LlmFinishReason

    /** Provider 明确表示本轮生成进入 Tool Calling 阶段。 */
    data object ToolCalls : LlmFinishReason

    /** Provider 明确表示生成因内容过滤而结束。 */
    data object ContentFilter : LlmFinishReason

    /** Provider/协议明确返回终止错误；网络与解析异常仍通过 Flow error 传播。 */
    data object Error : LlmFinishReason

    /** Provider 明确返回取消语义；用户主动停止仍由协程取消链路处理。 */
    data object Cancelled : LlmFinishReason

    /**
     * 尚未认识的 Provider 终止原因。
     *
     * 保留短原值仅用于诊断和未来扩展；上层状态机只按 Other 处理，禁止据此做模型/host 特判。
     */
    data class Other(val rawReason: String) : LlmFinishReason
}

/** 统一终止原因的稳定诊断值；不会泄漏成 Chat 业务判断所依赖的 Provider 原始字段。 */
internal fun LlmFinishReason.diagnosticValue(): String =
    when (this) {
        LlmFinishReason.Stop -> "STOP"
        LlmFinishReason.Length -> "LENGTH"
        LlmFinishReason.ToolCalls -> "TOOL_CALLS"
        LlmFinishReason.ContentFilter -> "CONTENT_FILTER"
        LlmFinishReason.Error -> "ERROR"
        LlmFinishReason.Cancelled -> "CANCELLED"
        is LlmFinishReason.Other -> "OTHER:$rawReason"
    }

/** Chat 生成协调层对一轮 Provider 流结束后的通用决策。 */
internal sealed interface LlmGenerationTerminalDecision {
    /** 有可提交正文，且 Provider 没有报告会使结果不完整/不可用的终止状态。 */
    data object Complete : LlmGenerationTerminalDecision

    /** 收到了结构化 Tool Call，应完成 Tool 执行并继续下一轮生成。 */
    data object ContinueWithTools : LlmGenerationTerminalDecision

    /** 当前轮不能被当作正常 Assistant 终态；已产生的部分正文/思考仍可保留用于诊断。 */
    data class Error(val userMessage: String) : LlmGenerationTerminalDecision
}

/**
 * 根据统一终止语义和实际收到的结构化输出决定本轮生成终态。
 *
 * 这里故意不接收 provider/model/host。第三方兼容网关可能漏报或误报终止原因，因此在正常 STOP/OTHER
 * 情况下仍以实际 Tool Call 为准；但 LENGTH/CONTENT_FILTER 等明确的不完整/受限终态不会执行 Tool。
 */
internal fun resolveLlmGenerationTerminalDecision(
    hasContent: Boolean,
    hasReasoning: Boolean,
    hasToolCalls: Boolean,
    finishReason: LlmFinishReason?,
): LlmGenerationTerminalDecision {
    when (finishReason) {
        LlmFinishReason.Length ->
            return LlmGenerationTerminalDecision.Error(
                "AI 输出达到长度上限，当前结果可能不完整",
            )

        LlmFinishReason.ContentFilter ->
            return LlmGenerationTerminalDecision.Error(
                "AI 服务因内容过滤结束生成",
            )

        LlmFinishReason.Error ->
            return LlmGenerationTerminalDecision.Error("AI 服务结束生成时返回错误")

        LlmFinishReason.Cancelled ->
            return LlmGenerationTerminalDecision.Error("AI 服务取消了本次生成")

        else -> Unit
    }

    if (hasToolCalls) return LlmGenerationTerminalDecision.ContinueWithTools

    if (finishReason == LlmFinishReason.ToolCalls) {
        return LlmGenerationTerminalDecision.Error(
            "AI 服务声明需要调用工具，但没有返回有效 Tool Call",
        )
    }

    if (hasContent) return LlmGenerationTerminalDecision.Complete

    if (hasReasoning) {
        return LlmGenerationTerminalDecision.Error(
            "AI 服务结束生成，但只返回了思考内容，未返回正文",
        )
    }

    return LlmGenerationTerminalDecision.Error("AI 服务没有返回可显示内容")
}
