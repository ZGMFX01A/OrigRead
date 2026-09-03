package me.ash.reader.llm.chat.data

/**
 * R07 Evidence Citation production gate.
 * The legacy whole-ARTICLE [R#] protocol is permanently retired; this gate controls only the
 * Evidence Block / [[E#]] pipeline shared by Standard and OrigRead X.
 */
internal const val LLM_EVIDENCE_CITATION_ENABLED = true

/**
 * When Citation is explicitly disabled (tests/compatibility fallback), hide both retired [R#]
 * tokens and request-local [[E#]] tokens. Ordinary R1/R2 text and normal bracketed numbers are
 * left untouched.
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
