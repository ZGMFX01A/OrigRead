package me.ash.reader.ui.page.nav3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.ash.reader.llm.settings.LlmAdvancedSettingsSection
import me.ash.reader.llm.skill.LlmSkillSettingsPage
import me.ash.reader.ui.page.settings.ai.AiSettingsPage

/** LLM edition 在基础 AI 阅读设置上叠加 Runtime / Context / Reasoning 参数。 */
@Composable
internal fun EditionAiSettingsPage(onBack: () -> Unit) {
    var showSkills by remember { mutableStateOf(false) }
    if (showSkills) {
        LlmSkillSettingsPage(onBack = { showSkills = false })
        return
    }
    AiSettingsPage(
        onBack = onBack,
        additionalSettingsContent = {
            LlmAdvancedSettingsSection(onOpenSkills = { showSkills = true })
        },
    )
}
