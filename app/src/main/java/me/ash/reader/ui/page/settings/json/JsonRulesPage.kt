package me.ash.reader.ui.page.settings.json

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.ui.component.base.Banner
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.MimeType
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.page.settings.RuleMarkdownGuideDialog
import me.ash.reader.ui.page.settings.AiRuleGenerationDialog
import me.ash.reader.ui.page.settings.AiRulePreviewDialog
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.theme.palette.onLight

@Composable
fun JsonRulesPage(
    onBack: () -> Unit,
    viewModel: JsonRulesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState = viewModel.uiState.collectAsStateValue()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showTutorial by remember { mutableStateOf(false) }
    var aiGenerateDialogVisible by remember { mutableStateOf(false) }
    var aiGenerateUrl by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(uiState.aiGenerating, uiState.aiPreview, uiState.aiError) {
        if (!uiState.aiGenerating && (uiState.aiPreview != null || uiState.aiError != null)) {
            aiGenerateDialogVisible = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(uiState.aiNotice) {
        uiState.aiNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearAiNotice()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { input ->
            viewModel.importRules(input.readBytes()) { result ->
                result.fold(
                    onSuccess = {
                        Toast.makeText(
                            context,
                            context.getString(R.string.json_rules_imported, it),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                    onFailure = {
                        errorMessage = context.getString(
                            R.string.json_rules_import_failed,
                            it.message.orEmpty(),
                        )
                    },
                )
            }
        }
    }
    val templateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MimeType.JSON)
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportTemplate { content ->
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MimeType.JSON)
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportRules { content ->
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
    }

    RYScaffold(
        containerColor = MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
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
                    DisplayText(
                        text = stringResource(R.string.json_rules),
                        desc = stringResource(R.string.json_rules_desc),
                    )
                    Banner(
                        title = stringResource(R.string.json_rules_tutorial_title),
                        desc = stringResource(R.string.json_rules_tutorial_summary),
                        onClick = { showTutorial = true },
                    )
                    SettingItem(
                        title = stringResource(R.string.ai_generate_json_rule),
                        desc = stringResource(R.string.ai_generate_rule_desc),
                        icon = Icons.Rounded.AutoAwesome,
                        onClick = { aiGenerateDialogVisible = true },
                        action = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.go_to),
                            )
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingItem(
                        title = stringResource(R.string.export_json_rule_template),
                        icon = Icons.Outlined.Description,
                        onClick = { templateLauncher.launch("json-rule-template.json") },
                        action = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.go_to),
                            )
                        },
                    )
                    SettingItem(
                        title = stringResource(R.string.import_json_rules),
                        icon = Icons.Outlined.Upload,
                        onClick = { importLauncher.launch(arrayOf(MimeType.JSON, MimeType.ANY)) },
                        action = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.go_to),
                            )
                        },
                    )
                    SettingItem(
                        title = stringResource(R.string.export_json_rules),
                        icon = Icons.Outlined.Download,
                        onClick = { exportLauncher.launch("json-rules.json") },
                        action = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.go_to),
                            )
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                items(uiState.rules, key = { it.id }) { rule ->
                    SettingItem(
                        title = rule.name,
                        desc = rule.hosts.joinToString() + " · ${rule.sourceKind} · v${rule.version} · " + stringResource(
                            if (rule.contentPath.isNullOrBlank()) {
                                R.string.rule_capability_list_generic_content
                            } else {
                                R.string.rule_capability_list_and_content
                            },
                        ),
                        separatedActions = true,
                        onClick = { viewModel.setEnabled(rule, !rule.enabled) },
                        action = {
                            Row {
                                RYSwitch(activated = rule.enabled) {
                                    viewModel.setEnabled(rule, !rule.enabled)
                                }
                                Spacer(Modifier.width(8.dp))
                                FeedbackIconButton(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error,
                                    onClick = { viewModel.delete(rule) },
                                )
                            }
                        },
                    )
                }

                item {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        },
    )

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(R.string.json_rule_error_title)) },
            text = { Text(errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }

    if (showTutorial) {
        RuleMarkdownGuideDialog(
            title = stringResource(R.string.json_rules_tutorial_title),
            assetName = "json-rules",
            onDismiss = { showTutorial = false },
        )
    }

    AiRuleGenerationDialog(
        visible = aiGenerateDialogVisible,
        title = stringResource(R.string.ai_generate_json_rule),
        url = aiGenerateUrl,
        providers = uiState.aiSettings.providers.filter { it.enabled },
        defaultProviderId = uiState.aiSettings.defaultProviderId,
        selectedProviderId = uiState.selectedAiProviderId,
        model = uiState.selectedAiModel,
        progress = uiState.aiProgress,
        isGenerating = uiState.aiGenerating,
        onUrlChange = { aiGenerateUrl = it },
        onProviderChange = viewModel::selectAiProvider,
        onModelChange = viewModel::setAiModel,
        onDismissRequest = { aiGenerateDialogVisible = false },
        onConfirm = viewModel::generateAiRule,
    )

    uiState.aiPreview?.let { preview ->
        AiRulePreviewDialog(
            preview = preview,
            onDismiss = viewModel::dismissAiPreview,
            onSave = viewModel::saveAiPreview,
        )
    }

    uiState.aiError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::clearAiError,
            title = { Text(stringResource(R.string.ai_rule_generation_failed)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::clearAiError) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }
}
