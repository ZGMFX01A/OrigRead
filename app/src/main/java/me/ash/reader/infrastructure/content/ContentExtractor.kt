package me.ash.reader.infrastructure.content

import org.jsoup.nodes.Document

/** 通用正文提取器。每个实现只负责生成候选，不负责最终选择。 */
fun interface ContentExtractor {
    fun extract(document: Document, sourceUrl: String): List<ContentExtractionCandidate>
}
