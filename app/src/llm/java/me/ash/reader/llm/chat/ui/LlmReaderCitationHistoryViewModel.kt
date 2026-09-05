package me.ash.reader.llm.chat.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import me.ash.reader.llm.chat.data.LlmChatRepository
import me.ash.reader.llm.chat.data.LlmCitationAnnotationWithRefs
import me.ash.reader.llm.chat.data.LlmCitationRefEntity
import me.ash.reader.llm.chat.data.LlmMessageEntity

internal data class LlmHistoricalCitationLayer(
    val assistantMessage: LlmMessageEntity,
    val citationRefs: List<LlmCitationRefEntity>,
    val citationAnnotations: List<LlmCitationAnnotationWithRefs>,
)

/**
 * Reader-only 历史 Citation 恢复入口。
 *
 * 这里故意不复用 [LlmChatViewModel]：Chat ViewModel 的文章切换会停止当前生成任务，而 Reader
 * 在助手关闭后仍允许后台生成继续。这个 ViewModel 只读 Room，不绑定 Provider/Runtime。
 */
@HiltViewModel
internal class LlmReaderCitationHistoryViewModel @Inject constructor(
    private val repository: LlmChatRepository,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLayer(articleId: String): Flow<LlmHistoricalCitationLayer?> {
        val normalizedArticleId = articleId.trim()
        if (normalizedArticleId.isBlank()) return flowOf(null)
        return repository
            .observeLatestRestorableCitationAssistant(normalizedArticleId)
            .flatMapLatest { assistant ->
                if (assistant == null) {
                    flowOf(null)
                } else {
                    repository.observeCitationRefs(assistant.conversationId)
                        .combine(repository.observeCitationAnnotations(assistant.conversationId)) { refs, annotations ->
                            val scoped = refs.filter { it.assistantMessageId == assistant.id }
                            val scopedAnnotations =
                                annotations.filter { it.annotation.assistantMessageId == assistant.id }
                            scoped.takeIf(List<LlmCitationRefEntity>::isNotEmpty)?.let {
                                LlmHistoricalCitationLayer(
                                    assistantMessage = assistant,
                                    citationRefs = it,
                                    citationAnnotations = scopedAnnotations,
                                )
                            }
                        }
                }
            }
    }
}
