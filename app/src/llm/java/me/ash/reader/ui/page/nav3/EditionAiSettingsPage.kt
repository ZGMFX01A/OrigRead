package me.ash.reader.ui.page.nav3

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import me.ash.reader.R
import me.ash.reader.llm.mcp.McpSettingsPage
import me.ash.reader.llm.quickmessage.LlmQuickMessageSettingsPage
import me.ash.reader.llm.search.WebSearchSettingsPage
import me.ash.reader.llm.settings.LlmAdvancedSettingsSection
import me.ash.reader.llm.settings.LlmCustomInstructionsSettingsPage
import me.ash.reader.llm.skill.LlmSkillSettingsPage
import me.ash.reader.ui.page.settings.ai.AiSettingsPage

private enum class AiSettingsSubPage {
    CUSTOM_INSTRUCTIONS,
    SKILLS,
    QUICK_MESSAGES,
    WEB_SEARCH,
    MCP,
}

/** LLM edition 在基础 AI 阅读设置上叠加 Runtime / Context / Reasoning 参数。 */
@Composable
internal fun EditionAiSettingsPage(onBack: () -> Unit) {
    var currentSubPage by rememberSaveable { mutableStateOf<AiSettingsSubPage?>(null) }

    /** 顶部返回与系统 Back/返回手势共用同一关闭动作；无子页时才交还外层 Settings。 */
    val closeCurrentSubPage = { currentSubPage = null }
    BackHandler(enabled = currentSubPage != null) { closeCurrentSubPage() }

    when (currentSubPage) {
        AiSettingsSubPage.CUSTOM_INSTRUCTIONS -> {
            LlmCustomInstructionsSettingsPage(onBack = closeCurrentSubPage)
            return
        }
        AiSettingsSubPage.SKILLS -> {
            LlmSkillSettingsPage(onBack = closeCurrentSubPage)
            return
        }
        AiSettingsSubPage.QUICK_MESSAGES -> {
            LlmQuickMessageSettingsPage(onBack = closeCurrentSubPage)
            return
        }
        AiSettingsSubPage.WEB_SEARCH -> {
            WebSearchSettingsPage(onBack = closeCurrentSubPage)
            return
        }
        AiSettingsSubPage.MCP -> {
            McpSettingsPage(onBack = closeCurrentSubPage)
            return
        }
        null -> Unit
    }
    AiSettingsPage(
        onBack = onBack,
        showProviderCapabilityOverrides = true,
        providerConfigurationTitle = stringResource(R.string.llm_ai_provider_configuration),
        additionalSettingsContent = {
            LlmAdvancedSettingsSection(
                onOpenCustomInstructions = { currentSubPage = AiSettingsSubPage.CUSTOM_INSTRUCTIONS },
                onOpenSkills = { currentSubPage = AiSettingsSubPage.SKILLS },
                onOpenQuickMessages = { currentSubPage = AiSettingsSubPage.QUICK_MESSAGES },
                onOpenWebSearch = { currentSubPage = AiSettingsSubPage.WEB_SEARCH },
                onOpenMcp = { currentSubPage = AiSettingsSubPage.MCP },
            )
        },
    )
}
