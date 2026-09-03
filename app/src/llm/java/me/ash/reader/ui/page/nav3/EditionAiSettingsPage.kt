package me.ash.reader.ui.page.nav3

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.llm.mcp.McpSettingsPage
import me.ash.reader.llm.quickmessage.LlmQuickMessageSettingsPage
import me.ash.reader.llm.search.WebSearchSettingsPage
import me.ash.reader.llm.settings.LlmAdvancedSettingsSection
import me.ash.reader.llm.settings.LlmAdvancedSettingsViewModel
import me.ash.reader.llm.settings.LlmAssistantFeatureSettingsSection
import me.ash.reader.llm.settings.LlmCustomInstructionsSettingsPage
import me.ash.reader.llm.skill.LlmSkillSettingsPage
import me.ash.reader.ui.motion.OrigReadMotionDirection
import me.ash.reader.ui.motion.origReadNavigationTransform
import me.ash.reader.ui.page.settings.ai.AiSettingsPage

private enum class AiSettingsSubPage {
    CUSTOM_INSTRUCTIONS,
    SKILLS,
    QUICK_MESSAGES,
    WEB_SEARCH,
    MCP,
}

/** LLM edition 在基础 AI 阅读设置上叠加 Runtime / Context / Reasoning 参数。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EditionAiSettingsPage(onBack: () -> Unit) {
    var currentSubPage by rememberSaveable { mutableStateOf<AiSettingsSubPage?>(null) }
    val advancedViewModel: LlmAdvancedSettingsViewModel = hiltViewModel()
    val advancedSettings by advancedViewModel.settings.collectAsState()
    val motionScheme = MaterialTheme.motionScheme
    val forwardTransform = origReadNavigationTransform(OrigReadMotionDirection.Forward, motionScheme)
    val backwardTransform = origReadNavigationTransform(OrigReadMotionDirection.Backward, motionScheme)

    /** 顶部返回与系统 Back/返回手势共用同一关闭动作；无子页时才交还外层 Settings。 */
    val closeCurrentSubPage = { currentSubPage = null }
    BackHandler(enabled = currentSubPage != null) { closeCurrentSubPage() }

    AnimatedContent(
        targetState = currentSubPage,
        transitionSpec = {
            if (initialState == null && targetState != null) forwardTransform
            else backwardTransform
        },
        label = "ai-settings-sub-page",
    ) { subPage ->
        when (subPage) {
            AiSettingsSubPage.CUSTOM_INSTRUCTIONS ->
                LlmCustomInstructionsSettingsPage(onBack = closeCurrentSubPage)
            AiSettingsSubPage.SKILLS -> LlmSkillSettingsPage(onBack = closeCurrentSubPage)
            AiSettingsSubPage.QUICK_MESSAGES ->
                LlmQuickMessageSettingsPage(onBack = closeCurrentSubPage)
            AiSettingsSubPage.WEB_SEARCH -> WebSearchSettingsPage(onBack = closeCurrentSubPage)
            AiSettingsSubPage.MCP -> McpSettingsPage(onBack = closeCurrentSubPage)
            null ->
                AiSettingsPage(
                    onBack = onBack,
                    showProviderCapabilityOverrides =
                        advancedSettings.assistantEnabled && advancedSettings.advancedAiConfigEnabled,
                    providerConfigurationTitle = stringResource(R.string.llm_ai_provider_configuration),
                    additionalSettingsContent = {
                        LlmAdvancedSettingsSection(
                            onOpenCustomInstructions = {
                                currentSubPage = AiSettingsSubPage.CUSTOM_INSTRUCTIONS
                            },
                            onOpenSkills = { currentSubPage = AiSettingsSubPage.SKILLS },
                            onOpenQuickMessages = {
                                currentSubPage = AiSettingsSubPage.QUICK_MESSAGES
                            },
                            onOpenWebSearch = { currentSubPage = AiSettingsSubPage.WEB_SEARCH },
                            onOpenMcp = { currentSubPage = AiSettingsSubPage.MCP },
                            viewModel = advancedViewModel,
                        )
                    },
                    footerSettingsContent = {
                        LlmAssistantFeatureSettingsSection(viewModel = advancedViewModel)
                    },
                )
        }
    }
}
