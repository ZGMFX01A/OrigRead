package me.ash.reader.infrastructure.content

import java.net.URI
import javax.inject.Inject
import org.jsoup.nodes.Document

/**
 * 微信公众号单篇文章专用正文提取器。
 *
 * RSS 已经负责提供文章链接；这里只处理 mp.weixin.qq.com 的正文 DOM，
 * 避免通用 Readability 被页面外围脚本、工具栏等结构干扰。
 */
class WeChatArticleContentExtractor @Inject constructor() : ContentExtractor {
    override fun extract(document: Document, sourceUrl: String): List<ContentExtractionCandidate> {
        if (!isWeChatArticleUrl(sourceUrl)) return emptyList()

        val content = document.selectFirst("#js_content")
            ?.takeIf { it.text().isNotBlank() }
            ?: return emptyList()
        val html = content.outerHtml()

        return listOf(
            ContentExtractionCandidate(
                source = ContentExtractionSource.PLATFORM_SPECIFIC,
                html = html,
                title = sequenceOf(
                    document.selectFirst("#activity-name")?.text(),
                    document.selectFirst("meta[property=og:title]")?.attr("content"),
                ).firstOrNull { !it.isNullOrBlank() }?.trim(),
                author = sequenceOf(
                    document.selectFirst("#js_name")?.text(),
                    document.selectFirst("meta[name=author]")?.attr("content"),
                ).firstOrNull { !it.isNullOrBlank() }?.trim(),
                score = ContentCandidateScorer.score(html) + PLATFORM_SOURCE_BONUS,
            )
        )
    }

    private fun isWeChatArticleUrl(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase()
        host == WECHAT_HOST || host.endsWith(".$WECHAT_HOST")
    }.getOrDefault(false)

    private companion object {
        const val WECHAT_HOST = "mp.weixin.qq.com"
        const val PLATFORM_SOURCE_BONUS = 10
    }
}
