package me.ash.reader.ui.page.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalNewVersionNumber
import me.ash.reader.infrastructure.preference.LocalSkipVersionNumber
import me.ash.reader.infrastructure.preference.toDisplayName
import me.ash.reader.ui.component.base.Banner
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.ext.getCurrentVersion
import me.ash.reader.ui.ext.isGitHub
import me.ash.reader.ui.page.adaptive.OrigReadAdaptiveContent
import me.ash.reader.ui.page.adaptive.OrigReadContentWidth
import me.ash.reader.ui.page.adaptive.LocalOrigReadAdaptiveLayoutProfile
import me.ash.reader.ui.page.settings.tips.UpdateDialog
import me.ash.reader.ui.page.settings.tips.UpdateViewModel
import me.ash.reader.ui.theme.palette.onLight
import java.util.Locale

@Composable
fun SettingsPage(
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onBack: () -> Unit,
    navigateToAccounts: () -> Unit,
    navigateToColorAndStyle: () -> Unit,
    navigateToInteraction: () -> Unit,
    navigateToLanguages: () -> Unit,
    navigateToWebsiteRules: () -> Unit,
    navigateToJsonRules: () -> Unit,
    navigateToRssHubSettings: () -> Unit,
    navigateToArticleFilters: () -> Unit,
    navigateToTranslationSettings: () -> Unit,
    navigateToAiSettings: () -> Unit,
    navigateToConfigurationBackup: () -> Unit,
    navigateToUpdateSettings: () -> Unit,
    navigateToTipsAndSupport: () -> Unit,
) {
    val context = LocalContext.current
    val newVersion = LocalNewVersionNumber.current
    val skipVersion = LocalSkipVersionNumber.current
    val currentVersion by remember { mutableStateOf(context.getCurrentVersion()) }
    val adaptiveProfile = LocalOrigReadAdaptiveLayoutProfile.current
    val settingsColumns = adaptiveProfile.settingsRootColumnCount
    val destinations =
        buildList {
            add(
                SettingsDestination(
                    title = stringResource(R.string.ai_settings),
                    desc = stringResource(R.string.ai_settings_desc),
                    icon = Icons.Outlined.AutoAwesome,
                    onClick = navigateToAiSettings,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.translation_settings),
                    desc = stringResource(R.string.translation_settings_desc),
                    icon = Icons.Outlined.Translate,
                    onClick = navigateToTranslationSettings,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.article_filter_settings),
                    desc = stringResource(R.string.article_filter_settings_desc),
                    icon = Icons.Outlined.FilterAlt,
                    onClick = navigateToArticleFilters,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.json_rules),
                    desc = stringResource(R.string.json_rules_desc),
                    icon = Icons.Outlined.DataObject,
                    onClick = navigateToJsonRules,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.website_rules),
                    desc = stringResource(R.string.website_rules_desc),
                    icon = Icons.Outlined.Rule,
                    onClick = navigateToWebsiteRules,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.rsshub_settings),
                    desc = stringResource(R.string.rsshub_settings_desc),
                    icon = Icons.Outlined.RssFeed,
                    onClick = navigateToRssHubSettings,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.accounts),
                    desc = stringResource(R.string.accounts_desc),
                    icon = Icons.Outlined.AccountCircle,
                    onClick = navigateToAccounts,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.configuration_backup_title),
                    desc = stringResource(R.string.configuration_backup_desc),
                    icon = Icons.Outlined.SettingsBackupRestore,
                    onClick = navigateToConfigurationBackup,
                )
            )
            if (isGitHub) {
                add(
                    SettingsDestination(
                        title = stringResource(R.string.software_update),
                        desc = stringResource(R.string.software_update_desc),
                        icon = Icons.Outlined.SystemUpdate,
                        onClick = navigateToUpdateSettings,
                    )
                )
            }
            add(
                SettingsDestination(
                    title = stringResource(R.string.color_and_style),
                    desc = stringResource(R.string.color_and_style_desc),
                    icon = Icons.Outlined.Palette,
                    onClick = navigateToColorAndStyle,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.interaction),
                    desc = stringResource(R.string.interaction_desc),
                    icon = Icons.Outlined.TouchApp,
                    onClick = navigateToInteraction,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.languages),
                    desc = Locale.getDefault().toDisplayName(),
                    icon = Icons.Outlined.Language,
                    onClick = navigateToLanguages,
                )
            )
            add(
                SettingsDestination(
                    title = stringResource(R.string.tips_and_support),
                    desc = stringResource(R.string.tips_and_support_desc),
                    icon = Icons.Outlined.TipsAndUpdates,
                    onClick = navigateToTipsAndSupport,
                )
            )
        }

    OrigReadScaffold(
        containerColor = MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack
            )
        },
        content = {
            OrigReadAdaptiveContent(
                width = if (settingsColumns > 1) OrigReadContentWidth.Editor else OrigReadContentWidth.Compact
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(settingsColumns),
                    modifier = Modifier.fillMaxSize(),
                ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DisplayText(text = stringResource(R.string.settings), desc = "")
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box {
                        if (newVersion.whetherNeedUpdate(currentVersion, skipVersion)) {
                            Banner(
                                modifier = Modifier.zIndex(1f),
                                title = stringResource(R.string.get_new_updates),
                                desc = stringResource(
                                    R.string.get_new_updates_desc,
                                    newVersion.toString(),
                                ),
                                icon = Icons.Outlined.Lightbulb,
                                action = {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.close),
                                    )
                                },
                            ) {
                                updateViewModel.showDialog()
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        // Banner(
                        //     title = stringResource(R.string.in_coding),
                        //     desc = stringResource(R.string.coming_soon),
                        //     icon = Icons.Outlined.Lightbulb,
                        // )
                    }
                }
                items(destinations) { destination ->
                    SelectableSettingGroupItem(
                        title = destination.title,
                        desc = destination.desc,
                        icon = destination.icon,
                        onClick = destination.onClick,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
                }
            }
        }
    )

    UpdateDialog()
}

private data class SettingsDestination(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)
