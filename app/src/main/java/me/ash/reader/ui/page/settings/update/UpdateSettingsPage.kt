package me.ash.reader.ui.page.settings.update

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalAutoCheckUpdates
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.getCurrentVersion
import me.ash.reader.ui.ext.put
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.page.settings.tips.UpdateDialog
import me.ash.reader.ui.page.settings.tips.UpdateViewModel

/** GitHub 渠道的软件更新设置；下载和安装仍始终由用户主动触发。 */
@Composable
fun UpdateSettingsPage(
    onBack: () -> Unit,
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoCheckUpdates = LocalAutoCheckUpdates.current
    val currentVersion = context.getCurrentVersion().toString()

    RYScaffold(
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
            )
        },
        content = {
            LazyColumn {
                item {
                    DisplayText(
                        text = stringResource(R.string.software_update),
                        desc = stringResource(R.string.software_update_desc),
                    )
                }
                item {
                    SettingItem(
                        title = stringResource(R.string.auto_check_updates),
                        desc = stringResource(R.string.auto_check_updates_desc),
                        onClick = { autoCheckUpdates.toggle(context, scope) },
                    ) {
                        RYSwitch(activated = autoCheckUpdates.value) {
                            autoCheckUpdates.toggle(context, scope)
                        }
                    }
                }
                item {
                    SettingItem(
                        title = stringResource(R.string.check_updates_now),
                        desc = stringResource(R.string.current_version_value, currentVersion),
                        icon = Icons.Outlined.SystemUpdate,
                        onClick = {
                            updateViewModel.checkUpdate(
                                preProcessor = {
                                    context.showToast(context.getString(R.string.checking_updates))
                                    // 手动检查应忽略“跳过此版本”，否则用户主动检查也看不到该版本。
                                    context.dataStore.put(DataStoreKey.skipVersionNumber, "")
                                },
                                postProcessor = { hasUpdate ->
                                    if (!hasUpdate) {
                                        context.showToast(context.getString(R.string.is_latest_version))
                                    }
                                },
                            )
                        },
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    )

    UpdateDialog(updateViewModel = updateViewModel)
}
