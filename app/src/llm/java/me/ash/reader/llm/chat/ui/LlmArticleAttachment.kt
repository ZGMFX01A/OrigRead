package me.ash.reader.llm.chat.ui

import me.ash.reader.llm.chat.data.LlmConversationArticleEntity
import me.ash.reader.llm.runtime.LlmContextItem
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.ui.page.home.reading.ArticleAssistantContext

/**
 * P6.7 多文章 Context 的附加文章快照。
 *
 * 该类型只承载用户额外附加到当前阅读会话的文章数据，不替代 [ArticleAssistantContext]，
 * 也不建立第二套 Runtime Context 模型；真正发请求时仍统一转换为 [LlmContextItem]。
 */
data class LlmArticleAttachment(
    val articleId: String,
    val title: String,
    val link: String?,
    val originalContent: String,
    val summary: String? = null,
)

/** 将 Room 中保存的活动附件快照恢复为 Runtime/UI 共用的文章附件。 */
internal fun LlmConversationArticleEntity.toArticleAttachment(): LlmArticleAttachment =
    LlmArticleAttachment(
        articleId = articleId,
        title = title,
        link = link,
        originalContent = originalContent,
        summary = summary,
    )

/** 将活动附件转换为会话级 Room 快照；position 由当前 UI 顺序唯一决定。 */
internal fun LlmArticleAttachment.toConversationArticleEntity(
    conversationId: String,
    position: Int,
    createdAt: Long,
): LlmConversationArticleEntity =
    LlmConversationArticleEntity(
        conversationId = conversationId,
        articleId = articleId,
        title = title,
        link = link,
        originalContent = originalContent,
        summary = summary,
        position = position,
        createdAt = createdAt,
    )

/**
 * 将附加文章转换为现有 ARTICLE / ARTICLE_SUMMARY Context。
 *
 * 当前文章永远由 [buildArticleContextItems] 提供主 Context，因此这里会主动排除同 articleId，
 * 防止产生重复 Context ID。附加文章摘要仍可辅助理解，但 Citation 只会指向其原始正文；
 * 附加文章不占用当前文章专属的 evidence reserve。
 */
internal fun buildAdditionalArticleContextItems(
    currentArticleId: String,
    attachments: List<LlmArticleAttachment>,
): List<LlmContextItem> =
    buildList {
        normalizedAdditionalArticleAttachments(currentArticleId, attachments).forEach { attachment ->
            attachment.summary?.let { summary ->
                add(
                    LlmContextItem(
                        id = "article:${attachment.articleId}:summary",
                        type = LlmContextType.ARTICLE_SUMMARY,
                        title = attachment.title,
                        sourceId = attachment.link,
                        internalArticleId = attachment.articleId,
                        content = summary,
                        priority = 95,
                    )
                )
            }
            attachment.originalContent.takeIf(String::isNotBlank)?.let { original ->
                add(
                    LlmContextItem(
                        id = "article:${attachment.articleId}:original",
                        type = LlmContextType.ARTICLE,
                        title = attachment.title,
                        sourceId = attachment.link,
                        internalArticleId = attachment.articleId,
                        content = original,
                        priority = 90,
                    )
                )
            }
        }
    }

/** 当前文章始终在前；附加文章只在普通 CHAT 中拼入同一 ContextComposer。 */
internal fun buildRequestArticleContextItems(
    currentArticle: ArticleAssistantContext,
    attachments: List<LlmArticleAttachment>,
    includeAdditionalArticles: Boolean,
): List<LlmContextItem> =
    buildArticleContextItems(currentArticle) +
        if (includeAdditionalArticles) {
            buildAdditionalArticleContextItems(currentArticle.articleId, attachments)
        } else {
            emptyList()
        }

/**
 * 统一清洗附加文章：排除当前文章、空 ID、无正文且无摘要的无效快照，并按首次出现去重。
 * 稳定保留用户附件顺序，使同优先级 Context 的 Composer 顺序与后续 [R#] 编号可预测。
 */
internal fun normalizedAdditionalArticleAttachments(
    currentArticleId: String,
    attachments: List<LlmArticleAttachment>,
): List<LlmArticleAttachment> {
    val normalizedCurrentId = currentArticleId.trim()
    val seenArticleIds = linkedSetOf<String>()
    return attachments.mapNotNull { attachment ->
        val articleId = attachment.articleId.trim()
        if (
            articleId.isBlank() ||
                articleId == normalizedCurrentId ||
                !seenArticleIds.add(articleId)
        ) {
            return@mapNotNull null
        }
        val originalContent = attachment.originalContent.trim()
        val summary = attachment.summary?.trim()?.takeIf(String::isNotBlank)
        if (originalContent.isBlank() && summary == null) return@mapNotNull null
        attachment.copy(
            articleId = articleId,
            title = attachment.title.trim(),
            link = attachment.link?.trim()?.takeIf(String::isNotBlank),
            originalContent = originalContent,
            summary = summary,
        )
    }
}

/** 同一 articleId 重复附加时原位替换快照，避免改变附件顺序和后续引用编号。 */
internal fun upsertAdditionalArticleAttachment(
    currentArticleId: String,
    existing: List<LlmArticleAttachment>,
    attachment: LlmArticleAttachment,
): List<LlmArticleAttachment> {
    val normalized =
        normalizedAdditionalArticleAttachments(currentArticleId, listOf(attachment)).singleOrNull()
            ?: return existing
    val current = normalizedAdditionalArticleAttachments(currentArticleId, existing)
    val index = current.indexOfFirst { it.articleId == normalized.articleId }
    if (index < 0) return current + normalized
    return current.toMutableList().apply { this[index] = normalized }
}
