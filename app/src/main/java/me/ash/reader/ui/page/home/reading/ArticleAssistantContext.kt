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
    val summary: String? = null,
    val translatedTitle: String? = null,
    val translatedContent: String? = null,
    /** LLM edition 从系统正文选区动作传入的临时文本；Standard 永远保持 null。 */
    val selectedText: String? = null,
)
