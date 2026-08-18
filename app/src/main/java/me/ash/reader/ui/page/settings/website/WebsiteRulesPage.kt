package me.ash.reader.ui.page.settings.website

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.MimeType
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.page.settings.RuleMarkdownGuideDialog
import me.ash.reader.ui.page.settings.AiRulePreviewDialog
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.theme.palette.onLight

@Composable
fun WebsiteRulesPage(
    onBack: () -> Unit,
    viewModel: WebsiteRulesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState = viewModel.uiState.collectAsStateValue()
    var testDialogVisible by remember { mutableStateOf(false) }
    var tutorialVisible by remember { mutableStateOf(false) }
    var aiGenerateDialogVisible by remember { mutableStateOf(false) }
    var aiGenerateUrl by remember { mutableStateOf("") }
    var testUrl by remember { mutableStateOf("") }
    var resultDialogTitle by remember { mutableStateOf<String?>(null) }
    var resultDialogMessage by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { input ->
            viewModel.importRules(input.readBytes()) { result ->
                result.fold(
                    onSuccess = {
                        Toast.makeText(
                            context,
                            context.getString(R.string.website_rules_imported, it),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                    onFailure = {
                        resultDialogTitle = context.getString(R.string.website_rule_error_title)
                        resultDialogMessage =
                            context.getString(R.string.website_rules_import_failed, it.message.orEmpty())
                    },
                )
            }
        }
    }

    val templateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MimeType.JSON)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportTemplate { content ->
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MimeType.JSON)) { uri ->
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
                    DisplayText(text = stringResource(R.string.website_rules), desc = "")
                    Spacer(Modifier.height(16.dp))
                    SettingItem(
                        title = stringResource(R.string.website_rule_tutorial),
                        icon = Icons.Outlined.HelpOutline,
                        onClick = { tutorialVisible = true },
                        action = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.go_to),
                            )
                        },
                    )
                    SettingItem(
                        modifier = Modifier.alpha(0.5f),
                        title = stringResource(R.string.ai_generate_website_rule),
                        desc = stringResource(R.string.ai_rule_generation_unavailable_desc),
                        icon = Icons.Rounded.AutoAwesome,
                        onClick = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.ai_rule_generation_unavailable_message),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                        action = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.go_to),
                            )
                        },
                    )
                    SettingItem(
                        title = stringResource(R.string.export_website_rule_template),
                        icon = Icons.Outlined.Description,
                        onClick = { templateLauncher.launch("website-rule-template.json") },
                        action = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.go_to),
                            )
                        },
                    )
                    SettingItem(
                        title = stringResource(R.string.import_website_rules),
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
                        title = stringResource(R.string.export_website_rules),
                        icon = Icons.Outlined.Download,
                        onClick = { exportLauncher.launch("website-rules.json") },
                        action = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.go_to),
                            )
                        },
                    )
                    SettingItem(
                        title = stringResource(R.string.test_website_rule),
                        icon = Icons.Outlined.Search,
                        onClick = { testDialogVisible = true },
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
                        desc = rule.hosts.joinToString() + " · v${rule.version}",
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

    if (tutorialVisible) {
        RuleMarkdownGuideDialog(
            title = stringResource(R.string.website_rule_tutorial),
            assetName = "website-rules",
            onDismiss = { tutorialVisible = false },
        )
    }

    TextFieldDialog(
        visible = aiGenerateDialogVisible,
        title = stringResource(R.string.ai_generate_website_rule),
        value = aiGenerateUrl,
        placeholder = "https://www.example.com/news/",
        onValueChange = { aiGenerateUrl = it },
        onDismissRequest = { aiGenerateDialogVisible = false },
        onConfirm = { url ->
            aiGenerateDialogVisible = false
            viewModel.generateAiRule(url)
        },
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

    TextFieldDialog(
        visible = testDialogVisible,
        title = stringResource(R.string.test_website_rule),
        value = testUrl,
        placeholder = "https://www.example.com/",
        onValueChange = { testUrl = it },
        onDismissRequest = { testDialogVisible = false },
        onConfirm = { url ->
            viewModel.test(url) { result ->
                resultDialogTitle = context.getString(R.string.test_website_rule)
                resultDialogMessage =
                    result.fold(
                        onSuccess = { context.getString(R.string.website_rule_test_success, it) },
                        onFailure = { context.getString(R.string.website_rule_test_failed, it.message.orEmpty()) },
                    )
            }
            testDialogVisible = false
        },
    )

    if (resultDialogMessage != null) {
        AlertDialog(
            onDismissRequest = {
                resultDialogTitle = null
                resultDialogMessage = null
            },
            title = { Text(resultDialogTitle.orEmpty()) },
            text = { Text(resultDialogMessage.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resultDialogTitle = null
                        resultDialogMessage = null
                    }
                ) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }
}
