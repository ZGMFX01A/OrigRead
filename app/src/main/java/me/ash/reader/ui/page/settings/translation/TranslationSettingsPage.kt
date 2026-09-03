package me.ash.reader.ui.page.settings.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import me.ash.reader.infrastructure.translation.TranslationProviderSettings
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.infrastructure.translation.displayName
import me.ash.reader.infrastructure.language.displayLanguageValue
import me.ash.reader.ui.component.base.Banner
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch
import me.ash.reader.ui.ext.collectAsStateValue

@Composable
fun TranslationSettingsPage(
    onBack: () -> Unit,
    viewModel: TranslationSettingsViewModel = hiltViewModel(),
) {
    val state = viewModel.uiState.collectAsStateValue()
    val successPrefix = stringResource(R.string.translation_test_success)
    val failurePrefix = stringResource(R.string.translation_test_failed)
    var targetLanguageFocused by remember { mutableStateOf(false) }
    val displayLocale = LocalConfiguration.current.locales[0]

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
                        text = stringResource(R.string.translation_settings),
                        desc = stringResource(R.string.translation_settings_desc),
                    )
                }
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value =
                                if (targetLanguageFocused) {
                                    state.settings.targetLanguage
                                } else {
                                    displayLanguageValue(state.settings.targetLanguage, displayLocale)
                                },
                            onValueChange = viewModel::setTargetLanguage,
                            modifier =
                                Modifier.fillMaxWidth().onFocusChanged {
                                    targetLanguageFocused = it.isFocused
                                },
                            singleLine = true,
                            label = { Text(stringResource(R.string.translation_target_language)) },
                            supportingText = {
                                Text(stringResource(R.string.translation_target_language_desc))
                            },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.translation_display_mode),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected =
                                    state.settings.displayMode ==
                                        TranslationDisplayMode.TRANSLATED,
                                onClick = {
                                    viewModel.setDisplayMode(TranslationDisplayMode.TRANSLATED)
                                },
                                label = { Text(stringResource(R.string.translation_only)) },
                            )
                            FilterChip(
                                selected =
                                    state.settings.displayMode ==
                                        TranslationDisplayMode.BILINGUAL,
                                onClick = {
                                    viewModel.setDisplayMode(TranslationDisplayMode.BILINGUAL)
                                },
                                label = { Text(stringResource(R.string.translation_bilingual)) },
                            )
                        }
                    }
                }
                item {
                    Banner(
                        title = stringResource(R.string.translation_privacy_title),
                        desc = stringResource(R.string.translation_privacy_desc),
                    )
                }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.translation_provider_selection_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.translation_provider_selection_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TranslationProviderType.entries.forEach { type ->
                    item(key = type.name) {
                        ProviderSettingsCard(
                            type = type,
                            settings = state.settings.provider(type),
                            selected =
                                state.settings.defaultTarget == TranslationTarget.Traditional(type),
                            apiKeyDraft = state.apiKeyDrafts[type].orEmpty(),
                            hasApiKey = state.hasApiKeys[type] == true,
                            testing = state.testingProvider == type,
                            testResult = state.testResults[type],
                            deepLUsage = state.deepLUsage,
                            deepLUsageError = state.deepLUsageError,
                            loadingDeepLUsage = state.loadingDeepLUsage,
                            onSelected = { viewModel.setDefaultProvider(type) },
                            onEnabled = { viewModel.setEnabled(type, it) },
                            onEndpoint = { viewModel.setEndpoint(type, it) },
                            onRegion = viewModel::setMicrosoftRegion,
                            onApiKey = { viewModel.updateApiKeyDraft(type, it) },
                            onSaveApiKey = { viewModel.saveApiKey(type) },
                            onTest = {
                                viewModel.testProvider(type, successPrefix, failurePrefix)
                            },
                            onRefreshDeepLUsage = viewModel::refreshDeepLUsage,
                        )
                    }
                }
                item {
                    TextButton(
                        onClick = viewModel::restoreDefaults,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(stringResource(R.string.restore_defaults))
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        },
    )
}

@Composable
private fun DeepLUsageSection(
    usage: me.ash.reader.infrastructure.translation.DeepLUsage?,
    error: String?,
    loading: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
) {
    val formatter = NumberFormat.getIntegerInstance()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.translation_deepl_usage_title),
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton(onClick = onRefresh, enabled = enabled && !loading) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.translation_deepl_usage_refresh))
                }
            }
        }
        usage?.let {
            Text(
                text =
                    stringResource(
                        R.string.translation_deepl_usage_value,
                        formatter.format(it.characterCount),
                        formatter.format(it.characterLimit),
                        formatter.format(it.remainingCharacters),
                        String.format("%.1f%%", it.usagePercent),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.translation_deepl_usage_failed, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ProviderSettingsCard(
    type: TranslationProviderType,
    settings: TranslationProviderSettings,
    selected: Boolean,
    apiKeyDraft: String,
    hasApiKey: Boolean,
    testing: Boolean,
    testResult: TranslationProviderTestResult?,
    deepLUsage: me.ash.reader.infrastructure.translation.DeepLUsage?,
    deepLUsageError: String?,
    loadingDeepLUsage: Boolean,
    onSelected: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onEndpoint: (String) -> Unit,
    onRegion: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onSaveApiKey: () -> Unit,
    onTest: () -> Unit,
    onRefreshDeepLUsage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = onSelected, enabled = settings.enabled)
            Column(modifier = Modifier.weight(1f)) {
                Text(type.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = providerDescription(type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OrigReadSwitch(activated = settings.enabled) { onEnabled(!settings.enabled) }
        }

        if (type != TranslationProviderType.ML_KIT) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.endpoint,
                onValueChange = onEndpoint,
                modifier = Modifier.fillMaxWidth(),
                enabled = settings.enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.translation_endpoint)) },
            )
        }
        if (type == TranslationProviderType.MICROSOFT) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.region,
                onValueChange = onRegion,
                modifier = Modifier.fillMaxWidth(),
                enabled = settings.enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.translation_region)) },
                supportingText = { Text(stringResource(R.string.translation_region_desc)) },
            )
        }
        if (type != TranslationProviderType.ML_KIT) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKeyDraft,
                onValueChange = onApiKey,
                modifier = Modifier.fillMaxWidth(),
                enabled = settings.enabled,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = {
                    Text(
                        if (type == TranslationProviderType.DLX) {
                            stringResource(R.string.translation_optional_token)
                        } else {
                            stringResource(R.string.translation_api_key)
                        }
                    )
                },
                supportingText = {
                    when {
                        type == TranslationProviderType.DLX ->
                            Text(stringResource(R.string.translation_optional_token_desc))
                        hasApiKey -> Text(stringResource(R.string.translation_key_saved))
                    }
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onSaveApiKey,
                enabled =
                    settings.enabled &&
                        (apiKeyDraft.isNotBlank() ||
                            (type == TranslationProviderType.DLX && hasApiKey)),
            ) {
                Text(
                    stringResource(
                        if (type == TranslationProviderType.DLX &&
                            hasApiKey &&
                            apiKeyDraft.isBlank()
                        ) {
                            R.string.translation_remove_key
                        } else {
                            R.string.translation_save_key
                        }
                    )
                )
            }
        }
        if (type == TranslationProviderType.DEEPL) {
            Spacer(modifier = Modifier.height(10.dp))
            DeepLUsageSection(
                usage = deepLUsage,
                error = deepLUsageError,
                loading = loadingDeepLUsage,
                enabled = settings.enabled && hasApiKey && settings.endpoint.isNotBlank(),
                onRefresh = onRefreshDeepLUsage,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onTest, enabled = settings.enabled && !testing) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.translation_test_provider))
                }
            }
            testResult?.let { ProviderTestResult(it) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
    }
}

@Composable
private fun ProviderTestResult(result: TranslationProviderTestResult) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (result.success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            contentDescription = null,
            tint = if (result.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        )
        Text(
            text = result.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (result.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun providerDescription(type: TranslationProviderType): String =
    when (type) {
        TranslationProviderType.ML_KIT ->
            stringResource(R.string.translation_provider_mlkit_desc)
        TranslationProviderType.MICROSOFT ->
            stringResource(R.string.translation_provider_microsoft_desc)
        TranslationProviderType.DEEPL ->
            stringResource(R.string.translation_provider_deepl_desc)
        TranslationProviderType.GOOGLE_CLOUD ->
            stringResource(R.string.translation_provider_google_cloud_desc)
        TranslationProviderType.DLX ->
            stringResource(R.string.translation_provider_dlx_desc)
    }
