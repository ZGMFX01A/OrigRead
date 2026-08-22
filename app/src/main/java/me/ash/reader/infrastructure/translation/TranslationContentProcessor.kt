package me.ash.reader.infrastructure.translation

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 将正文 HTML 转成适合机器翻译的块，并在翻译后恢复为可直接交给阅读器的 HTML。 */
class TranslationContentProcessor {
    data class PreparedContent(
        internal val document: Document,
        val texts: List<String>,
    )

    fun prepare(html: String): PreparedContent {
        val document = Jsoup.parseBodyFragment(html)
        val elements = selectTranslationBlocks(document)
        val texts = elements.map { it.text().trim() }.filter { it.isNotBlank() }
        if (texts.isEmpty()) {
            val plainText = document.body().text().trim()
            if (plainText.isBlank()) {
                throw TranslationException(TranslationErrorCode.EMPTY_CONTENT, "正文中没有可翻译文本")
            }
            document.body().html("<p></p>")
            return PreparedContent(document, listOf(plainText))
        }
        return PreparedContent(document, texts)
    }

    fun render(
        prepared: PreparedContent,
        translatedTexts: List<String>,
        mode: TranslationDisplayMode,
    ): String {
        val document = prepared.document.clone()
        val elements = selectTranslationBlocks(document)
        require(elements.size == translatedTexts.size) {
            "正文翻译块数量不一致"
        }
        when (mode) {
            TranslationDisplayMode.TRANSLATED ->
                elements.zip(translatedTexts).forEach { (element, translated) ->
                    replaceWithTranslatedText(element, translated)
                }
            TranslationDisplayMode.BILINGUAL ->
                elements.zip(translatedTexts).forEach { (element, translated) ->
                    element.after(
                        Element("div")
                            .addClass("origread-translation")
                            .attr(
                                "style",
                                "margin:.35em 0 1em;padding:.65em .8em;border-left:3px solid currentColor;opacity:.82;",
                            )
                            .text(translated)
                    )
                }
        }
        return document.body().html()
    }

    /** 将只含译文的 HTML 恢复为“原文块 + 译文块”的交替结构。 */
    fun renderBilingual(originalHtml: String, translatedHtml: String): String? {
        val originalDocument = Jsoup.parseBodyFragment(originalHtml)
        val translatedDocument = Jsoup.parseBodyFragment(translatedHtml)
        val originalElements = selectTranslationBlocks(originalDocument)
        val translatedElements = selectTranslationBlocks(translatedDocument)

        if (
            originalElements.isEmpty() ||
                originalElements.size != translatedElements.size
        ) {
            return null
        }

        originalElements.zip(translatedElements).forEach { (original, translated) ->
            val translatedText =
                translated.clone().apply {
                    select("img, picture, video, audio, source").remove()
                    select("a:empty").remove()
                }.html().trim()
            if (translatedText.isNotBlank()) {
                original.after(
                    Element("div")
                        .addClass("origread-translation")
                        .attr(
                            "style",
                            "margin:.35em 0 1em;padding:.65em .8em;border-left:3px solid currentColor;opacity:.82;",
                        )
                        .html(translatedText),
                )
            }
        }
        return originalDocument.body().html()
    }

    private fun replaceWithTranslatedText(element: Element, translated: String) {
        val media = element.select("img, picture, video, audio, source").map { it.clone() }
        element.empty().text(translated)
        media.forEach(element::appendChild)
    }

    private fun selectTranslationBlocks(document: Document): List<Element> {
        val candidates =
            document.select("p, li, blockquote, h1, h2, h3, h4, h5, h6, figcaption, td, th")
        val selected =
            candidates.filter { element ->
                element.text().isNotBlank() &&
                    element.parents().none { parent -> parent.tagName() in SKIPPED_TAGS } &&
                    element.select(BLOCK_SELECTOR).none { child -> child !== element }
            }
        return if (selected.isNotEmpty()) selected else listOf(document.body().child(0))
    }

    companion object {
        private const val BLOCK_SELECTOR =
            "p, li, blockquote, h1, h2, h3, h4, h5, h6, figcaption, td, th"
        private val SKIPPED_TAGS = setOf("pre", "code", "kbd", "samp", "script", "style")
    }
}

