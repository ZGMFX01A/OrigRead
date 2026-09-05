package me.ash.reader.ui.page.adaptive

import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.util.fastFirstOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import javax.inject.Inject
import kotlin.collections.any
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.ArticlePagingListUseCase
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.data.PagerData
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.service.GoogleReaderRssService
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.android.AndroidImageDownloader
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.AiException
import me.ash.reader.infrastructure.ai.AiPerfTracer
import me.ash.reader.infrastructure.ai.AiSummaryDocument
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.infrastructure.ai.AiSummaryProgressStage
import me.ash.reader.infrastructure.ai.AiSummaryService
import me.ash.reader.infrastructure.ai.AiSummaryStreamUpdate
import me.ash.reader.infrastructure.ai.resolvedDefaultModel
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.content.ContentExtractionService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.PullToLoadNextFeedPreference
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.rss.ReaderContentPolicy
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.content.FullContentFailureClassifier
import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.infrastructure.translation.TranslationDocument
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationService
import me.ash.reader.infrastructure.translation.TranslationSettingsRepository
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.llm.settings.LlmSettingsRepository
import timber.log.Timber

private const val TAG = "FlowViewModel"

@OptIn(FlowPreview::class)
@HiltViewModel()
class ArticleListReaderViewModel
@Inject
constructor(
    private val rssService: RssService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
    val diffMapHolder: DiffMapHolder,
    private val filterStateUseCase: FilterStateUseCase,
    private val groupWithFeedsListUseCase: GroupWithFeedsListUseCase,
    private val settingsProvider: SettingsProvider,
    private val readerCacheHelper: ReaderCacheHelper,
    private val articleWebSessionManager: ArticleWebSessionManager,
    private val contentExtractionService: ContentExtractionService,
    private val translationService: TranslationService,
    private val translationSettingsRepository: TranslationSettingsRepository,
    private val aiSummaryService: AiSummaryService,
    private val aiSettingsRepository: AiSettingsRepository,
    private val llmSettingsRepository: LlmSettingsRepository,
    val textToSpeechManager: TextToSpeechManager,
    private val imageDownloader: AndroidImageDownloader,
    private val articleListUseCase: ArticlePagingListUseCase,
    workManager: WorkManager,
) : ViewModel() {

    val flowUiState: StateFlow<FlowUiState?> =
        articleListUseCase.pagerFlow
            .combine(groupWithFeedsListUseCase.groupWithFeedListFlow) {
                pagerData,
                groupWithFeedsList ->
                val filterState = pagerData.filterState
                var nextFilterState: FilterState? = null
                if (filterState.group != null) {
                    val groupList = groupWithFeedsList.map { it.group }
                    val index = groupList.indexOfFirst { it.id == filterState.group.id }
                    if (index != -1) {
                        val nextGroup = groupList.getOrNull(index + 1)
                        if (nextGroup != null) {
                            nextFilterState = filterState.copy(group = nextGroup)
                        }
                    } else {
                        val allGroupList =
                            rssService.get().queryAllGroupWithFeeds().map { it.group }
                        val index = allGroupList.indexOfFirst { it.id == filterState.group.id }
                        if (index != -1) {
                            val nextGroup =
                                allGroupList.subList(index, allGroupList.size).fastFirstOrNull {
                                    groupList.map { it.id }.contains(it.id)
                                }
                            if (nextGroup != null) {
                                nextFilterState = filterState.copy(group = nextGroup)
                            }
                        }
                    }
                } else if (filterState.feed != null) {
                    val feedList = groupWithFeedsList.flatMap { it.feeds }
                    val index = feedList.indexOfFirst { it.id == filterState.feed.id }
                    if (index != -1) {
                        val nextFeed = feedList.getOrNull(index + 1)
                        if (nextFeed != null) {
                            nextFilterState = filterState.copy(feed = nextFeed)
                        }
                    } else {
                        val allFeedList =
                            rssService.get().queryAllGroupWithFeeds().flatMap { it.feeds }
                        val index = allFeedList.indexOfFirst { it.id == filterState.feed.id }
                        if (index != -1) {
                            val nextFeed =
                                allFeedList.subList(index, allFeedList.size).fastFirstOrNull {
                                    feedList.map { it.id }.contains(it.id)
                                }
                            if (nextFeed != null) {
                                nextFilterState = filterState.copy(feed = nextFeed)
                            }
                        }
                    }
                }
                FlowUiState(nextFilterState = nextFilterState, pagerData = pagerData)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val syncWorkerStatusFlow =
        workManager
            .getWorkInfosByTagFlow(SyncWorker.SYNC_TAG)
            .map { it.any { workInfo -> workInfo.state == WorkInfo.State.RUNNING } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isSyncingFlow = MutableStateFlow(false)
    val isSyncingFlow = _isSyncingFlow.asStateFlow()

    init {
        viewModelScope.launch {
            syncWorkerStatusFlow.debounce(500L).collect { _isSyncingFlow.value = it }
        }
    }

    /** 使用用户在可见 WebView 中完成验证后的当前 DOM 再次提取正文。 */
    fun parseVerifiedPage(html: String, sourceUrl: String) {
        val article = currentArticle ?: return
        resetTranslation()
        resetAiSummary()
        setLoading()
        viewModelScope.launch(ioDispatcher) {
            val extracted = runCatching {
                contentExtractionService.extract(
                    html = html,
                    sourceUrl = sourceUrl,
                    expectedTitle = article.title,
                )
            }.getOrNull()

            if (extracted == null || extracted.html.isBlank()) {
                if (currentArticle?.id == article.id) {
                    val failureReason = FullContentFailureClassifier.classifyHtml(html)
                    _readerState.update {
                        it.copy(content = ReaderState.Error(failureReason))
                    }
                }
                return@launch
            }

            readerCacheHelper.writeContentToCache(extracted.html, article.id)
            articleWebSessionManager.flushCookies()
            if (currentArticle?.id == article.id) {
                _readerState.update {
                    it.copy(content = ReaderState.FullContent(extracted.html))
                }
            }
        }
    }

    /** 阅读页长按翻译选择器可以把传统服务或某个 AI 模型设为后续单击翻译的默认方式。 */
    fun setDefaultTranslationTarget(target: TranslationTarget) {
        translationSettingsRepository.setDefaultTarget(target)
    }

    fun updateReadStatus(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        conditions: MarkAsReadConditions,
        isUnread: Boolean,
    ) {
        applicationScope.launch(ioDispatcher) {
            rssService
                .get()
                .markAsRead(
                    groupId = groupId,
                    feedId = feedId,
                    articleId = articleId,
                    before = conditions.toDate(),
                    isUnread = isUnread,
                )
        }
    }

    fun updateStarredStatus(articleId: String?, isStarred: Boolean) {
        applicationScope.launch(ioDispatcher) {
            if (articleId != null) {
                rssService.get().markAsStarred(articleId = articleId, isStarred = isStarred)
            }
        }
    }

    fun markAsReadFromListByDate(date: Date, isBefore: Boolean) {
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

    fun loadNextFeedOrGroup() {
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

    fun markAllAsRead() {
        viewModelScope.launch {
            val items =
                articleListUseCase.itemSnapshotList.items
                    .filterIsInstance<ArticleFlowItem.Article>()
                    .map { it.articleWithFeed }

            diffMapHolder.updateDiff(articleWithFeed = items.toTypedArray(), isUnread = false)
        }
    }

    fun sync() {
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

    fun resetFilter() =
        filterStateUseCase.updateFilterState(feed = null, group = null, searchContent = null)

    fun changeFilter(filterState: FilterState) {
        filterStateUseCase.updateFilterState(
            filterState.feed,
            filterState.group,
            filterState.filter,
        )
    }

    fun inputSearchContent(content: String? = null) {
        if (content != filterStateUseCase.filterStateFlow.value.searchContent)
            filterStateUseCase.updateFilterState(searchContent = content)
    }

    private val _readingUiState = MutableStateFlow(ReadingUiState())
    val readingUiState: StateFlow<ReadingUiState> = _readingUiState.asStateFlow()

    private val _readerState: MutableStateFlow<ReaderState> = MutableStateFlow(ReaderState())
    val readerStateStateFlow = _readerState.asStateFlow()

    private val _translationUiState = MutableStateFlow(ReaderTranslationUiState())
    val translationUiState = _translationUiState.asStateFlow()
    val translationSettings = translationSettingsRepository.settings
    private var translationJob: Job? = null
    private var translationRequestSerial: Long = 0L

    private val _aiSummaryUiState = MutableStateFlow(ReaderAiSummaryUiState())
    val aiSummaryUiState = _aiSummaryUiState.asStateFlow()
    val aiSettings = aiSettingsRepository.settings
    val llmSettings = llmSettingsRepository.settings
    private var aiSummaryJob: Job? = null
    private var aiSummaryProgressJob: Job? = null
    private var aiSummaryRequestSerial: Long = 0L

    private val currentArticle: Article?
        get() = readingUiState.value.articleWithFeed?.article

    private val currentFeed: Feed?
        get() = readingUiState.value.articleWithFeed?.feed

    fun initData(articleId: String, listIndex: Int? = null) {
        resetTranslation()
        resetAiSummary()
        viewModelScope.launch {
            val snapshotList = articleListUseCase.itemSnapshotList

            val itemByIndex =
                listIndex?.let { snapshotList.getOrNull(it) as? ArticleFlowItem.Article }

            val itemFromList =
                if (itemByIndex != null && itemByIndex.articleWithFeed.article.id != articleId) {
                    itemByIndex
                } else {
                    snapshotList.find { item ->
                        item is ArticleFlowItem.Article &&
                            item.articleWithFeed.article.id == articleId
                    } as? ArticleFlowItem.Article
                }

            val item =
                itemByIndex?.articleWithFeed
                    ?: (itemFromList?.articleWithFeed
                        ?: rssService.get().findArticleById(articleId)!!)

            if (diffMapHolder.checkIfUnread(item)) {
                diffMapHolder.updateDiff(item, isUnread = false)
            }
            item.run {
                _readingUiState.update {
                    it.copy(articleWithFeed = this, isStarred = article.isStarred, isUnread = false)
                }
                _readerState.update {
                    it.copy(
                            articleId = article.id,
                            feedName = feed.name,
                            title = article.title,
                            author = article.author,
                            link = article.link,
                            publishedDate = article.date,
                        )
                        .prefetchArticleId()
                        .renderContent(this)
                }
            }
        }
    }

    fun clearReadingData() {
        resetTranslation()
        resetAiSummary()
        _readingUiState.update { ReadingUiState() }
        _readerState.update { ReaderState() }
    }

    suspend fun ReaderState.renderContent(articleWithFeed: ArticleWithFeed): ReaderState {
        val embeddedFullContent =
            ReaderContentPolicy.embeddedFullContent(articleWithFeed.article, articleWithFeed.feed)
        val contentState =
            // WEBSITE 来源的列表只保存标题和链接，必须始终读取网页正文，不能切回空摘要。
            if (embeddedFullContent != null) {
                // Wechat2RSS 等来源已经在 content:encoded 中给出完整公众号正文。
                // 直接进入 FullContent，避免无意义访问微信原网页并触发安全验证。
                ReaderState.FullContent(embeddedFullContent)
            } else if (ReaderContentPolicy.requiresFetchedFullContent(articleWithFeed.feed)) {
                val fullContent =
                    readerCacheHelper.readFullContent(articleWithFeed.article.id).getOrNull()
                if (fullContent != null) ReaderState.FullContent(fullContent)
                else {
                    renderFullContent()
                    ReaderState.Loading
                }
            } else ReaderState.Description(articleWithFeed.article.rawDescription)

        return copy(content = contentState)
    }

    fun renderDescriptionContent() {
        resetTranslation()
        resetAiSummary()
        _readerState.update {
            it.copy(
                content = ReaderState.Description(content = currentArticle?.rawDescription ?: "")
            )
        }
    }

    fun renderFullContent() {
        resetTranslation()
        resetAiSummary()
        val article = currentArticle ?: return
        val feed = currentFeed
        if (feed != null && ReaderContentPolicy.embeddedFullContent(article, feed) != null) {
            // 用户手动切换到“全文”时同样优先使用 RSS 已携带的公众号全文，
            // 不再因为按钮操作重新访问 mp.weixin.qq.com。
            _readerState.update {
                it.copy(content = ReaderState.FullContent(article.rawDescription))
            }
            return
        }
        val fetchJob =
            viewModelScope.launch {
                readerCacheHelper
                    .readOrFetchFullContent(article)
                    .onSuccess { content ->
                        resetTranslation()
                        resetAiSummary()
                        _readerState.update {
                            it.copy(content = ReaderState.FullContent(content = content))
                        }
                    }
                    .onFailure { th ->
                        _readerState.update {
                            it.copy(
                                content = ReaderState.Error(
                                    FullContentFailureClassifier.classifyThrowable(th)
                                )
                            )
                        }
                    }
            }
        viewModelScope.launch {
            delay(100L)
            if (fetchJob.isActive) {
                setLoading()
            }
        }
    }

    fun updateReadStatus(isUnread: Boolean) {
        readingUiState.value.articleWithFeed?.let {
            diffMapHolder.updateDiff(it, isUnread = isUnread)
        }
        _readingUiState.update {
            it.copy(isUnread = diffMapHolder.checkIfUnread(it.articleWithFeed!!))
        }
    }

    fun updateStarredStatus(isStarred: Boolean) {
        applicationScope.launch(ioDispatcher) {
            _readingUiState.update { it.copy(isStarred = isStarred) }
            currentArticle?.let {
                rssService.get().markAsStarred(articleId = it.id, isStarred = isStarred)
            }
        }
    }

    private fun setLoading() {
        _readerState.update { it.copy(content = ReaderState.Loading) }
    }

    /** 首次点击开始翻译；翻译中再次点击立即取消；已有译文时只在原文和译文之间切换。 */
    fun translateOrToggle() {
        val state = _translationUiState.value
        if (state.isLoading) {
            stopTranslation()
            return
        }
        if (state.document != null) {
            _translationUiState.update { it.copy(showTranslation = !it.showTranslation) }
            return
        }
        translateWithTarget(resolveDefaultTranslationTarget())
    }

    fun stopTranslation() {
        if (!_translationUiState.value.isLoading) return
        translationRequestSerial++
        translationJob?.cancel()
        translationJob = null
        _translationUiState.update { it.copy(isLoading = false, errorMessage = null) }
    }

    /**
     * Citation 永远核验原文。
     *
     * 如果翻译仍在生成，必须先失效并取消当前请求；否则旧请求可能在 Citation 已经定位成功后
     * 再次把 showTranslation 写回 true，把用户从原文证据位置切回译文。
     * 这里只改变当前阅读瞬时状态，不修改默认翻译目标或持久设置。
     */
    fun showOriginalContentForCitation() {
        if (_translationUiState.value.isLoading) {
            stopTranslation()
        }
        _translationUiState.update { state ->
            if (state.showTranslation) state.copy(showTranslation = false) else state
        }
    }

    /**
     * 默认翻译目标允许指向 AI 模型。若对应 AI 服务已被删除、停用或关闭 AI 阅读，
     * 则安全回退到传统默认 Provider，避免单击翻译落到失效配置。
     */
    fun resolveDefaultTranslationTarget(): TranslationTarget {
        val translationSettings = translationSettingsRepository.current()
        return when (val requested = translationSettings.defaultTarget) {
            is TranslationTarget.Traditional -> requested
            is TranslationTarget.Ai -> {
                val aiSettings = aiSettingsRepository.current()
                val provider = aiSettings.providers.firstOrNull { it.id == requested.providerId }
                if (
                    aiSettings.enabled &&
                        provider?.enabled == true &&
                        provider.endpoint.isNotBlank() &&
                        requested.model.isNotBlank()
                ) {
                    requested.copy(providerName = provider.name)
                } else {
                    TranslationTarget.Traditional(translationSettings.defaultProvider)
                }
            }
        }
    }

    /** 阅读页只展示真正具备运行条件的传统翻译服务。 */
    fun isTranslationProviderConfigured(type: TranslationProviderType): Boolean =
        translationSettingsRepository.current().provider(type).enabled &&
            translationSettingsRepository.isConfigured(type)

    /** 统一翻译目标入口；传统 Provider 与 AI Provider/Model 共用同一执行状态和缓存流程。 */
    fun translateWithTarget(target: TranslationTarget) {
        val state = _translationUiState.value
        val targetLanguage =
            translationSettingsRepository.current().targetLanguage.trim().ifBlank {
                me.ash.reader.infrastructure.translation.TranslationSettings.defaultTargetLanguage()
            }
        if (state.document?.target == target && state.document.targetLanguage == targetLanguage) {
            _translationUiState.update { it.copy(showTranslation = true) }
            return
        }
        val articleId = _readerState.value.articleId ?: return
        val content = _readerState.value.content.text.orEmpty()
        if (content.isBlank()) return
        val title = _readerState.value.title.orEmpty()
        val previousState = _translationUiState.value
        translationJob?.cancel()
        val requestSerial = ++translationRequestSerial
        // Publish loading before the coroutine starts so an immediate second tap is guaranteed to
        // observe the in-flight state and cancel this request instead of starting another one.
        _translationUiState.value = previousState.copy(isLoading = true, errorMessage = null)
        translationJob =
            viewModelScope.launch {
                runCatching {
                        translationService.translateArticle(
                            articleId = articleId,
                            title = title,
                            content = content,
                            target = target,
                            targetLanguageOverride = targetLanguage,
                        )
                    }
                    .onSuccess { document ->
                        // 网络调用结束时用户可能已经切换文章、目标或语言；旧结果不得覆盖更新的请求。
                        if (
                            _readerState.value.articleId == articleId &&
                                translationRequestSerial == requestSerial &&
                                document.target == target &&
                                document.targetLanguage == targetLanguage
                        ) {
                            _translationUiState.value =
                                ReaderTranslationUiState(
                                    document = document,
                                    showTranslation = true,
                                )
                        }
                    }
                    .onFailure { error ->
                        if (error is CancellationException) return@onFailure
                        if (
                            _readerState.value.articleId == articleId &&
                                translationRequestSerial == requestSerial
                        ) {
                            _translationUiState.value =
                                previousState.copy(
                                    isLoading = false,
                                    errorMessage = error.message ?: "翻译失败",
                                )
                        }
                    }
            }
    }

    /** 长按菜单选择传统 Provider 后立即翻译；保留该方法供旧调用点复用。 */
    fun translateWithProvider(type: TranslationProviderType) {
        translateWithTarget(TranslationTarget.Traditional(type))
    }

    /** 非流式接口没有可靠百分比，因此只显示真实可观察的阶段和已等待时间。 */
    private fun startAiSummaryProgressTicker(articleId: String) {
        val startedAt = SystemClock.elapsedRealtime()
        aiSummaryProgressJob =
            viewModelScope.launch {
                while (true) {
                    delay(1000L)
                    if (_readerState.value.articleId != articleId) return@launch
                    val elapsed =
                        ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt().coerceAtLeast(0)
                    _aiSummaryUiState.update { state ->
                        if (state.isLoading) state.copy(elapsedSeconds = elapsed) else state
                    }
                }
            }
    }

    fun clearTranslationError() {
        _translationUiState.update { it.copy(errorMessage = null) }
    }

    private fun resetTranslation() {
        translationRequestSerial++
        translationJob?.cancel()
        translationJob = null
        _translationUiState.value = ReaderTranslationUiState()
    }

    /**
     * 生成或重新打开当前文章的 AI 摘要。
     * providerId/modelOverride/lengthOverride 仅作用于本次请求，不会改变设置页默认项。
     */
    fun summarizeArticle(
        forceRefresh: Boolean = false,
        providerId: String? = null,
        modelOverride: String? = null,
        lengthOverride: AiSummaryLength? = null,
    ) {
        val currentState = _aiSummaryUiState.value
        if (
            currentState.document != null &&
                !forceRefresh &&
                providerId == null &&
                modelOverride == null &&
                lengthOverride == null
        ) {
            _aiSummaryUiState.update { it.copy(showPanel = true, errorMessage = null) }
            return
        }
        if (currentState.isLoading) return
        val articleId = _readerState.value.articleId ?: return
        val content = _readerState.value.content.text.orEmpty()
        if (content.isBlank()) return
        val title = _readerState.value.title.orEmpty()
        val settings = aiSettingsRepository.current()
        val selectedProvider =
            providerId?.let { id -> settings.providers.firstOrNull { it.id == id } }
                ?: settings.defaultProvider()
        if (selectedProvider == null) {
            _aiSummaryUiState.update { it.copy(errorMessage = "没有可用的 AI 服务") }
            return
        }
        val selectedModel = modelOverride?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectedProvider.resolvedDefaultModel().orEmpty()
        val perfTrace = AiPerfTracer.start("summary")
        AiPerfTracer.mark(
            perfTrace,
            "ui_request_ready",
            "articleChars" to content.length,
            "providerId" to selectedProvider.id,
            "model" to selectedModel,
        )
        aiSummaryJob?.cancel()
        aiSummaryProgressJob?.cancel()
        val requestSerial = ++aiSummaryRequestSerial
        aiSummaryJob =
            viewModelScope.launch {
                var firstStreamingFeedbackVisible = false
                AiPerfTracer.mark(perfTrace, "summary_job_started")
                _aiSummaryUiState.update {
                    it.copy(
                        isLoading = true,
                        showPanel = true,
                        errorMessage = null,
                        progressStage = AiSummaryProgressStage.PREPARING,
                        elapsedSeconds = 0,
                        activeProviderId = selectedProvider.id,
                        activeProviderName = selectedProvider.name,
                        activeModel = selectedModel,
                        streamingSummaryPreview = "",
                        streamingReasoningPreview = "",
                    )
                }
                startAiSummaryProgressTicker(articleId)
                try {
                    val document =
                        aiSummaryService.summarizeArticle(
                            articleId = articleId,
                            title = title,
                            content = content,
                            forceRefresh = forceRefresh,
                            providerId = selectedProvider.id,
                            modelOverride = selectedModel,
                            lengthOverride = lengthOverride,
                            onProgress = { stage ->
                                if (
                                    requestSerial == aiSummaryRequestSerial &&
                                        _readerState.value.articleId == articleId
                                ) {
                                    _aiSummaryUiState.update { it.copy(progressStage = stage) }
                                }
                            },
                            onStreamUpdate = { update: AiSummaryStreamUpdate ->
                                if (
                                    requestSerial == aiSummaryRequestSerial &&
                                        _readerState.value.articleId == articleId
                                ) {
                                    _aiSummaryUiState.update {
                                        it.copy(
                                            streamingSummaryPreview = update.summaryPreview,
                                            streamingReasoningPreview = update.reasoningPreview,
                                        )
                                    }
                                    if (!firstStreamingFeedbackVisible && update.hasVisibleContent) {
                                        firstStreamingFeedbackVisible = true
                                        AiPerfTracer.mark(
                                            perfTrace,
                                            "ui_first_stream_visible",
                                            "hasReasoning" to update.reasoningPreview.isNotBlank(),
                                            "hasContent" to update.summaryPreview.isNotBlank(),
                                        )
                                    }
                                }
                            },
                            perfTrace = perfTrace,
                        )
                    // 文章切换、停止任务或新请求启动后，旧结果不得覆盖当前页面。
                    if (
                        requestSerial == aiSummaryRequestSerial &&
                            _readerState.value.articleId == articleId
                    ) {
                        _aiSummaryUiState.update {
                            it.copy(
                                document = document,
                                activeProviderId = document.providerId,
                                activeProviderName = document.providerName,
                                activeModel = document.model,
                                errorMessage = null,
                                streamingSummaryPreview = "",
                                streamingReasoningPreview = "",
                            )
                        }
                        AiPerfTracer.mark(
                            perfTrace,
                            "ui_result_visible",
                            "summaryChars" to document.summary.length,
                        )
                    }
                } catch (error: CancellationException) {
                    // 用户停止、文章切换或 ViewModel 清理都属于正常取消，不提示失败。
                } catch (error: Throwable) {
                    Timber.e(error, "AI summary generation failed")
                    if (
                        requestSerial == aiSummaryRequestSerial &&
                            _readerState.value.articleId == articleId
                    ) {
                        _aiSummaryUiState.update {
                            it.copy(
                                errorMessage =
                                    if (error is AiException) {
                                        error.message?.takeIf(String::isNotBlank) ?: "AI 摘要生成失败，请重试"
                                    } else {
                                        // 内部类名、初始化错误等实现细节只写日志，不直接暴露到阅读页。
                                        "AI 摘要生成失败，请重试"
                                    },
                            )
                        }
                    }
                } finally {
                    if (requestSerial == aiSummaryRequestSerial) {
                        aiSummaryProgressJob?.cancel()
                        aiSummaryProgressJob = null
                        if (_readerState.value.articleId == articleId) {
                            _aiSummaryUiState.update {
                                it.copy(
                                    isLoading = false,
                                    progressStage = null,
                                    streamingSummaryPreview = "",
                                    streamingReasoningPreview = "",
                                )
                            }
                        }
                        aiSummaryJob = null
                    }
                }
            }
    }

    /** 立即终止当前摘要任务，并让阅读页恢复到可再次生成的状态。 */
    fun stopAiSummary() {
        aiSummaryRequestSerial++
        aiSummaryJob?.cancel()
        aiSummaryJob = null
        aiSummaryProgressJob?.cancel()
        aiSummaryProgressJob = null
        _aiSummaryUiState.update { state ->
            state.copy(
                isLoading = false,
                showPanel = state.document != null && state.showPanel,
                progressStage = null,
                elapsedSeconds = 0,
                errorMessage = null,
                streamingSummaryPreview = "",
                streamingReasoningPreview = "",
            )
        }
    }

    fun dismissAiSummary() {
        _aiSummaryUiState.update { it.copy(showPanel = false) }
    }

    fun clearAiSummaryError() {
        _aiSummaryUiState.update { it.copy(errorMessage = null) }
    }

    private fun resetAiSummary() {
        aiSummaryRequestSerial++
        aiSummaryJob?.cancel()
        aiSummaryJob = null
        aiSummaryProgressJob?.cancel()
        aiSummaryProgressJob = null
        _aiSummaryUiState.value = ReaderAiSummaryUiState()
    }

    fun ReaderState.prefetchArticleId(): ReaderState {
        val items = articleListUseCase.itemSnapshotList
        val currentId = currentArticle?.id
        val index =
            items.indexOfFirst { item ->
                item is ArticleFlowItem.Article && item.articleWithFeed.article.id == currentId
            }
        var previousArticle: ReaderState.PrefetchResult? = null
        var nextArticle: ReaderState.PrefetchResult? = null

        if (index != -1 || currentId == null) {
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

        Timber.d("$previousArticle, $nextArticle, $listIndex")
        return copy(nextArticle = nextArticle, previousArticle = previousArticle, listIndex = index)
    }

    fun downloadImage(
        url: String,
        onSuccess: (Uri) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) {
        viewModelScope.launch {
            imageDownloader.downloadImage(url).onSuccess(onSuccess).onFailure(onFailure)
        }
    }
}

data class FlowUiState(val pagerData: PagerData, val nextFilterState: FilterState? = null)

data class ReadingUiState(
    val articleWithFeed: ArticleWithFeed? = null,
    val isUnread: Boolean = false,
    val isStarred: Boolean = false,
)

data class ReaderAiSummaryUiState(
    val isLoading: Boolean = false,
    val document: AiSummaryDocument? = null,
    val showPanel: Boolean = false,
    val errorMessage: String? = null,
    val progressStage: AiSummaryProgressStage? = null,
    val elapsedSeconds: Int = 0,
    val activeProviderId: String? = null,
    val activeProviderName: String? = null,
    val activeModel: String? = null,
    /** 仅生成期间展示；最终完成后由 document.summary 替换。 */
    val streamingSummaryPreview: String = "",
    /** 仅展示模型 API 明确返回的 reasoning_content 预览，不持久化 UI 中间状态。 */
    val streamingReasoningPreview: String = "",
)

data class ReaderTranslationUiState(
    val isLoading: Boolean = false,
    val document: TranslationDocument? = null,
    val showTranslation: Boolean = false,
    val errorMessage: String? = null,
)

data class ReaderState(
    val articleId: String? = null,
    val feedName: String = "",
    val title: String? = null,
    val author: String? = null,
    val link: String? = null,
    val publishedDate: Date = Date(0L),
    val content: ContentState = Loading,
    val listIndex: Int? = null,
    val nextArticle: PrefetchResult? = null,
    val previousArticle: PrefetchResult? = null,
) {
    data class PrefetchResult(val articleId: String, val index: Int)

    sealed interface ContentState {
        val text: String?
            get() {
                return when (this) {
                    is Description -> content
                    is Error -> null
                    is FullContent -> content
                    Loading -> null
                }
            }
    }

    data class FullContent(val content: String) : ContentState

    data class Description(val content: String) : ContentState

    data class Error(val reason: FullContentFailureReason) : ContentState

    data object Loading : ContentState
}
