package me.ash.reader.ui.page.adaptive

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.rss.ReaderContentPolicy
import me.ash.reader.infrastructure.rss.ReaderFullContentResult
import me.ash.reader.infrastructure.content.FullContentFailureClassifier
import timber.log.Timber

/** 仅用户点击会记录来源偏好；重试、缓存恢复和正文解析均不改按钮选择。 */
internal fun ArticleListReaderViewModel.performSetFullContentEnabled(enabled: Boolean) {
    val feed = currentFeed?.takeIf { it.sourceType == SourceType.RSS } ?: return
    _readerState.update { it.copy(isFullContentEnabled = enabled) }
    // 在本次点击内进入存储队列，后续点击及新阅读页的读取按相同顺序执行。
    applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            readerContentPreferences.setFullContentEnabled(feed.accountId, feed.id, enabled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Could not save source full-content selection")
        }
    }
    if (enabled) renderFullContent() else renderDescriptionContent()
}

/** 沿用既有全文 loading 延迟，避免快速缓存命中闪烁。 */
private const val FULL_CONTENT_LOADING_DELAY_MILLIS = 100L

/** 使用用户在可见 WebView 中完成验证后的当前 DOM 再次提取正文。 */
internal fun ArticleListReaderViewModel.performParseVerifiedPage(html: String, sourceUrl: String) {
    val article = currentArticle ?: return
    val identity = beginFullContentRequest(article)
    resetTranslation()
    resetAiSummary()
    setLoading()
    fullContentJob = viewModelScope.launch {
        try {
            val extracted = withContext(ioDispatcher) {
                contentExtractionService.extract(html = html, sourceUrl = sourceUrl, expectedTitle = article.title)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrentRequest(identity)) return@launch
            if (extracted == null || extracted.html.isBlank()) {
                _readerState.update { it.copy(content = ReaderState.Error(FullContentFailureClassifier.classifyHtml(html))) }
                return@launch
            }
            val cachedContent = readerCacheHelper.cacheFullContent(extracted.html, article)
            articleWebSessionManager.flushCookies()
            publishFullContentResult(identity, Result.success(cachedContent))
        } catch (error: CancellationException) {
            // 验证页面也受正文请求代次管理，取消后不能显示失败或覆盖新文章。
            throw error
        } catch (error: Exception) {
            // 提取或验证会话失败仍明确报错；缓存写入失败随有效正文单独发布。
            publishFullContentResult(identity, Result.failure(error))
        }
    }
}

/** 切回摘要同时取消全文，防止旧成功结果再次把页面切回全文。 */
internal fun ArticleListReaderViewModel.performRenderDescriptionContent() {
    invalidateFullContentRequest()
    resetTranslation()
    resetAiSummary()
    _readerState.update {
        it.copy(
            content = ReaderState.Description(content = currentArticle?.rawDescription ?: "")
        )
    }
}

/** 全文优先使用来源自带正文，否则启动绑定文章、账户和代次的任务。 */
internal fun ArticleListReaderViewModel.performRenderFullContent() {
    val article = currentArticle ?: return
    val identity = beginFullContentRequest(article)
    resetTranslation()
    resetAiSummary()
    val feed = currentFeed
    if (feed != null && ReaderContentPolicy.embeddedFullContent(article, feed) != null) {
        // 用户手动切换到“全文”时同样优先使用 RSS 已携带的公众号全文，
        // 不再因为按钮操作重新访问 mp.weixin.qq.com。
        _readerState.update {
            it.copy(content = ReaderState.FullContent(article.rawDescription))
        }
        return
    }
    fullContentJob = viewModelScope.launch { fetchRequestedContent(identity, article) }
}

/** 当前请求进入加载状态，调用者负责在发布前核对身份。 */
internal fun ArticleListReaderViewModel.setLoading() {
    _readerState.update { it.copy(content = ReaderState.Loading) }
}

/** loading 与网络任务属于同一协程树，退出或换文会一并取消。 */
internal suspend fun ArticleListReaderViewModel.fetchRequestedContent(identity: ReaderRequestIdentity, article: Article) =
    coroutineScope {
        val loading = launch {
            delay(FULL_CONTENT_LOADING_DELAY_MILLIS)
            if (isCurrentRequest(identity)) setLoading()
        }
        try {
            val result = readerCacheHelper.readOrFetchFullContentForReading(article)
            currentCoroutineContext().ensureActive()
            publishFullContentResult(identity, result)
        } finally {
            loading.cancel()
            if (readerRequestSerial == identity.serial) fullContentJob = null
        }
    }

/** 所有状态发布先核对身份，旧结果不能改正文、错误或 AI 展示状态。 */
private fun ArticleListReaderViewModel.publishFullContentResult(
    identity: ReaderRequestIdentity,
    result: Result<ReaderFullContentResult>,
) {
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    if (!isCurrentRequest(identity)) return
    result.fold(onSuccess = { content ->
        resetTranslation()
        resetAiSummary()
        // 离线写入失败仍展示本次正文，提示状态与正文一起受请求身份校验保护。
        _readerState.update {
            it.copy(content = ReaderState.FullContent(content.content, cacheWriteFailed = content.cacheWriteFailure != null))
        }
    }, onFailure = { error ->
        _readerState.update { it.copy(content = ReaderState.Error(FullContentFailureClassifier.classifyThrowable(error))) }
    })
}
