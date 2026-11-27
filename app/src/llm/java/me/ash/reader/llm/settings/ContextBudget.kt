package me.ash.reader.llm.settings

/** K consistently means 1000 tokens, including the exact 4096-token lower bound (4.096K). */
internal fun parseContextBudgetK(raw: String): Int? =
    raw.toBigDecimalOrNull()?.multiply(java.math.BigDecimal(1000))?.let { tokens ->
        try { tokens.intValueExact() } catch (_: ArithmeticException) { null }
    }?.takeIf { it in LlmSettingsRepository.MIN_CONTEXT_TOKENS..LlmSettingsRepository.MAX_CONTEXT_TOKENS }

internal fun formatContextBudgetK(tokens: Int): String =
    java.math.BigDecimal(tokens).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString()
