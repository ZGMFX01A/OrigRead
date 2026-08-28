package me.ash.reader.llm.quickmessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import me.ash.reader.R
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.component.base.OrigReadSwitch

@HiltViewModel
class LlmQuickMessageSettingsViewModel @Inject constructor(
    private val repository: LlmQuickMessageRepository,
) : ViewModel() {
    val messages = repository.messages

    fun save(
        id: String?,
        title: String,
        content: String,
    ): String? =
        runCatching {
                if (id == null) repository.create(title, content)
                else repository.update(id, title, content)
            }
            .exceptionOrNull()
            ?.message

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = repository.setEnabled(id, enabled)

    fun delete(id: String) = repository.delete(id)

    fun moveUp(id: String) = repository.move(id, -1)

    fun moveDown(id: String) = repository.move(id, 1)
}

/** P6.4 Quick Messages 管理页：全局阅读快捷消息，不绑定 Agent，也不承载 Prompt/Tool 权限。 */
@Composable
fun LlmQuickMessageSettingsPage(
    onBack: () -> Unit,
    viewModel: LlmQuickMessageSettingsViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<LlmQuickMessage?>(null) }
    var createVisible by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LlmQuickMessage?>(null) }

    OrigReadScaffold(
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
            )
        },
        actions = {
            IconButton(onClick = { createVisible = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.llm_quick_message_add),
                )
            }
        },
        content = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DisplayText(
                        text = stringResource(R.string.llm_quick_messages_title),
                        desc = stringResource(R.string.llm_quick_messages_desc),
                    )
                }
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.llm_quick_messages_empty),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(messages, key = LlmQuickMessage::id) { message ->
                        QuickMessageCard(
                            message = message,
                            text = resolveQuickMessageText(context, message),
                            canMoveUp = message.order > 0,
                            canMoveDown = message.order < messages.lastIndex,
                            onEnabledChange = { viewModel.setEnabled(message.id, it) },
                            onEdit = { editing = message },
                            onDelete = { deleteTarget = message },
                            onMoveUp = { viewModel.moveUp(message.id) },
                            onMoveDown = { viewModel.moveDown(message.id) },
                        )
                    }
                }
            }
        },
    )

    if (createVisible) {
        QuickMessageEditorSheet(
            initial = null,
            initialText = null,
            onDismiss = { createVisible = false },
            onSave = { title, content ->
                viewModel.save(null, title, content).also { error ->
                    if (error == null) createVisible = false
                }
            },
        )
    }
    editing?.let { message ->
        QuickMessageEditorSheet(
            initial = message,
            initialText = resolveQuickMessageText(context, message),
            onDismiss = { editing = null },
            onSave = { title, content ->
                viewModel.save(message.id, title, content).also { error ->
                    if (error == null) editing = null
                }
            },
        )
    }
    deleteTarget?.let { message ->
        val displayText = resolveQuickMessageText(context, message)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.llm_quick_message_delete_title)) },
            text = { Text(stringResource(R.string.llm_quick_message_delete_desc, displayText.title)) },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(message.id)
                        deleteTarget = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
        )
    }
}

@Composable
private fun QuickMessageCard(
    message: LlmQuickMessage,
    text: LlmQuickMessageText,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = text.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = text.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OrigReadSwitch(activated = message.enabled) { onEnabledChange(!message.enabled) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.llm_quick_message_move_up))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.llm_quick_message_move_down))
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.llm_quick_message_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickMessageEditorSheet(
    initial: LlmQuickMessage?,
    initialText: LlmQuickMessageText?,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String) -> String?,
) {
    var title by rememberSaveable(initial?.id) { mutableStateOf(initialText?.title.orEmpty()) }
    var content by rememberSaveable(initial?.id) { mutableStateOf(initialText?.content.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                    stringResource(
                        if (initial == null) R.string.llm_quick_message_add
                        else R.string.llm_quick_message_edit
                    ),
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(LlmQuickMessageRepository.MAX_TITLE_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.llm_quick_message_title_field)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(LlmQuickMessageRepository.MAX_CONTENT_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.llm_quick_message_content_field)) },
                minLines = 5,
                maxLines = 10,
                supportingText = { Text(stringResource(R.string.llm_quick_message_variables_hint)) },
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { error = onSave(title, content) },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && content.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
