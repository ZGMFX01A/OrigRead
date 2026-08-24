package me.ash.reader.llm.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.ash.reader.llm.runtime.LlmReasoningEffort

/** LLM edition 独有的执行偏好；与 Standard 的基础 AI 阅读设置分开持久化。 */
data class LlmAdvancedSettings(
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.AUTO,
    val streamResponses: Boolean = true,
    val showReasoning: Boolean = true,
    val contextMaxTokens: Int = LlmSettingsRepository.DEFAULT_CONTEXT_TOKENS,
)

@Singleton
class LlmSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<LlmAdvancedSettings> = _settings.asStateFlow()

    fun current(): LlmAdvancedSettings = _settings.value

    fun setReasoningEffort(value: LlmReasoningEffort) =
        update { it.copy(reasoningEffort = value) }

    fun setStreamResponses(value: Boolean) = update { it.copy(streamResponses = value) }

    fun setShowReasoning(value: Boolean) = update { it.copy(showReasoning = value) }

    fun setContextMaxTokens(value: Int) =
        update { it.copy(contextMaxTokens = normalizeContextTokens(value)) }

    private fun update(transform: (LlmAdvancedSettings) -> LlmAdvancedSettings) {
        val transformed = transform(_settings.value)
        val next =
            transformed.copy(
                contextMaxTokens = normalizeContextTokens(transformed.contextMaxTokens)
            )
        persist(next)
        _settings.value = next
    }

    private fun readSettings(): LlmAdvancedSettings =
        LlmAdvancedSettings(
            reasoningEffort =
                runCatching {
                    LlmReasoningEffort.valueOf(
                        preferences.getString(KEY_REASONING_EFFORT, null).orEmpty()
                    )
                }.getOrDefault(LlmReasoningEffort.AUTO),
            streamResponses = preferences.getBoolean(KEY_STREAM_RESPONSES, true),
            showReasoning = preferences.getBoolean(KEY_SHOW_REASONING, true),
            contextMaxTokens =
                normalizeContextTokens(
                    preferences.getInt(KEY_CONTEXT_MAX_TOKENS, DEFAULT_CONTEXT_TOKENS)
                ),
        )

    private fun persist(settings: LlmAdvancedSettings) {
        preferences.edit()
            .putString(KEY_REASONING_EFFORT, settings.reasoningEffort.name)
            .putBoolean(KEY_STREAM_RESPONSES, settings.streamResponses)
            .putBoolean(KEY_SHOW_REASONING, settings.showReasoning)
            .putInt(KEY_CONTEXT_MAX_TOKENS, settings.contextMaxTokens)
            .remove(KEY_CONTEXT_MAX_CHARACTERS)
            .apply()
    }

    /** 只做防止异常值/Int 溢出的安全夹取，不再把用户输入吸附到固定档位。 */
    private fun normalizeContextTokens(value: Int): Int =
        value.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 128_000
        const val MIN_CONTEXT_TOKENS = 8_000
        const val MAX_CONTEXT_TOKENS = 4_000_000

        private const val PREFERENCES_NAME = "origread_llm_runtime_settings"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_STREAM_RESPONSES = "stream_responses"
        private const val KEY_SHOW_REASONING = "show_reasoning"
        private const val KEY_CONTEXT_MAX_TOKENS = "context_max_tokens"
        // 2026-08-24 以前的实验版曾把 UTF-16 字符预算误显示成 Context；新版本不继承该语义。
        private const val KEY_CONTEXT_MAX_CHARACTERS = "context_max_characters"
    }
}
