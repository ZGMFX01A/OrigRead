package me.ash.reader.llm.chat.data

/**
 * Evidence Citation 已从当前 OrigRead X 修复阶段移出，作为独立跨端大功能后续开发。
 *
 * 当前整篇 ARTICLE 级 [R#] 只能够说明“来自哪篇文章”，不能回到文章中的真实证据位置，
 * 因此生产版本必须保持关闭。现有实现代码继续保留，供未来 Evidence Block / Anchor 完整方案复用。
 */
internal const val LLM_EVIDENCE_CITATION_ENABLED = false

/**
 * 当前产品关闭 Citation 时，历史消息中已经持久化的 [R#] 也不再显示给用户。
 * 只清理 OrigRead 自己曾使用的严格引用 token，不改写普通的 R1/R2 文本。
 */
internal fun stripDisabledLlmCitationTokens(
    text: String,
    citationFeatureEnabled: Boolean = LLM_EVIDENCE_CITATION_ENABLED,
): String {
    if (citationFeatureEnabled || text.isBlank()) return text
    return text.replace(DISABLED_LLM_CITATION_TOKEN_REGEX, "")
}

private val DISABLED_LLM_CITATION_TOKEN_REGEX = Regex("[ \\t]*\\[R\\d+]", RegexOption.IGNORE_CASE)
