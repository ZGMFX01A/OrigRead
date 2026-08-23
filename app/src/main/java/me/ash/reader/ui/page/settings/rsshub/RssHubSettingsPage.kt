package me.ash.reader.ui.page.settings.rsshub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.rsshub.RssHubInstance
import me.ash.reader.infrastructure.rsshub.RssHubLocation
import me.ash.reader.infrastructure.rsshub.RssHubSettingsRepository
import me.ash.reader.ui.component.base.Banner
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch
import me.ash.reader.ui.ext.collectAsStateValue

@Composable
fun RssHubSettingsPage(
    onBack: () -> Unit,
    viewModel: RssHubSettingsViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateValue()
    val successMessage = stringResource(R.string.rsshub_test_success)
    val failurePrefix = stringResource(R.string.rsshub_test_failed)

    OrigReadScaffold(
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
                        text = stringResource(R.string.rsshub_settings),
                        desc = stringResource(R.string.rsshub_settings_desc),
                    )
                }
                item {
                    Banner(
                        title = stringResource(R.string.rsshub_enable),
                        desc = stringResource(R.string.rsshub_enable_desc),
                        action = {
                            OrigReadSwitch(activated = state.enabled) {
                                viewModel.setEnabled(!state.enabled)
                            }
                        },
                    ) { viewModel.setEnabled(!state.enabled) }
                }
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.rsshub_instance_list),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.rsshub_instance_list_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.instances, key = { it.id }) { instance ->
                    RssHubInstanceItem(
                        instance = instance,
                        globalEnabled = state.enabled,
                        testing = state.testingUrl == instance.url,
                        testResult = state.testResults[instance.url],
                        onEnabledChange = { enabled ->
                            viewModel.setInstanceEnabled(instance.id, enabled)
                        },
                        onTest = {
                            viewModel.testConnection(
                                instanceUrl = instance.url,
                                addOnSuccess = false,
                                successMessage = successMessage,
                                failurePrefix = failurePrefix,
                            )
                        },
                        onDelete = { viewModel.deleteInstance(instance.id) },
                    )
                }
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        OutlinedTextField(
                            value = state.instanceUrl,
                            onValueChange = viewModel::updateInstanceUrl,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.enabled,
                            singleLine = true,
                            label = { Text(stringResource(R.string.rsshub_add_instance)) },
                            supportingText = { Text(stringResource(R.string.rsshub_instance_desc)) },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = {
                                    viewModel.testConnection(
                                        instanceUrl = state.instanceUrl,
                                        addOnSuccess = true,
                                        successMessage = successMessage,
                                        failurePrefix = failurePrefix,
                                    )
                                },
                                enabled =
                                    state.enabled &&
                                        state.instanceUrl.isNotBlank() &&
                                        state.testingUrl == null,
                            ) {
                                if (state.testingUrl != null) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(stringResource(R.string.rsshub_save_and_test))
                                }
                            }
                            val normalizedCustomUrl =
                                state.instanceUrl
                                    .takeIf { it.isNotBlank() }
                                    ?.let(RssHubSettingsRepository::normalizeInstanceUrl)
                            normalizedCustomUrl?.let { url ->
                                state.testResults[url]?.let { result ->
                                    RssHubTestResult(result)
                                }
                            }
                        }
                        TextButton(onClick = viewModel::restoreDefault) {
                            Text(stringResource(R.string.restore_defaults))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun RssHubTestResult(result: RssHubInstanceTestResult) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (result.success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            contentDescription = null,
            tint =
                if (result.success) {
                    Color(0xFF2E7D32)
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
        Text(
            text = result.message,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (result.success) {
                    Color(0xFF2E7D32)
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
    }
}

@Composable
private fun RssHubInstanceItem(
    instance: RssHubInstance,
    globalEnabled: Boolean,
    testing: Boolean,
    testResult: RssHubInstanceTestResult?,
    onEnabledChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val language = LocalConfiguration.current.locales[0].language
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.82f)) {
                    Text(text = instance.url, style = MaterialTheme.typography.bodyLarge)
                    val metadata =
                        listOf(
                            RssHubLocation.display(instance.location, language),
                            instance.maintainer,
                        )
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    if (metadata.isNotBlank()) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OrigReadSwitch(
                    activated = instance.enabled,
                    enable = globalEnabled,
                ) { onEnabledChange(!instance.enabled) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onTest,
                        enabled = globalEnabled && !testing,
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.rsshub_test_instance))
                        }
                    }
                    testResult?.let { RssHubTestResult(it) }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.rsshub_delete_instance),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}
