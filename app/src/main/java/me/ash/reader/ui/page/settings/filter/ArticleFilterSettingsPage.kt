package me.ash.reader.ui.page.settings.filter

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.filter.ArticleFilterRuleType
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch
import me.ash.reader.ui.ext.MimeType
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.theme.palette.onLight

@Composable
fun ArticleFilterSettingsPage(
    onBack: () -> Unit,
    viewModel: ArticleFilterSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState = viewModel.uiState.collectAsStateValue()
    var showAddDialog by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ArticleFilterRuleType.KEYWORD) }
    var error by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openInputStream(uri)?.use { input ->
            viewModel.importRules(input.readBytes()).fold(
                onSuccess = {
                    Toast.makeText(context, context.getString(R.string.filter_rules_imported, it), Toast.LENGTH_LONG).show()
                },
                onFailure = { error = it.message },
            )
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MimeType.JSON)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportRules().toByteArray()) }
    }

    OrigReadScaffold(
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
                        text = stringResource(R.string.article_filter_settings),
                        desc = stringResource(R.string.article_filter_settings_desc),
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingItem(
                        title = stringResource(R.string.filter_stats_title),
                        desc = stringResource(R.string.filter_stats_desc, uiState.stats.totalFiltered),
                        icon = Icons.Outlined.FilterAlt,
                        enabled = false,
                        onClick = {},
                    )
                    SettingItem(
                        title = stringResource(R.string.add_filter_rule),
                        desc = stringResource(R.string.add_filter_keyword_desc),
                        icon = Icons.Outlined.Add,
                        onClick = { showAddDialog = true },
                    )
                    SettingItem(
                        title = stringResource(R.string.import_filter_rules),
                        icon = Icons.Outlined.Upload,
                        onClick = { importLauncher.launch(arrayOf(MimeType.JSON, MimeType.ANY)) },
                    )
                    SettingItem(
                        title = stringResource(R.string.export_filter_rules),
                        icon = Icons.Outlined.Download,
                        onClick = { exportLauncher.launch("article-filter-rules.json") },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                if (uiState.rules.isEmpty()) {
                    item {
                        SettingItem(
                            title = stringResource(R.string.no_filter_rules),
                            desc = stringResource(R.string.no_filter_rules_desc),
                            icon = Icons.Outlined.FilterAlt,
                            enabled = false,
                            onClick = {},
                        )
                    }
                }

                items(uiState.rules, key = { it.id }) { rule ->
                    val scope = if (rule.feedId == null) {
                        stringResource(R.string.global_filter_rule)
                    } else {
                        rule.feedName ?: stringResource(R.string.source_filter_rule)
                    }
                    val typeName = if (rule.type == ArticleFilterRuleType.REGEX) {
                        stringResource(R.string.filter_type_regex)
                    } else {
                        stringResource(R.string.filter_type_keyword)
                    }
                    SettingItem(
                        title = rule.keyword,
                        desc = "$scope · $typeName",
                        separatedActions = true,
                        onClick = { viewModel.setEnabled(rule, !rule.enabled) },
                        action = {
                            Row {
                                OrigReadSwitch(activated = rule.enabled) { viewModel.setEnabled(rule, !rule.enabled) }
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

                item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
            }
        },
    )

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_filter_rule)) },
            text = {
                Column {
                    Row {
                        RadioButton(selected = type == ArticleFilterRuleType.KEYWORD, onClick = { type = ArticleFilterRuleType.KEYWORD })
                        Text(stringResource(R.string.filter_type_keyword), modifier = Modifier.padding(top = 12.dp, end = 12.dp))
                        RadioButton(selected = type == ArticleFilterRuleType.REGEX, onClick = { type = ArticleFilterRuleType.REGEX })
                        Text(stringResource(R.string.filter_type_regex), modifier = Modifier.padding(top = 12.dp))
                    }
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it; error = null },
                        singleLine = true,
                        label = { Text(stringResource(R.string.filter_pattern)) },
                        isError = error != null,
                        supportingText = { error?.let { Text(it) } },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pattern.isNotBlank(),
                    onClick = {
                        viewModel.addGlobalRule(pattern, type).fold(
                            onSuccess = { pattern = ""; showAddDialog = false },
                            onFailure = { error = it.message },
                        )
                    },
                ) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    error?.takeIf { !showAddDialog }?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(stringResource(R.string.filter_rule_error)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.confirm)) } },
        )
    }
}
