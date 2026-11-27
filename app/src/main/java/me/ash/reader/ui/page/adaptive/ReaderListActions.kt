package me.ash.reader.ui.page.adaptive

import android.net.Uri
import androidx.lifecycle.viewModelScope
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.service.GoogleReaderRssService
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.infrastructure.preference.PullToLoadNextFeedPreference

/** 一次批量已读操作捕获目标范围，交给应用作用域完成落库。 */
internal data class ReaderReadStatusRequest(
    val groupId: String?, val feedId: String?, val articleId: String?,
    val conditions: MarkAsReadConditions, val isUnread: Boolean,
)

/** 列表批量状态仍沿用原服务入口，参数由一次请求快照携带。 */
internal fun ArticleListReaderViewModel.performUpdateReadStatus(
    request: ReaderReadStatusRequest,
) {
    applicationScope.launch(ioDispatcher) {
        rssService
            .get()
            .markAsRead(
                groupId = request.groupId,
                feedId = request.feedId,
                articleId = request.articleId,
                before = request.conditions.toDate(),
                isUnread = request.isUnread,
            )
    }
}

/** 收藏状态先捕获目标文章，后台写入使用该文章的账户。 */
internal fun ArticleListReaderViewModel.performUpdateStarredStatus(article: Article, isStarred: Boolean) {
    val targetArticle = article.copy()
    applicationScope.launch(ioDispatcher) {
        rssService.markAsStarred(targetArticle, isStarred)
    }
}

/** 按当前列表快照和日期范围更新已读差异。 */
internal fun ArticleListReaderViewModel.performMarkAsReadFromListByDate(date: Date, isBefore: Boolean) {
    viewModelScope.launch(ioDispatcher) {
        val items =
            articleListUseCase.itemSnapshotList
                .filterIsInstance<ArticleFlowItem.Article>()
                .map { it.articleWithFeed }
                .filter {
                    if (isBefore) {
                        date > it.article.date && it.article.isUnread
                    } else {
                        date < it.article.date && it.article.isUnread
                    }
                }
                .distinctBy { it.article.id }

        diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)
    }
}

/** 根据用户偏好标记已读并切换下一来源或分组。 */
internal fun ArticleListReaderViewModel.performLoadNextFeedOrGroup() {
    viewModelScope.launch {
        if (
            settingsProvider.settings.pullToSwitchFeed ==
                PullToLoadNextFeedPreference.MarkAsReadAndLoadNextFeed
        ) {
            markAllAsRead()
        }
        flowUiState.value?.nextFilterState?.let { filterStateUseCase.updateFilterState(it) }
    }
}

/** 将当前可见列表中的文章统一标为已读。 */
internal fun ArticleListReaderViewModel.performMarkAllAsRead() {
    viewModelScope.launch {
        val items =
            articleListUseCase.itemSnapshotList.items
                .filterIsInstance<ArticleFlowItem.Article>()
                .map { it.articleWithFeed }

        diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)
    }
}

/** 提交列表差异并按当前过滤范围安排刷新。 */
internal fun ArticleListReaderViewModel.performSync() {
    diffMapHolder.commitDiffsToDb()
    viewModelScope.launch {
        _isSyncingFlow.value = true
        val isSyncing = syncWorkerStatusFlow.value
        if (!isSyncing) {
            delay(1000L)
            if (syncWorkerStatusFlow.value == false) {
                _isSyncingFlow.value = false
            }
        }
    }
    applicationScope.launch(ioDispatcher) {
        val filterState = filterStateUseCase.filterStateFlow.value
        val service = rssService.get()
        when (service) {
            is LocalRssService ->
                service.doSyncOneTime(
                    feedId = filterState.feed?.id,
                    groupId = filterState.group?.id,
                )

            is GoogleReaderRssService ->
                service.doSyncOneTime(
                    feedId = filterState.feed?.id,
                    groupId = filterState.group?.id,
                )

            else -> service.doSyncOneTime()
        }
    }
}

/** 清除来源、分组和搜索条件。 */
internal fun ArticleListReaderViewModel.performResetFilter() =
    filterStateUseCase.updateFilterState(feed = null, group = null, searchContent = null)

/** 切换列表过滤范围，保留现有筛选语义。 */
internal fun ArticleListReaderViewModel.performChangeFilter(filterState: FilterState) {
    filterStateUseCase.updateFilterState(
        filterState.feed,
        filterState.group,
        filterState.filter,
    )
}

/** 搜索内容改变时更新过滤状态。 */
internal fun ArticleListReaderViewModel.performInputSearchContent(content: String? = null) {
    if (content != filterStateUseCase.filterStateFlow.value.searchContent)
        filterStateUseCase.updateFilterState(searchContent = content)
}

/** 更新已打开文章的已读差异，无文章时保持当前错误展示。 */
internal fun ArticleListReaderViewModel.performUpdateReadStatus(isUnread: Boolean) {
    val item = readingUiState.value.articleWithFeed ?: return
    diffMapHolder.updateDiff(item, isUnread = isUnread)
    _readingUiState.update { it.copy(isUnread = diffMapHolder.checkIfUnread(item)) }
}

/** 收藏状态先捕获目标文章，后台写入使用该文章的账户。 */
internal fun ArticleListReaderViewModel.performUpdateStarredStatus(isStarred: Boolean) {
    val article = currentArticle?.copy() ?: return
    _readingUiState.update { it.copy(isStarred = isStarred) }
    applicationScope.launch(ioDispatcher) {
        rssService.markAsStarred(article, isStarred)
    }
}

/** 阅读页图片下载复用既有下载服务和结果回调。 */
internal fun ArticleListReaderViewModel.performDownloadImage(
    url: String,
    onSuccess: (Uri) -> Unit = {},
    onFailure: (Throwable) -> Unit = {},
) {
    viewModelScope.launch {
        executeDownloadImage({ imageDownloader.downloadImage(url) }, onSuccess, onFailure)
    }
}

internal suspend fun executeDownloadImage(
    download: suspend () -> Result<Uri>,
    onSuccess: (Uri) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    val result = download()
    val error = result.exceptionOrNull()
    if (error is CancellationException) throw error
    result.onSuccess(onSuccess).onFailure(onFailure)
}
