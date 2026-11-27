package me.ash.reader.domain.service

import android.util.Log
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI
import me.ash.reader.ui.ext.dollarLast

/** 沿用远端编辑标签的单批数量。 */
private const val READER_EDIT_TAG_BATCH_SIZE = 500

/** 本地和远端阅读状态修改均使用操作开始时的同一账户。 */
internal suspend fun GoogleReaderRssService.markGoogleReaderSelection(selection: ReadSelection) {
    val api = getGoogleReaderAPI(
        requireNotNull(accountService.getAccountById(selection.accountId)) { "阅读操作账户不存在" }
    )
    val ids = readerSelectionIds(selection).map { it.dollarLast() }
    articleDao.markReadSelection(selection)
    ids.chunked(READER_EDIT_TAG_BATCH_SIZE).forEachIndexed { index, chunk ->
        Log.d("RLog", "sync markAsRead: ${index * READER_EDIT_TAG_BATCH_SIZE + chunk.size}/${ids.size} num")
        api.editTag(
            itemIds = chunk,
            mark = if (!selection.isUnread) GoogleReaderAPI.Stream.Read.tag else null,
            unmark = if (selection.isUnread) GoogleReaderAPI.Stream.Read.tag else null,
        ).getOrThrow()
    }
}

/** 仅查询需要改变阅读状态的旧文章集合，保留范围优先顺序与截止时间。 */
private fun GoogleReaderRssService.readerSelectionIds(selection: ReadSelection): List<String> {
    val accountId = selection.accountId
    val before = selection.before
    val unread = !selection.isUnread
    selection.groupId?.let { id ->
        return (if (before == null) articleDao.queryMetadataByGroupIdWhenIsUnread(accountId, id, unread)
        else articleDao.queryMetadataByGroupIdWhenIsUnread(accountId, id, unread, before)).map { it.id }
    }
    selection.feedId?.let { id ->
        return (if (before == null) articleDao.queryMetadataByFeedId(accountId, id, unread)
        else articleDao.queryMetadataByFeedId(accountId, id, unread, before)).map { it.id }
    }
    selection.articleId?.let { return listOf(it) }
    return (if (before == null) articleDao.queryMetadataAll(accountId, isUnread = unread)
    else articleDao.queryMetadataAll(accountId, unread, before)).map { it.id }
}
