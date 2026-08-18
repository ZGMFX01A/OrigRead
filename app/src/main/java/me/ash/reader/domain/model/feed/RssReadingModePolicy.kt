package me.ash.reader.domain.model.feed

/**
 * RSS / Atom 的正文由 Feed 本身提供。
 *
 * 历史版本曾允许 RSS 来源误带“全文解析 / 浏览器打开”状态；当前产品语义下这两个
 * 开关只属于 Website 等网页来源，因此任何 RSS 来源进入持久化前都应归一为 false。
 */
fun Feed.normalizeRssReadingMode(): Feed =
    if (sourceType == SourceType.RSS && (isFullContent || isBrowser)) {
        copy(isFullContent = false, isBrowser = false)
    } else {
        this
    }

