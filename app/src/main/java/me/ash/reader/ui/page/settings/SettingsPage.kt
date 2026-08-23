package me.ash.reader.ui.page.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
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
            LazyColumn {
                item {
                    DisplayText(text = stringResource(R.string.settings), desc = "")
                }
                item {
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
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.ai_settings),
                        desc = stringResource(R.string.ai_settings_desc),
                        icon = Icons.Outlined.AutoAwesome,
                        onClick = navigateToAiSettings,
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.translation_settings),
                        desc = stringResource(R.string.translation_settings_desc),
                        icon = Icons.Outlined.Translate,
                        onClick = navigateToTranslationSettings,
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.article_filter_settings),
                        desc = stringResource(R.string.article_filter_settings_desc),
                        icon = Icons.Outlined.FilterAlt,
                        onClick = navigateToArticleFilters,
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.json_rules),
                        desc = stringResource(R.string.json_rules_desc),
                        icon = Icons.Outlined.DataObject,
                        onClick = navigateToJsonRules,
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.website_rules),
                        desc = stringResource(R.string.website_rules_desc),
                        icon = Icons.Outlined.Rule,
                        onClick = navigateToWebsiteRules,
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.rsshub_settings),
                        desc = stringResource(R.string.rsshub_settings_desc),
                        icon = Icons.Outlined.RssFeed,
                        onClick = navigateToRssHubSettings,
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.accounts),
                        desc = stringResource(R.string.accounts_desc),
                        icon = Icons.Outlined.AccountCircle,
                        onClick = navigateToAccounts
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.configuration_backup_title),
                        desc = stringResource(R.string.configuration_backup_desc),
                        icon = Icons.Outlined.SettingsBackupRestore,
                        onClick = navigateToConfigurationBackup,
                    )
                }
                if (isGitHub) {
                    item {
                        SelectableSettingGroupItem(
                            title = stringResource(R.string.software_update),
                            desc = stringResource(R.string.software_update_desc),
                            icon = Icons.Outlined.SystemUpdate,
                            onClick = navigateToUpdateSettings,
                        )
                    }
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.color_and_style),
                        desc = stringResource(R.string.color_and_style_desc),
                        icon = Icons.Outlined.Palette,
                        onClick = navigateToColorAndStyle
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.interaction),
                        desc = stringResource(R.string.interaction_desc),
                        icon = Icons.Outlined.TouchApp,
                        onClick = navigateToInteraction
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.languages),
                        desc = Locale.getDefault().toDisplayName(),
                        icon = Icons.Outlined.Language,
                        onClick = navigateToLanguages
                    )
                }
                item {
                    SelectableSettingGroupItem(
                        title = stringResource(R.string.tips_and_support),
                        desc = stringResource(R.string.tips_and_support_desc),
                        icon = Icons.Outlined.TipsAndUpdates,
                        onClick = navigateToTipsAndSupport
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    )

    UpdateDialog()
}
