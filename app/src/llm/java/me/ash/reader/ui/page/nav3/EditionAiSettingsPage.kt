package me.ash.reader.ui.page.nav3

import androidx.compose.runtime.Composable
import me.ash.reader.llm.settings.LlmAdvancedSettingsSection
import me.ash.reader.ui.page.settings.ai.AiSettingsPage

/** LLM edition 在基础 AI 阅读设置上叠加 Runtime / Context / Reasoning 参数。 */
@Composable
internal fun EditionAiSettingsPage(onBack: () -> Unit) {
    AiSettingsPage(
        onBack = onBack,
        additionalSettingsContent = { LlmAdvancedSettingsSection() },
    )
}
