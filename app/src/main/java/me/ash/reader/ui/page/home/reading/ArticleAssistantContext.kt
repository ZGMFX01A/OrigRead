package me.ash.reader.ui.page.home.reading

/**
 * 阅读页向 LLM edition 暴露的当前文章上下文。
 *
 * 这里只传递阅读页已经拥有的数据，不让公共阅读代码反向依赖 LLM Runtime。
 */
data class ArticleAssistantContext(
    val articleId: String,
    val title: String,
    val link: String?,
    val originalContent: String,
    /** 兼容旧调用/快照字段；Chat 新请求不再消费派生摘要。 */
    val summary: String? = null,
    /** 兼容旧调用/快照字段；Chat 新请求不再消费译文。 */
    val translatedTitle: String? = null,
    val translatedContent: String? = null,
    /** 用户显式选中的临时文本；整篇摘要/译文仍不会自动进入 Chat Context。 */
    val selectedText: String? = null,
    /** 译文选区可参与提问，但不能伪装成原文 Evidence/Citation。 */
    val selectedTextFromTranslation: Boolean = false,
)
