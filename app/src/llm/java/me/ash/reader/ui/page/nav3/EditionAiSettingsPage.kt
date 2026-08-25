package me.ash.reader.ui.page.nav3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.ash.reader.llm.mcp.McpSettingsPage
import me.ash.reader.llm.quickmessage.LlmQuickMessageSettingsPage
import me.ash.reader.llm.search.WebSearchSettingsPage
import me.ash.reader.llm.settings.LlmAdvancedSettingsSection
import me.ash.reader.llm.settings.LlmCustomInstructionsSettingsPage
import me.ash.reader.llm.skill.LlmSkillSettingsPage
import me.ash.reader.ui.page.settings.ai.AiSettingsPage

/** LLM edition 在基础 AI 阅读设置上叠加 Runtime / Context / Reasoning 参数。 */
@Composable
internal fun EditionAiSettingsPage(onBack: () -> Unit) {
    var showCustomInstructions by remember { mutableStateOf(false) }
    var showSkills by remember { mutableStateOf(false) }
    var showQuickMessages by remember { mutableStateOf(false) }
    var showWebSearch by remember { mutableStateOf(false) }
    var showMcp by remember { mutableStateOf(false) }
    if (showCustomInstructions) {
        LlmCustomInstructionsSettingsPage(onBack = { showCustomInstructions = false })
        return
    }
    if (showSkills) {
        LlmSkillSettingsPage(onBack = { showSkills = false })
        return
    }
    if (showQuickMessages) {
        LlmQuickMessageSettingsPage(onBack = { showQuickMessages = false })
        return
    }
    if (showWebSearch) {
        WebSearchSettingsPage(onBack = { showWebSearch = false })
        return
    }
    if (showMcp) {
        McpSettingsPage(onBack = { showMcp = false })
        return
    }
    AiSettingsPage(
        onBack = onBack,
        additionalSettingsContent = {
            LlmAdvancedSettingsSection(
                onOpenCustomInstructions = { showCustomInstructions = true },
                onOpenSkills = { showSkills = true },
                onOpenQuickMessages = { showQuickMessages = true },
                onOpenWebSearch = { showWebSearch = true },
                onOpenMcp = { showMcp = true },
            )
        },
    )
}
