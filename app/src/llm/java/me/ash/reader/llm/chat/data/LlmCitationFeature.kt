package me.ash.reader.llm.chat.data

/**
 * R07 Evidence Citation is implemented incrementally behind one production gate.
 * The legacy whole-ARTICLE [R#] protocol stays retired; only the new Evidence Block / [[E#]]
 * pipeline may be enabled, and the gate must remain false until R07.7 completes device acceptance.
 */
internal const val LLM_EVIDENCE_CITATION_ENABLED = false

/**
 * While the product gate is closed, hide both retired [R#] tokens and the new request-local
 * [[E#]] tokens. Ordinary R1/R2 text and normal bracketed numbers are left untouched.
 */
internal fun stripDisabledLlmCitationTokens(
    text: String,
    citationFeatureEnabled: Boolean = LLM_EVIDENCE_CITATION_ENABLED,
): String {
    if (citationFeatureEnabled || text.isBlank()) return text
    return text
        .replace(DISABLED_LEGACY_CITATION_TOKEN_REGEX, "")
        .replace(DISABLED_EVIDENCE_CITATION_TOKEN_REGEX, "")
}

private val DISABLED_LEGACY_CITATION_TOKEN_REGEX = Regex("[ \\t]*\\[R\\d+]", RegexOption.IGNORE_CASE)
private val DISABLED_EVIDENCE_CITATION_TOKEN_REGEX = Regex("[ \\t]*\\[\\[E\\d+]]")
