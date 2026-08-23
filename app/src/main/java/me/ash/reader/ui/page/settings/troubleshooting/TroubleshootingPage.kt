package me.ash.reader.ui.page.settings.troubleshooting

import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.domain.data.Log
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.theme.palette.onLight

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TroubleshootingPage(onBack: () -> Unit, viewModel: TroubleshootingViewModel = hiltViewModel()) {
    val hapticFeedback = LocalHapticFeedback.current
    val syncLogList = remember { mutableStateListOf<Log>() }

    LaunchedEffect(viewModel) { viewModel.getSyncLogs().let { syncLogList.addAll(it) } }

    OrigReadScaffold(
        containerColor =
            MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack,
            )
        },
        content = {
            LazyColumn {
                item {
                    DisplayText(text = stringResource(R.string.troubleshooting), desc = "")
                    Spacer(modifier = Modifier.height(24.dp))
                }
                item {
                    Subtitle(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.sync_error_logs),
                    )
                }
                if (syncLogList.isEmpty()) {
                    item {
                        Text(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            text = stringResource(R.string.sync_error_logs_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(syncLogList) { SyncLogItem(log = it) }
                    item {
                        Button(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 24.dp),
                            onClick = {
                                viewModel.clearSyncLogs()
                                syncLogList.clear()
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(
                        modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                    )
                }
            }
        },
    )
}

@Composable
fun SyncLogItem(log: Log, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText(log.fileName, log.content))
                        )
                    }
                }
                .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(log.fileName, style = MaterialTheme.typography.titleMedium)
        Text(
            log.content,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
