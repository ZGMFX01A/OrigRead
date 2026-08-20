package me.ash.reader.ui.page.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.ai.AiProviderProfile
import me.ash.reader.infrastructure.ai.AiRuleGenerationProgress
import me.ash.reader.infrastructure.ai.AiRuleGenerationStage
import me.ash.reader.infrastructure.ai.availableModels

@Composable
internal fun AiRuleGenerationDialog(
    visible: Boolean,
    title: String,
    url: String,
    providers: List<AiProviderProfile>,
    defaultProviderId: String,
    selectedProviderId: String,
    model: String,
    progress: AiRuleGenerationProgress?,
    isGenerating: Boolean,
    onUrlChange: (String) -> Unit,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (!visible) return
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val models = selectedProvider?.availableModels().orEmpty()
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var modelSearch by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf(false) }

    LaunchedEffect(selectedProviderId) {
        providerMenuExpanded = false
        modelMenuExpanded = false
        modelSearch = ""
        customModel = false
    }

    val urlIsValid = url.isBlank() || url.isHttpUrl()
    val canGenerate = !isGenerating && url.isHttpUrl() && selectedProvider != null && model.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!isGenerating) onDismissRequest() },
        modifier = Modifier.heightIn(max = 700.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(10.dp),
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.widthIn(min = 12.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = stringResource(R.string.ai_rule_dialog_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AiRuleStepIndicator(activeStep = if (progress == null) 1 else 3)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 510.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AiRuleSectionTitle(
                    step = "1",
                    title = stringResource(R.string.ai_rule_step_target),
                    icon = androidx.compose.material.icons.Icons.Rounded.Link,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating,
                    singleLine = true,
                    isError = !urlIsValid,
                    label = { Text(stringResource(R.string.ai_rule_target_url)) },
                    placeholder = { Text("https://example.com/news/") },
                    supportingText = {
                        Text(
                            text = when {
                                !urlIsValid -> stringResource(R.string.ai_rule_invalid_url)
                                else -> stringResource(R.string.ai_rule_target_url_hint)
                            },
                            color = if (!urlIsValid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                AiRuleSectionTitle(
                    step = "2",
                    title = stringResource(R.string.ai_rule_step_service),
                    icon = androidx.compose.material.icons.Icons.Rounded.AutoAwesome,
                )
                if (providers.isEmpty()) {
                    AiRuleEmptyServiceCard()
                } else {
                    Box {
                        OutlinedButton(
                            onClick = { providerMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isGenerating,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(
                                    selectedProvider?.name ?: stringResource(R.string.ai_rule_no_provider),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = providerMenuExpanded,
                            onDismissRequest = { providerMenuExpanded = false },
                            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp),
                        ) {
                            providers.forEach { provider ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(provider.name, fontWeight = FontWeight.SemiBold)
                                                if (provider.id == defaultProviderId) {
                                                    Text(
                                                        text = "  ${stringResource(R.string.ai_rule_default_badge)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                            Text(
                                                text = stringResource(R.string.ai_rule_model_choices, provider.availableModels().size),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        if (provider.id == selectedProviderId) {
                                            Icon(Icons.Rounded.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        providerMenuExpanded = false
                                        onProviderChange(provider.id)
                                    },
                                )
                            }
                        }
                    }
                }

                if (selectedProvider != null) {
                    Box {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { if (customModel) onModelChange(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isGenerating && !customModel) {
                                    modelSearch = ""
                                    modelMenuExpanded = true
                                },
                            enabled = !isGenerating,
                            readOnly = !customModel,
                            singleLine = true,
                            label = { Text(stringResource(R.string.ai_rule_model)) },
                            placeholder = { Text(stringResource(R.string.ai_rule_model_select_hint)) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (!customModel) {
                                            modelSearch = ""
                                            modelMenuExpanded = true
                                        }
                                    },
                                    enabled = !isGenerating,
                                ) {
                                    Icon(
                                        imageVector = if (customModel) Icons.Rounded.Search else Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                    )
                                }
                            },
                            supportingText = {
                                Text(
                                    if (customModel) {
                                        stringResource(R.string.ai_rule_model_custom_hint)
                                    } else {
                                        stringResource(R.string.ai_rule_model_choices, models.size)
                                    },
                                )
                            },
                        )
                        if (!customModel) {
                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false },
                                modifier = Modifier
                                    .widthIn(min = 280.dp, max = 420.dp)
                                    .heightIn(max = 380.dp),
                            ) {
                                OutlinedTextField(
                                    value = modelSearch,
                                    onValueChange = { modelSearch = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    singleLine = true,
                                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                    label = { Text(stringResource(R.string.ai_rule_model_search)) },
                                )
                                val filteredModels = models.filter {
                                    modelSearch.isBlank() || it.contains(modelSearch.trim(), ignoreCase = true)
                                }
                                if (filteredModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.ai_rule_model_no_match)) },
                                        onClick = {},
                                    )
                                } else {
                                    filteredModels.forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = option,
                                                    fontWeight = if (option == model) FontWeight.SemiBold else FontWeight.Normal,
                                                )
                                            },
                                            leadingIcon = {
                                                if (option == model) Icon(Icons.Rounded.Check, contentDescription = null)
                                            },
                                            onClick = {
                                                onModelChange(option)
                                                modelMenuExpanded = false
                                                modelSearch = ""
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = { customModel = !customModel },
                        enabled = !isGenerating,
                    ) {
                        Text(
                            if (customModel) {
                                stringResource(R.string.ai_rule_model_choose_from_list)
                            } else {
                                stringResource(R.string.ai_rule_model_use_custom)
                            },
                        )
                    }
                }

                progress?.let { AiRuleProgressStatus(it, isGenerating) }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = { onConfirm(url.trim()) },
                enabled = canGenerate,
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    )
                } else {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.widthIn(min = 8.dp))
                }
                Text(if (isGenerating) stringResource(R.string.ai_rule_generating) else stringResource(R.string.ai_rule_generate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !isGenerating) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AiRuleStepIndicator(activeStep: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val active = index + 1 <= activeStep
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun AiRuleSectionTitle(step: String, title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = "$step. $title",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AiRuleEmptyServiceCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                text = stringResource(R.string.ai_rule_no_provider),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AiRuleProgressStatus(progress: AiRuleGenerationProgress, isGenerating: Boolean) {
    val stageText =
        stringResource(
            when (progress.stage) {
                AiRuleGenerationStage.PREPARING -> R.string.ai_rule_stage_preparing
                AiRuleGenerationStage.FETCHING_SOURCE -> R.string.ai_rule_stage_fetching
                AiRuleGenerationStage.ANALYZING_SOURCE -> R.string.ai_rule_stage_analyzing
                AiRuleGenerationStage.GENERATING_CANDIDATE -> R.string.ai_rule_stage_generating
                AiRuleGenerationStage.VALIDATING_CANDIDATE -> R.string.ai_rule_stage_validating
                AiRuleGenerationStage.REPAIRING_CANDIDATE -> R.string.ai_rule_stage_repairing
                AiRuleGenerationStage.FETCHING_CONTENT -> R.string.ai_rule_stage_fetching_content
                AiRuleGenerationStage.GENERATING_CONTENT -> R.string.ai_rule_stage_generating_content
                AiRuleGenerationStage.VALIDATING_CONTENT -> R.string.ai_rule_stage_validating_content
                AiRuleGenerationStage.COMPLETED -> R.string.ai_rule_stage_completed
                AiRuleGenerationStage.FAILED -> R.string.ai_rule_stage_failed
            },
        )
    val isFailure = progress.stage == AiRuleGenerationStage.FAILED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isFailure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        isFailure -> Icons.Rounded.Error
                        progress.stage == AiRuleGenerationStage.COMPLETED -> Icons.Rounded.CheckCircle
                        else -> Icons.Rounded.AutoAwesome
                    },
                    contentDescription = null,
                    tint = if (isFailure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.widthIn(min = 8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stageText,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isFailure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (progress.attempt > 1) {
                        Text(
                            text = stringResource(R.string.ai_rule_attempt, progress.attempt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isGenerating) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            if (isGenerating) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            progress.detail?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun String.isHttpUrl(): Boolean =
    runCatching {
        val uri = java.net.URI(trim())
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
