package me.ash.reader.ui.page.home.reading

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.ai.AiSummaryDocument
import me.ash.reader.infrastructure.ai.AiSummaryProgressStage
import me.ash.reader.infrastructure.ai.AiSummaryStatus
import me.ash.reader.ui.motion.origReadFadeThroughTransform
import me.ash.reader.ui.motion.origReadVisibilityEnter
import me.ash.reader.ui.motion.origReadVisibilityExit

private enum class AiSummaryBodyMode {
    Document,
    Streaming,
    Loading,
}

/**
 * 阅读页内的非模态 AI 摘要面板。
 * 摘要和正文拥有各自滚动区域，用户无需关闭摘要即可继续阅读和操作正文。
 */
@Composable
internal fun AiSummaryPanel(
    document: AiSummaryDocument?,
    isLoading: Boolean,
    progressStage: AiSummaryProgressStage?,
    elapsedSeconds: Int,
    activeProviderName: String?,
    activeModel: String?,
    streamingSummaryPreview: String,
    streamingReasoningPreview: String,
    onClose: () -> Unit,
    onRegenerate: () -> Unit,
    /** LLM edition 可提供文章级追问入口；Standard 传 null 时摘要 UI 完全保持原样。 */
    onAskArticle: (() -> Unit)? = null,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = activeModel ?: document?.model.orEmpty()
    // 暂不在阅读主流程展示模型思考。底层仍保留 reasoning 接收与缓存，后续需要时可恢复 UI。
    // var reasoningExpanded by remember(document?.reasoning) { mutableStateOf(false) }
    // 与 Desktop 统一使用轻科技蓝作为 AI 识别色，不跟随普通主题主色漂移。
    val accentColor = AiSummaryAccentBlue.copy(alpha = 0.92f)
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // 左侧细色条承担 AI 区域识别，不再依赖重阴影或深色背景制造层级。
        Column(
            modifier =
                Modifier.drawBehind {
                        drawRect(
                            color = accentColor,
                            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                        )
                    }
                    .padding(start = 3.dp)
        ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(start = 13.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AiSummaryAccentIcon(
                        contentDescription = null,
                        active = true,
                        size = 30.dp,
                        iconSize = 18.dp,
                    )
                    // 标题与模型构成主标题 + 副标题关系；小字号模型贴近标题右下方，而不是垂直居中。
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = stringResource(R.string.ai_summary),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (model.isNotBlank()) {
                            Text(
                                text = model,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier.weight(1f)
                                        .padding(start = 8.dp, bottom = 1.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.ai_summary_close),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                val bodyMode =
                    when {
                        document != null -> AiSummaryBodyMode.Document
                        streamingSummaryPreview.isNotBlank() ||
                            streamingReasoningPreview.isNotBlank() -> AiSummaryBodyMode.Streaming
                        else -> AiSummaryBodyMode.Loading
                    }
                val bodyTransform = origReadFadeThroughTransform()
                AnimatedContent(
                    targetState = bodyMode,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    transitionSpec = { bodyTransform },
                    label = "ai_summary_body",
                ) { mode ->
                    when (mode) {
                        AiSummaryBodyMode.Document -> {
                            val summaryDocument = document
                            if (summaryDocument != null) {
                                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .verticalScroll(rememberScrollState())
                                                .padding(horizontal = 18.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        if (summaryDocument.status == AiSummaryStatus.NOT_NEEDED) {
                                            Column(
                                                modifier =
                                                    Modifier.fillMaxWidth()
                                                        .padding(vertical = 18.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    text =
                                                        stringResource(
                                                            R.string.ai_summary_not_needed_title
                                                        ),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    text =
                                                        stringResource(
                                                            R.string.ai_summary_not_needed_local
                                                        ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        } else {
                                            AiMarkdown(
                                                summaryDocument.summary,
                                                hideLeadingSummaryHeading = true,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        AiSummaryBodyMode.Streaming -> {
                            // 流式阶段只更新 Text；AnimatedContent 的 targetState 不随 token 改变。
                            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                                Column(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (streamingReasoningPreview.isNotBlank()) {
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 0.dp,
                                        ) {
                                            Column(
                                                modifier =
                                                    Modifier.fillMaxWidth().padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.ai_reasoning),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    text = streamingReasoningPreview,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                    if (streamingSummaryPreview.isNotBlank()) {
                                        Text(
                                            text = streamingSummaryPreview,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }

                        AiSummaryBodyMode.Loading ->
                            Box(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = progressStageLabel(progressStage),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text =
                                            stringResource(
                                                R.string.ai_summary_elapsed,
                                                elapsedSeconds,
                                            ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                    }
                }
                AnimatedVisibility(
                    visible = isLoading,
                    enter = origReadVisibilityEnter(),
                    exit = origReadVisibilityExit(),
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(start = 12.dp, top = 1.dp, end = 6.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(
                        visible = isLoading,
                        enter = origReadVisibilityEnter(),
                        exit = origReadVisibilityExit(),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_summary_elapsed, elapsedSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    AnimatedVisibility(
                        visible = !isLoading && onAskArticle != null,
                        enter = origReadVisibilityEnter(),
                        exit = origReadVisibilityExit(),
                    ) {
                        onAskArticle?.let { askArticle ->
                            IconButton(
                                onClick = askArticle,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Forum,
                                    contentDescription = stringResource(R.string.ai_ask_article),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    val actionTransform = origReadFadeThroughTransform()
                    IconButton(
                        onClick = if (isLoading) onStop else onRegenerate,
                        modifier = Modifier.size(36.dp),
                    ) {
                        AnimatedContent(
                            targetState = isLoading,
                            transitionSpec = { actionTransform },
                            label = "ai_summary_action",
                        ) { loading ->
                            Icon(
                                imageVector =
                                    if (loading) Icons.Rounded.Stop else Icons.Rounded.Refresh,
                                contentDescription =
                                    stringResource(
                                        if (loading) R.string.ai_summary_stop
                                        else R.string.ai_summary_regenerate
                                    ),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun progressStageLabel(stage: AiSummaryProgressStage?): String =
    stringResource(
        when (stage) {
            AiSummaryProgressStage.PREPARING -> R.string.ai_summary_preparing
            AiSummaryProgressStage.FINALIZING -> R.string.ai_summary_finalizing
            AiSummaryProgressStage.GENERATING,
            null -> R.string.ai_summary_generating
        }
    )
