package me.ash.reader.ui.page.adaptive

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.rss.ReaderContentPolicy
import me.ash.reader.infrastructure.content.FullContentFailureReason
import timber.log.Timber

/** 导航首先按稳定 ID 定位，旧下标仅在身份一致时用于加速查找。 */
internal fun ArticleListReaderViewModel.performInitData(articleId: String, listIndex: Int? = null) {
    cancelReaderRequests()
    resetTranslation()
    resetAiSummary()
    _readingUiState.value = ReadingUiState()
    _readerState.value = ReaderState(articleId = articleId)
    val serial = readerRequestSerial
    initializationJob = viewModelScope.launch {
        val item = resolveRequestedArticle(articleId, listIndex)
        if (serial != readerRequestSerial) return@launch
        if (item == null) {
            _readerState.update { it.copy(content = ReaderState.Error(FullContentFailureReason.ARTICLE_UNAVAILABLE)) }
            return@launch
        }
        val fullContentEnabled = item.feed.sourceType == SourceType.RSS &&
            readerContentPreferences.isFullContentEnabled(item.feed.accountId, item.feed.id)
        if (serial != readerRequestSerial) return@launch
        if (diffMapHolder.checkIfUnread(item)) {
            diffMapHolder.updateDiff(item, isUnread = false)
        }
        publishRequestedArticle(item, fullContentEnabled)
        renderInitialContent(ReaderRequestIdentity(articleId, item.article.accountId, serial), item)
    }
}

/** 离开阅读页后清空展示状态，并使全部未完成请求失效。 */
internal fun ArticleListReaderViewModel.performClearReadingData() {
    cancelReaderRequests()
    resetTranslation()
    resetAiSummary()
    _readingUiState.update { ReadingUiState() }
    _readerState.update { ReaderState() }
}

/** 上下篇导航使用当前快照中的稳定身份，不替代待打开文章的身份校验。 */
internal fun ArticleListReaderViewModel.prefetchState(state: ReaderState): ReaderState {
    val items = articleListUseCase.itemSnapshotList
    val currentId = currentArticle?.id
    val index =
        items.indexOfFirst { item ->
            item is ArticleFlowItem.Article && item.articleWithFeed.article.id == currentId
        }
    var previousArticle: ReaderState.PrefetchResult? = null
    var nextArticle: ReaderState.PrefetchResult? = null

    if (index != -1) {
        val prevIterator = items.listIterator(index)
        while (prevIterator.hasPrevious()) {
            val previousIndex = prevIterator.previousIndex()
            val prev = prevIterator.previous()
            if (prev is ArticleFlowItem.Article) {
                previousArticle =
                    ReaderState.PrefetchResult(
                        articleId = prev.articleWithFeed.article.id,
                        index = previousIndex,
                    )
                break
            }
        }
        val nextIterator = items.listIterator(index + 1)
        while (nextIterator.hasNext()) {
            val nextIndex = nextIterator.nextIndex()
            val next = nextIterator.next()
            if (
                next is ArticleFlowItem.Article && next.articleWithFeed.article.id != currentId
            ) {
                nextArticle =
                    ReaderState.PrefetchResult(
                        articleId = next.articleWithFeed.article.id,
                        index = nextIndex,
                    )
                break
            }
        }
    }

    Timber.d("$previousArticle, $nextArticle, ${state.listIndex}")
    return state.copy(nextArticle = nextArticle, previousArticle = previousArticle, listIndex = index)
}

/** 下标过期或分页尚未加载时按请求 ID 查询；不存在时明确交回空结果。 */
internal suspend fun ArticleListReaderViewModel.resolveRequestedArticle(articleId: String, listIndex: Int?): ArticleWithFeed? {
    val snapshot = articleListUseCase.itemSnapshotList
    val indexed = listIndex?.let { snapshot.getOrNull(it) as? ArticleFlowItem.Article }
    if (indexed?.articleWithFeed?.article?.id == articleId) return indexed.articleWithFeed
    val matching = snapshot.find { it is ArticleFlowItem.Article && it.articleWithFeed.article.id == articleId }
        as? ArticleFlowItem.Article
    return matching?.articleWithFeed ?: rssService.get().findArticleById(articleId)
}

/** 标题和文章对象一次发布，正文加载不在 StateFlow 的可重试更新函数内执行。 */
internal fun ArticleListReaderViewModel.publishRequestedArticle(item: ArticleWithFeed, fullContentEnabled: Boolean = false) {
    _readingUiState.value = ReadingUiState(item, isStarred = item.article.isStarred)
    val state = ReaderState(articleId = item.article.id, feedName = item.feed.name, title = item.article.title,
        author = item.article.author, link = item.article.link, publishedDate = item.article.date,
        isFullContentEnabled = fullContentEnabled)
    _readerState.value = prefetchState(state)
}

/** 初始内容继续沿用来源阅读策略；缓存与网络由同一受控全文请求处理。 */
internal fun ArticleListReaderViewModel.renderInitialContent(identity: ReaderRequestIdentity, item: ArticleWithFeed) {
    if (!isCurrentRequest(identity)) return
    if (item.feed.sourceType == SourceType.RSS) {
        if (_readerState.value.isFullContentEnabled) renderFullContent()
        else _readerState.update { it.copy(content = ReaderState.Description(item.article.rawDescription)) }
        return
    }
    val embedded = ReaderContentPolicy.embeddedFullContent(item.article, item.feed)
    if (embedded != null) {
        _readerState.update { it.copy(content = ReaderState.FullContent(embedded)) }
        return
    }
    if (ReaderContentPolicy.requiresFetchedFullContent(item.feed)) {
        renderFullContent()
        return
    }
    _readerState.update { it.copy(content = ReaderState.Description(item.article.rawDescription)) }
}
