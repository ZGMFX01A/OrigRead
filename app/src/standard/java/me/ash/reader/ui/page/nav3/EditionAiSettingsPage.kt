package me.ash.reader.ui.page.nav3

import androidx.compose.runtime.Composable
import me.ash.reader.ui.page.settings.ai.AiSettingsPage

/** Standard edition 保持 LLM 开发前的基础 AI 阅读设置。 */
@Composable
internal fun EditionAiSettingsPage(onBack: () -> Unit) {
    AiSettingsPage(onBack = onBack)
}
