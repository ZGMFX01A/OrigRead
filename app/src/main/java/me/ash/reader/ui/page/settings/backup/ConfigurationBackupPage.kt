package me.ash.reader.ui.page.settings.backup

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.ash.reader.R
import me.ash.reader.ui.component.base.Banner
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.ext.MimeType
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.page.settings.SettingItem

@Composable
fun ConfigurationBackupPage(
    onBack: () -> Unit,
    viewModel: ConfigurationBackupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsStateValue()
    var includeSecrets by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MimeType.JSON)) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            viewModel.exportBackup(includeSecrets, exportPassword) { result ->
                result.fold(
                    onSuccess = { bytes ->
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                                ?: error(context.getString(R.string.configuration_backup_write_failed))
                        }.onSuccess {
                            Toast.makeText(
                                context,
                                context.getString(R.string.configuration_backup_export_success),
                                Toast.LENGTH_LONG,
                            ).show()
                        }.onFailure { error ->
                            Toast.makeText(context, error.message.orEmpty(), Toast.LENGTH_LONG).show()
                        }
                    },
                    onFailure = { error ->
                        Toast.makeText(context, error.message.orEmpty(), Toast.LENGTH_LONG).show()
                    },
                )
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val bytes =
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error(context.getString(R.string.configuration_backup_read_failed))
                }.getOrElse { error ->
                    Toast.makeText(context, error.message.orEmpty(), Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
            restorePassword = ""
            viewModel.inspectBackup(bytes) { result ->
                result.exceptionOrNull()?.let { error ->
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.configuration_backup_invalid,
                            error.message.orEmpty(),
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

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
                        text = stringResource(R.string.configuration_backup_title),
                        desc = stringResource(R.string.configuration_backup_desc),
                    )
                }
                item {
                    Banner(
                        title = stringResource(R.string.configuration_backup_scope_title),
                        desc = stringResource(R.string.configuration_backup_scope_desc),
                    )
                }
                item {
                    SettingItem(
                        title = stringResource(R.string.configuration_backup_include_secrets),
                        desc = stringResource(R.string.configuration_backup_include_secrets_desc),
                        icon = Icons.Outlined.Key,
                        onClick = { includeSecrets = !includeSecrets },
                    ) {
                        RYSwitch(activated = includeSecrets) { includeSecrets = !includeSecrets }
                    }
                }
                if (includeSecrets) {
                    item {
                        OutlinedTextField(
                            value = exportPassword,
                            onValueChange = { exportPassword = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text(stringResource(R.string.configuration_backup_password)) },
                            supportingText = {
                                Text(stringResource(R.string.configuration_backup_password_desc))
                            },
                        )
                    }
                }
                item {
                    SettingItem(
                        enabled = !state.isWorking,
                        title = stringResource(R.string.configuration_backup_export),
                        desc = stringResource(R.string.configuration_backup_export_desc),
                        icon = Icons.Outlined.Download,
                        onClick = {
                            if (includeSecrets && exportPassword.length < 6) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.configuration_backup_password_too_short),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                exportLauncher.launch(configurationBackupFileName())
                            }
                        },
                    ) {
                        if (state.isWorking) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingItem(
                        enabled = !state.isWorking,
                        title = stringResource(R.string.configuration_backup_restore),
                        desc = stringResource(R.string.configuration_backup_restore_desc),
                        icon = Icons.Outlined.Restore,
                        onClick = { importLauncher.launch(arrayOf(MimeType.JSON, MimeType.ANY)) },
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    )

    state.pendingSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = {
                if (!state.isWorking) {
                    restorePassword = ""
                    viewModel.dismissRestore()
                }
            },
            title = { Text(stringResource(R.string.configuration_backup_restore_confirm_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.configuration_backup_restore_summary,
                            summary.sourceVersion,
                            summary.groupCount,
                            summary.subscriptionCount,
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.configuration_backup_merge_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (summary.containsEncryptedSecrets) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = restorePassword,
                            onValueChange = { restorePassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text(stringResource(R.string.configuration_backup_password)) },
                            supportingText = {
                                Text(stringResource(R.string.configuration_backup_restore_password_desc))
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled =
                        !state.isWorking &&
                            (!summary.containsEncryptedSecrets || restorePassword.isNotEmpty()),
                    onClick = {
                        viewModel.restoreBackup(restorePassword) { result ->
                            result.fold(
                                onSuccess = { restored ->
                                    restorePassword = ""
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.configuration_backup_restore_success,
                                            restored.restoredSubscriptions,
                                        ),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                                onFailure = { error ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.configuration_backup_restore_failed,
                                            error.message.orEmpty(),
                                        ),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            )
                        }
                    },
                ) {
                    if (state.isWorking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text(stringResource(R.string.confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.isWorking,
                    onClick = {
                        restorePassword = ""
                        viewModel.dismissRestore()
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun configurationBackupFileName(): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
    return "OrigRead-config-$timestamp.json"
}
