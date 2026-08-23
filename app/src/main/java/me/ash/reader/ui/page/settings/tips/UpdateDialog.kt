package me.ash.reader.ui.page.settings.tips

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.infrastructure.net.Download
import me.ash.reader.ui.component.base.OrigReadDialog
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.installLatestApk
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.page.home.reading.AiMarkdown

@Composable
fun UpdateDialog(
    updateViewModel: UpdateViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val updateUiState = updateViewModel.updateUiState.collectAsStateValue()
    val downloadState = updateUiState.downloadFlow.collectAsState(initial = Download.NotYet).value
    val scope = rememberCoroutineScope { Dispatchers.IO }
    val newVersionNumber = LocalNewVersionNumber.current
    val newVersionPublishDate = LocalNewVersionPublishDate.current
    val newVersionLog = LocalNewVersionLog.current
    val newVersionSize = LocalNewVersionSize.current
    val newVersionDownloadUrl = LocalNewVersionDownloadUrl.current
    val currentAppLanguage = LocalConfiguration.current.locales[0].language

    val installSourceSettings =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // REQUEST_INSTALL_PACKAGES 是特殊授权，不走 runtime permission 回调；
            // 用户从“安装未知应用”设置页回来后重新检查系统授权。
            if (context.packageManager.canRequestPackageInstalls()) {
                context.installLatestApk()
            }
        }

    fun requestInstall() {
        if (context.packageManager.canRequestPackageInstalls()) {
            context.installLatestApk()
        } else {
            installSourceSettings.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    // 下载完成属于状态变化副作用，不能放在 Text 组合阶段执行，否则重组可能重复拉起系统安装器。
    LaunchedEffect(downloadState) {
        if (downloadState is Download.Finished) {
            Log.i("RLog", "Download.Finished: ${downloadState.file.absolutePath}")
            requestInstall()
        }
    }

    OrigReadDialog(
        modifier = Modifier.heightIn(max = 560.dp),
        visible = updateUiState.updateDialogVisible,
        onDismissRequest = {
            // 下载中保持弹窗，避免用户误以为任务已经停止；AppUpdater 自身负责实际下载生命周期。
            if (downloadState !is Download.Progress) updateViewModel.hideDialog()
        },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Update,
                contentDescription = stringResource(R.string.change_log),
            )
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(R.string.change_log))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "${newVersionPublishDate.asReleaseDate()} $newVersionSize".trim(),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    AiMarkdown(
                        markdown =
                            newVersionLog
                                .localizedReleaseNotes(currentAppLanguage)
                                .withoutGeneratedFullChangelog(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (downloadState is Download.Error) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = downloadState.message.ifBlank { stringResource(R.string.download_failure) },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (downloadState) {
                        is Download.Progress -> Unit
                        is Download.Finished -> requestInstall()
                        else -> {
                            if (newVersionDownloadUrl.isNotBlank()) {
                                updateViewModel.downloadUpdate(
                                    url = newVersionDownloadUrl,
                                    version = newVersionNumber.toString(),
                                )
                            } else {
                                // Release 元数据异常或没有 APK 资产时保留浏览器兜底，不让更新入口变成死按钮。
                                context.openURL(
                                    "${context.getString(R.string.github_link)}/releases/latest",
                                    OpenLinkPreference.AutoPreferCustomTabs,
                                )
                            }
                        }
                    }
                },
                enabled = downloadState !is Download.Progress,
            ) {
                Text(
                    text =
                        when (downloadState) {
                            is Download.Progress -> "${stringResource(R.string.update)} ${downloadState.percent}%"
                            is Download.Finished -> stringResource(R.string.install_update)
                            is Download.Error -> stringResource(R.string.retry_update)
                            Download.Cancelled -> stringResource(R.string.retry_update)
                            Download.NotYet -> stringResource(R.string.update)
                        },
                )
            }
        },
        dismissButton = {
            if (downloadState !is Download.Progress) {
                TextButton(
                    onClick = {
                        SkipVersionNumberPreference.put(context, scope, newVersionNumber.toString())
                        updateViewModel.hideDialog()
                    }
                ) {
                    Text(text = stringResource(R.string.skip_this_version))
                }
            }
        },
    )
}

/** GitHub 自动生成 Release Notes 时附带的 compare 链接不属于用户需要阅读的更新内容。 */
internal fun String.withoutGeneratedFullChangelog(): String {
    val markers = listOf("**Full Changelog**:", "**Full Changelog**", "Full Changelog:")
    val markerIndex = markers.map { indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull()
        ?: return trim()
    return substring(0, markerIndex).trimEnd()
}

/**
 * 一个 GitHub Release body 可以同时维护中文和英文段落。
 * 推荐使用 GitHub 页面不可见的 `<!-- lang:zh -->` / `<!-- lang:en -->` 分隔；
 * 旧的可见语言标题和旧版单语日志继续兼容。
 */
internal fun String.localizedReleaseNotes(language: String): String {
    val body = trim()
    if (body.isBlank()) return body
    val hiddenMarker =
        Regex(
            pattern = "(?im)^\\s*<!--\\s*(?:origread:)?lang\\s*[:=]\\s*([a-zA-Z-]+)\\s*-->\\s*$"
        )
    val hiddenMatches = hiddenMarker.findAll(body).toList()
    if (hiddenMatches.isNotEmpty()) {
        val wantChinese = language.trim().startsWith("zh", ignoreCase = true)
        val selected =
            hiddenMatches.firstOrNull { match ->
                match.groupValues[1].startsWith("zh", ignoreCase = true) == wantChinese
            } ?: return body
        val next = hiddenMatches.firstOrNull { it.range.first > selected.range.last }
        return body.substring(selected.range.last + 1, next?.range?.first ?: body.length).trim()
    }
    val heading =
        Regex(
            pattern = "(?im)^#{1,6}\\s*(中文|简体中文|繁體中文|繁体中文|Chinese|English|英文)\\s*$"
        )
    val matches = heading.findAll(body).toList()
    if (matches.isEmpty()) return body
    val wantChinese = language.trim().startsWith("zh", ignoreCase = true)
    val selected =
        matches.firstOrNull { match ->
            val label = match.groupValues[1].lowercase()
            val chineseSection = label != "english" && label != "英文"
            chineseSection == wantChinese
        } ?: return body
    val next = matches.firstOrNull { it.range.first > selected.range.last }
    return body.substring(selected.range.last + 1, next?.range?.first ?: body.length).trim()
}

/** GitHub Release 时间统一只展示 yyyy-MM-dd，避免把 ISO 时间戳直接暴露给用户。 */
internal fun String.asReleaseDate(): String {
    val value = trim()
    return Regex("^\\d{4}-\\d{2}-\\d{2}").find(value)?.value ?: value
}
