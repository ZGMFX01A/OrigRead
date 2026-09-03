package me.ash.reader.llm.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.ash.reader.BuildConfig
import me.ash.reader.llm.runtime.LlmReasoningEffort
import me.ash.reader.llm.search.WebSearchMode

/** LLM/Chat 执行偏好；Standard 与 OrigRead X 共用实现，仅首次默认值不同。 */
data class LlmAdvancedSettings(
    /** Chat/阅读助手总开关。数据类默认保持旧 X 行为，真正首次默认值由 Repository 按 Edition 决定。 */
    val assistantEnabled: Boolean = true,
    /** Reader 中央 AI 按钮的默认短按动作：true=摘要，false=直接进入 Chat。 */
    val defaultGenerateSummary: Boolean = true,
    /** 仅控制设置页是否展开 Provider 兼容能力等高级配置；关闭 Chat 时只隐藏，不清空该偏好。 */
    val advancedAiConfigEnabled: Boolean = false,
    val reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.AUTO,
    val streamResponses: Boolean = true,
    val showReasoning: Boolean = true,
    val contextMaxTokens: Int = LlmSettingsRepository.DEFAULT_CONTEXT_TOKENS,
    /** 用户长期回答偏好；只进入受控 Custom Instructions 插槽，不参与权限或 Tool 决策。 */
    val customInstructions: String = "",
    val skillsEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val webSearchMode: WebSearchMode = WebSearchMode.AUTO,
    val mcpEnabled: Boolean = false,
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

    fun setAssistantEnabled(value: Boolean) = update { it.copy(assistantEnabled = value) }

    fun setDefaultGenerateSummary(value: Boolean) =
        update { it.copy(defaultGenerateSummary = value) }

    fun setAdvancedAiConfigEnabled(value: Boolean) =
        update { it.copy(advancedAiConfigEnabled = value) }

    fun setReasoningEffort(value: LlmReasoningEffort) =
        update { it.copy(reasoningEffort = value) }

    fun setStreamResponses(value: Boolean) = update { it.copy(streamResponses = value) }

    fun setShowReasoning(value: Boolean) = update { it.copy(showReasoning = value) }

    fun setContextMaxTokens(value: Int) =
        update { it.copy(contextMaxTokens = normalizeContextTokens(value)) }

    fun setCustomInstructions(value: String) =
        update { it.copy(customInstructions = normalizeCustomInstructions(value)) }

    fun setSkillsEnabled(value: Boolean) = update { it.copy(skillsEnabled = value) }

    fun setWebSearchEnabled(value: Boolean) = update { it.copy(webSearchEnabled = value) }

    /** FORCE 是 Chat 的一次性动作，不允许写成全局持久状态。 */
    fun setWebSearchMode(value: WebSearchMode) =
        update { it.copy(webSearchMode = value.takeUnless { it == WebSearchMode.FORCE } ?: WebSearchMode.AUTO) }

    fun setMcpEnabled(value: Boolean) = update { it.copy(mcpEnabled = value) }


    /** 完整配置备份恢复入口；沿用与设置页相同的边界归一化，不保留旧实验字段。 */
    fun restoreBackup(settings: LlmAdvancedSettings) {
        val normalized =
            settings.copy(
                contextMaxTokens = normalizeContextTokens(settings.contextMaxTokens),
                customInstructions = normalizeCustomInstructions(settings.customInstructions),
                webSearchMode = settings.webSearchMode.takeUnless { it == WebSearchMode.FORCE } ?: WebSearchMode.AUTO,
            )
        preferences.edit().clear().apply()
        persist(normalized)
        _settings.value = normalized
    }

    private fun update(transform: (LlmAdvancedSettings) -> LlmAdvancedSettings) {
        val transformed = transform(_settings.value)
        val next =
            transformed.copy(
                contextMaxTokens = normalizeContextTokens(transformed.contextMaxTokens),
                customInstructions = normalizeCustomInstructions(transformed.customInstructions),
            )
        persist(next)
        _settings.value = next
    }

    private fun readSettings(): LlmAdvancedSettings =
        LlmAdvancedSettings(
            assistantEnabled =
                preferences.getBoolean(KEY_ASSISTANT_ENABLED, defaultAssistantEnabled()),
            defaultGenerateSummary =
                preferences.getBoolean(KEY_DEFAULT_GENERATE_SUMMARY, true),
            advancedAiConfigEnabled =
                preferences.getBoolean(KEY_ADVANCED_AI_CONFIG_ENABLED, false),
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
            customInstructions =
                normalizeCustomInstructions(
                    preferences.getString(KEY_CUSTOM_INSTRUCTIONS, null).orEmpty()
                ),
            skillsEnabled = preferences.getBoolean(KEY_SKILLS_ENABLED, false),
            webSearchEnabled = preferences.getBoolean(KEY_WEB_SEARCH_ENABLED, false),
            webSearchMode =
                runCatching {
                        WebSearchMode.valueOf(
                            preferences.getString(KEY_WEB_SEARCH_MODE, null).orEmpty()
                        )
                    }
                    .getOrDefault(WebSearchMode.AUTO)
                    .takeUnless { it == WebSearchMode.FORCE }
                    ?: WebSearchMode.AUTO,
            mcpEnabled = preferences.getBoolean(KEY_MCP_ENABLED, false),
        )

    private fun persist(settings: LlmAdvancedSettings) {
        preferences.edit()
            .putBoolean(KEY_ASSISTANT_ENABLED, settings.assistantEnabled)
            .putBoolean(KEY_DEFAULT_GENERATE_SUMMARY, settings.defaultGenerateSummary)
            .putBoolean(KEY_ADVANCED_AI_CONFIG_ENABLED, settings.advancedAiConfigEnabled)
            .putString(KEY_REASONING_EFFORT, settings.reasoningEffort.name)
            .putBoolean(KEY_STREAM_RESPONSES, settings.streamResponses)
            .putBoolean(KEY_SHOW_REASONING, settings.showReasoning)
            .putInt(KEY_CONTEXT_MAX_TOKENS, settings.contextMaxTokens)
            .putString(KEY_CUSTOM_INSTRUCTIONS, settings.customInstructions)
            .putBoolean(KEY_SKILLS_ENABLED, settings.skillsEnabled)
            .putBoolean(KEY_WEB_SEARCH_ENABLED, settings.webSearchEnabled)
            .putString(
                KEY_WEB_SEARCH_MODE,
                settings.webSearchMode.takeUnless { it == WebSearchMode.FORCE }?.name
                    ?: WebSearchMode.AUTO.name,
            )
            .putBoolean(KEY_MCP_ENABLED, settings.mcpEnabled)
            .remove(KEY_CONTEXT_MAX_CHARACTERS)
            .apply()
    }

    /** 已安装旧版本没有该 key：OrigRead 默认关闭，OrigRead X 默认开启。 */
    private fun defaultAssistantEnabled(): Boolean = defaultLlmAssistantEnabledForEdition(BuildConfig.EDITION)

    /** 只做防止异常值/Int 溢出的安全夹取，不再把用户输入吸附到固定档位。 */
    private fun normalizeContextTokens(value: Int): Int =
        value.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS)

    /** 去掉首尾空白并限制持久化体积；不解析用户文本，也不允许它决定 Prompt 位置。 */
    private fun normalizeCustomInstructions(value: String): String =
        value.trim().take(MAX_CUSTOM_INSTRUCTIONS_LENGTH)

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 128_000
        /** LLM Context Budget 与 Provider 最小窗口保持一致，支持常见 4K 模型。 */
        const val MIN_CONTEXT_TOKENS = 4_096
        const val MAX_CONTEXT_TOKENS = 4_000_000
        const val MAX_CUSTOM_INSTRUCTIONS_LENGTH = 8_000

        private const val PREFERENCES_NAME = "origread_llm_runtime_settings"
        private const val KEY_ASSISTANT_ENABLED = "assistant_enabled"
        private const val KEY_DEFAULT_GENERATE_SUMMARY = "default_generate_summary"
        private const val KEY_ADVANCED_AI_CONFIG_ENABLED = "advanced_ai_config_enabled"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_STREAM_RESPONSES = "stream_responses"
        private const val KEY_SHOW_REASONING = "show_reasoning"
        private const val KEY_CONTEXT_MAX_TOKENS = "context_max_tokens"
        private const val KEY_CUSTOM_INSTRUCTIONS = "custom_instructions"
        private const val KEY_SKILLS_ENABLED = "skills_enabled"
        private const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"
        private const val KEY_WEB_SEARCH_MODE = "web_search_mode"
        private const val KEY_MCP_ENABLED = "mcp_enabled"
        // 2026-08-24 以前的实验版曾把 UTF-16 字符预算误显示成 Context；新版本不继承该语义。
        private const val KEY_CONTEXT_MAX_CHARACTERS = "context_max_characters"
    }
}

/** Edition 只决定首次默认值，不决定功能是否存在。 */
internal fun defaultLlmAssistantEnabledForEdition(edition: String): Boolean = edition == "llm"
