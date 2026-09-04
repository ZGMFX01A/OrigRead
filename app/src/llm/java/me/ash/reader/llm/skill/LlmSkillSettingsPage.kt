package me.ash.reader.llm.skill

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.llm.chat.ui.LlmRichMarkdown
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch
import me.ash.reader.ui.page.adaptive.OrigReadAdaptiveContent
import me.ash.reader.ui.page.adaptive.OrigReadContentWidth

data class LlmSkillSettingsUiState(
    val skillState: LlmSkillState = LlmSkillState(),
    val importMessage: String? = null,
    val importError: String? = null,
)

@HiltViewModel
class LlmSkillSettingsViewModel @Inject constructor(
    private val repository: LlmSkillRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LlmSkillSettingsUiState(skillState = repository.current()))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.collect { state -> _uiState.update { it.copy(skillState = state) } }
        }
    }

    fun importSkill(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.import(uri) }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            importMessage =
                                if (result.replaced) "${result.skill.displayName} updated"
                                else "${result.skill.displayName} installed",
                            importError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(importMessage = null, importError = error.message ?: "Skill import failed")
                    }
                }
        }
    }

    fun setEnabled(skillId: String, enabled: Boolean) = repository.setEnabled(skillId, enabled)
    fun delete(skillId: String) = repository.delete(skillId)
    fun bind(task: LlmSkillTask, skillId: String?) = repository.setBinding(task, skillId)

    /** 保存应用内编辑的 SKILL.md；失败时返回可展示错误，成功返回 null。 */
    suspend fun createLocalSkill(
        fileName: String,
        markdown: String,
    ): String? {
        var failureMessage: String? = null
        runCatching { repository.createLocal(fileName, markdown) }
            .onSuccess { result ->
                _uiState.update {
                    it.copy(
                        importMessage =
                            if (result.replaced) "${result.skill.displayName} updated"
                            else "${result.skill.displayName} installed",
                        importError = null,
                    )
                }
            }
            .onFailure { error ->
                failureMessage = error.message ?: "Skill save failed"
                _uiState.update {
                    it.copy(importMessage = null, importError = failureMessage)
                }
            }
        return failureMessage
    }
}

/** LLM edition 的 Skill 管理页；只管理受控指令/文本资源，不提供脚本执行入口。 */
@Composable
fun LlmSkillSettingsPage(
    onBack: () -> Unit,
    viewModel: LlmSkillSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardEmptyMessage = stringResource(R.string.llm_skill_clipboard_empty)
    val invalidSkillMessage = stringResource(R.string.llm_skill_invalid)
    val coroutineScope = rememberCoroutineScope()
    var previewSkill by remember { mutableStateOf<LlmSkillRecord?>(null) }
    var createSkillVisible by remember { mutableStateOf(false) }
    var createDraft by remember { mutableStateOf("") }
    var saveFileNameVisible by remember { mutableStateOf(false) }
    var suggestedFileName by remember { mutableStateOf("") }
    var saveFileNameError by remember { mutableStateOf<String?>(null) }
    var draftError by remember { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importSkill)
        }

    OrigReadScaffold(
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
            )
        },
        content = {
            OrigReadAdaptiveContent(width = OrigReadContentWidth.Comfortable) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        DisplayText(
                            text = stringResource(R.string.llm_skill_title),
                            desc = stringResource(R.string.llm_skill_desc),
                        )
                    }
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        createDraft = readClipboardText(context).orEmpty()
                                        draftError = null
                                        createSkillVisible = true
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.llm_skill_create))
                                }
                                OutlinedButton(
                                    onClick = {
                                        launcher.launch(
                                            arrayOf(
                                                "application/zip",
                                                "application/x-zip-compressed",
                                                "text/markdown",
                                                "text/plain",
                                                "application/octet-stream",
                                            )
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Outlined.UploadFile, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.llm_skill_import))
                                }
                            }
                            Text(
                                text = stringResource(R.string.llm_skill_folder_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    uiState.importMessage?.let { message ->
                        item { StatusText(message, error = false) }
                    }
                    uiState.importError?.let { message ->
                        item { StatusText(message, error = true) }
                    }
                    if (uiState.skillState.skills.any(LlmSkillRecord::enabled)) {
                        item {
                            SkillBindingsCard(
                                state = uiState.skillState,
                                onBind = viewModel::bind,
                            )
                        }
                    }
                    if (uiState.skillState.skills.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.llm_skill_empty),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        uiState.skillState.skills.forEach { skill ->
                            item(key = skill.id) {
                                SkillCard(
                                    skill = skill,
                                    onEnabledChange = { viewModel.setEnabled(skill.id, it) },
                                    onPreview = { previewSkill = skill },
                                    onDelete = { viewModel.delete(skill.id) },
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.size(12.dp)) }
                }
            }
        },
    )

    previewSkill?.let { skill ->
        SkillPreviewSheet(
            skill = skill,
            onDismiss = { previewSkill = null },
        )
    }

    if (createSkillVisible) {
        SkillEditorSheet(
            markdown = createDraft,
            errorMessage = draftError,
            onMarkdownChange = {
                createDraft = it
                draftError = null
            },
            onPaste = {
                val clipboardText = readClipboardText(context)
                if (clipboardText.isNullOrBlank()) {
                    draftError = clipboardEmptyMessage
                } else {
                    createDraft = clipboardText
                    draftError = null
                }
            },
            onDismiss = {
                createSkillVisible = false
                draftError = null
            },
            onSave = {
                runCatching { LlmSkillParser.parse(createDraft) }
                    .onSuccess { parsed ->
                        suggestedFileName = "${parsed.name}.md"
                        draftError = null
                        saveFileNameError = null
                        saveFileNameVisible = true
                    }
                    .onFailure { error ->
                        draftError = error.message ?: invalidSkillMessage
                    }
            },
        )
    }

    if (saveFileNameVisible) {
        SaveSkillFileNameDialog(
            initialFileName = suggestedFileName,
            errorMessage = saveFileNameError,
            onDismiss = {
                saveFileNameVisible = false
                saveFileNameError = null
            },
            onConfirm = { fileName ->
                coroutineScope.launch {
                    val error = viewModel.createLocalSkill(fileName, createDraft)
                    if (error == null) {
                        saveFileNameVisible = false
                        createSkillVisible = false
                        createDraft = ""
                        saveFileNameError = null
                        draftError = null
                    } else {
                        saveFileNameError = error
                    }
                }
            },
        )
    }
}

@Composable
private fun StatusText(message: String, error: Boolean) {
    Text(
        text = message,
        modifier = Modifier.padding(horizontal = 24.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SkillBindingsCard(
    state: LlmSkillState,
    onBind: (LlmSkillTask, String?) -> Unit,
) {
    OutlinedCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.llm_skill_bindings), style = MaterialTheme.typography.titleMedium)
            LlmSkillTask.entries.filterNot { it == LlmSkillTask.CHAT }.forEach { task ->
                SkillBindingRow(
                    task = task,
                    skillId = state.bindings.skillId(task),
                    skills = state.skills.filter(LlmSkillRecord::enabled),
                    onBind = { onBind(task, it) },
                )
            }
        }
    }
}

@Composable
private fun SkillBindingRow(
    task: LlmSkillTask,
    skillId: String?,
    skills: List<LlmSkillRecord>,
    onBind: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = skills.firstOrNull { it.id == skillId }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = task.displayName(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(
                    text = selected?.displayName ?: stringResource(R.string.llm_skill_default),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.llm_skill_default)) },
                    onClick = {
                        expanded = false
                        onBind(null)
                    },
                )
                skills.forEach { skill ->
                    DropdownMenuItem(
                        text = { Text(skill.displayName) },
                        onClick = {
                            expanded = false
                            onBind(skill.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: LlmSkillRecord,
    onEnabledChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    var deleteConfirmationVisible by remember { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(skill.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (skill.displayName != skill.id) {
                        Text(skill.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OrigReadSwitch(activated = skill.enabled) { onEnabledChange(!skill.enabled) }
                IconButton(onClick = onPreview) {
                    Icon(Icons.Outlined.Visibility, contentDescription = stringResource(R.string.llm_skill_preview))
                }
                IconButton(onClick = { deleteConfirmationVisible = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.llm_skill_delete))
                }
            }
            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                skill.version?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                if (skill.hasScripts) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.llm_skill_scripts_ignored)) },
                    )
                }
            }
        }
    }

    if (deleteConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { deleteConfirmationVisible = false },
            title = { Text(stringResource(R.string.llm_skill_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.llm_skill_delete_confirm,
                        skill.displayName,
                    )
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmationVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmationVisible = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.llm_skill_delete))
                }
            },
        )
    }
}

/**
 * 应用内 Skill 编辑器。
 *
 * 长文本编辑放在大 Bottom Sheet；文件名这种短字段留到保存确认 Dialog，避免在管理页长期占空间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditorSheet(
    markdown: String,
    errorMessage: String?,
    onMarkdownChange: (String) -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.llm_skill_create_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.llm_skill_create_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onPaste) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.llm_skill_paste))
                }
            }

            OutlinedTextField(
                value = markdown,
                onValueChange = onMarkdownChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("SKILL.md") },
                placeholder = { Text(stringResource(R.string.llm_skill_editor_placeholder)) },
                isError = errorMessage != null,
            )

            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                FilledTonalButton(
                    onClick = onSave,
                    enabled = markdown.isNotBlank(),
                ) {
                    Text(stringResource(R.string.llm_skill_save))
                }
            }
        }
    }
}

/** 保存本地 Skill 时只询问短文件名；`.md` 省略时由仓储自动补齐。 */
@Composable
private fun SaveSkillFileNameDialog(
    initialFileName: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var fileName by remember(initialFileName) { mutableStateOf(initialFileName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.llm_skill_file_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.llm_skill_file_name)) },
                    supportingText = { Text(stringResource(R.string.llm_skill_file_name_desc)) },
                    isError = errorMessage != null,
                )
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(fileName.trim()) },
                enabled = fileName.isNotBlank(),
            ) {
                Text(stringResource(R.string.llm_skill_save))
            }
        },
    )
}

/** 读取系统剪贴板首项并按文本语义转换；空剪贴板返回 null。 */
private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf(String::isNotBlank)
}

/**
 * 使用与 LLM Chat 一致的富 Markdown 渲染器预览 Skill 正文。
 * Frontmatter 已在导入时解析为结构化字段，因此顶部单独展示名称/描述，正文专注于 SKILL.md 指令。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillPreviewSheet(
    skill: LlmSkillRecord,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = skill.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (skill.displayName != skill.id) {
                        Text(
                            text = skill.id,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { HorizontalDivider() }
            item {
                LlmRichMarkdown(
                    markdown = skill.instructions,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (skill.resources.isNotEmpty()) {
                item {
                    Text(
                        text = skill.resources.joinToString("\n") { it.path },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
        }
    }
}

private fun LlmSkillTask.displayName(): String =
    when (this) {
        LlmSkillTask.SUMMARY -> "Summary"
        LlmSkillTask.TRANSLATION -> "Translation"
        LlmSkillTask.CHAT -> "Chat"
        LlmSkillTask.ARTICLE_ANALYSIS -> "Article Analysis"
    }
