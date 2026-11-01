package me.ash.reader.ui.page.adaptive

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Date
import javax.inject.Inject
import kotlin.collections.any
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.ArticlePagingListUseCase
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.data.GroupWithFeedsListUseCase
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.MarkAsReadConditions
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.service.AccountService
import me.ash.reader.ui.page.home.flow.ArticleListTranslationController
import me.ash.reader.ui.page.home.flow.ListTranslationPersistence
import me.ash.reader.infrastructure.translation.TranslationDisplayMode
import me.ash.reader.domain.service.SyncWorker
import me.ash.reader.infrastructure.android.AndroidImageDownloader
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.AiSummaryLength
import me.ash.reader.infrastructure.ai.AiSummaryService
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.content.ContentExtractionService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.rss.ReaderContentPreferences
import me.ash.reader.infrastructure.translation.TranslationProviderType
import me.ash.reader.infrastructure.translation.TranslationService
import me.ash.reader.infrastructure.translation.TranslationSettingsRepository
import me.ash.reader.infrastructure.translation.TranslationTarget
import me.ash.reader.llm.settings.LlmSettingsRepository

@OptIn(FlowPreview::class)
@HiltViewModel()
class ArticleListReaderViewModel
@Inject
constructor(
    internal val rssService: RssService,
    internal val accountService: AccountService,
    @IODispatcher internal val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope internal val applicationScope: CoroutineScope,
    val diffMapHolder: DiffMapHolder,
    internal val filterStateUseCase: FilterStateUseCase,
    internal val groupWithFeedsListUseCase: GroupWithFeedsListUseCase,
    internal val settingsProvider: SettingsProvider,
    internal val readerCacheHelper: ReaderCacheHelper,
    internal val readerContentPreferences: ReaderContentPreferences,
    internal val articleWebSessionManager: ArticleWebSessionManager,
    internal val contentExtractionService: ContentExtractionService,
    internal val translationService: TranslationService,
    internal val translationSettingsRepository: TranslationSettingsRepository,
    internal val aiSummaryService: AiSummaryService,
    internal val aiSettingsRepository: AiSettingsRepository,
    internal val llmSettingsRepository: LlmSettingsRepository,
    val textToSpeechManager: TextToSpeechManager,
    internal val imageDownloader: AndroidImageDownloader,
    internal val articleListUseCase: ArticlePagingListUseCase,
    workManager: WorkManager,
) : ViewModel() {

    /** 列表快照和分组变化共同计算下一阅读范围。 */
    val flowUiState: StateFlow<FlowUiState?> = articleListUseCase.pagerFlow
        .combine(groupWithFeedsListUseCase.groupWithFeedListFlow) { pager, groups -> buildFlowUiState(pager, groups) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal val syncWorkerStatusFlow =
        workManager
            .getWorkInfosByTagFlow(SyncWorker.SYNC_TAG)
            .map { it.any { workInfo -> workInfo.state == WorkInfo.State.RUNNING } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    internal val _isSyncingFlow = MutableStateFlow(false)
    val isSyncingFlow = _isSyncingFlow.asStateFlow()

    /** 与正文翻译分离的列表展示状态，复用同一服务及渠道配置。 */
    private val listTranslationPersistence = ListTranslationPersistence(translationService)
    val translationCacheInvalidations = translationService.cacheInvalidations
    val listTranslation = ArticleListTranslationController(
        coroutineScope = viewModelScope,
        translate = translationService::translateListTexts,
        readCached = listTranslationPersistence::read,
        saveCached = listTranslationPersistence::save,
        selectCached = listTranslationPersistence::select,
        isScopeCurrent = { key, accountId ->
            key == articleListUseCase.pagerFlow.value &&
                articleListUseCase.pagerFlow.value.filterState == filterStateUseCase.filterStateFlow.value &&
                accountId == accountService.getCurrentAccountId()
        },
    )

    init {
        viewModelScope.launch {
            articleListUseCase.pagerFlow.combine(accountService.currentAccountIdFlow) { pager, account -> pager to account }
                .collect { (pager, _) -> listTranslation.reset(pager) }
        }
        viewModelScope.launch {
            translationSettingsRepository.settings
                .map { it.copy(displayMode = TranslationDisplayMode.TRANSLATED) }
                .distinctUntilChanged()
                .combine(aiSettingsRepository.settings) { translation, ai -> translation to ai }
                .drop(1).collect { listTranslation.reset(articleListUseCase.pagerFlow.value) }
        }
        viewModelScope.launch {
            syncWorkerStatusFlow.debounce(500L).collect { _isSyncingFlow.value = it }
        }
    }

    fun parseVerifiedPage(html: String, sourceUrl: String) = performParseVerifiedPage(html, sourceUrl)

    fun setDefaultTranslationTarget(target: TranslationTarget) = performSetDefaultTranslationTarget(target)

    fun updateReadStatus(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        conditions: MarkAsReadConditions,
        isUnread: Boolean,
    ) = performUpdateReadStatus(ReaderReadStatusRequest(groupId, feedId, articleId, conditions, isUnread))

    fun updateStarredStatus(article: Article, isStarred: Boolean) = performUpdateStarredStatus(article, isStarred)

    fun markAsReadFromListByDate(date: Date, isBefore: Boolean) = performMarkAsReadFromListByDate(date, isBefore)

    fun loadNextFeedOrGroup() = performLoadNextFeedOrGroup()

    fun markAllAsRead() = performMarkAllAsRead()

    fun sync() = performSync()

    fun resetFilter() = performResetFilter()

    fun changeFilter(filterState: FilterState) = performChangeFilter(filterState)

    fun inputSearchContent(content: String? = null) = performInputSearchContent(content)

    internal val _readingUiState = MutableStateFlow(ReadingUiState())
    val readingUiState: StateFlow<ReadingUiState> = _readingUiState.asStateFlow()

    internal val _readerState: MutableStateFlow<ReaderState> = MutableStateFlow(ReaderState())
    val readerStateStateFlow = _readerState.asStateFlow()

    internal val _translationUiState = MutableStateFlow(ReaderTranslationUiState())
    val translationUiState = _translationUiState.asStateFlow()
    val translationSettings = translationSettingsRepository.settings
    internal var translationJob: Job? = null
    internal var translationRestoreJob: Job? = null
    internal var translationExpiryJob: Job? = null
    internal var translationRequestSerial: Long = 0L

    internal val _aiSummaryUiState = MutableStateFlow(ReaderAiSummaryUiState())
    val aiSummaryUiState = _aiSummaryUiState.asStateFlow()
    val aiSettings = aiSettingsRepository.settings
    val llmSettings = llmSettingsRepository.settings
    internal var aiSummaryJob: Job? = null
    internal var aiSummaryProgressJob: Job? = null
    internal var aiSummaryRequestSerial: Long = 0L

    internal val currentArticle: Article?
        get() = readingUiState.value.articleWithFeed?.article

    internal val currentFeed: Feed?
        get() = readingUiState.value.articleWithFeed?.feed

    /** 导航、切换正文模式和重复请求都使旧代次失效。 */
    internal var readerRequestSerial = 0L
    internal var initializationJob: Job? = null
    internal var fullContentJob: Job? = null

    init { observeStoredReaderTranslation() }

    fun initData(articleId: String, listIndex: Int? = null) = performInitData(articleId, listIndex)

    fun clearReadingData() = performClearReadingData()

    fun renderDescriptionContent() = performRenderDescriptionContent()

    fun renderFullContent() = performRenderFullContent()

    fun setFullContentEnabled(enabled: Boolean) = performSetFullContentEnabled(enabled)

    fun updateReadStatus(isUnread: Boolean) = performUpdateReadStatus(isUnread)

    fun updateStarredStatus(isStarred: Boolean) = performUpdateStarredStatus(isStarred)

    fun translateOrToggle() = performTranslateOrToggle()

    fun stopTranslation() = performStopTranslation()

    fun showOriginalContentForCitation() = performShowOriginalContentForCitation()

    fun resolveDefaultTranslationTarget(): TranslationTarget = performResolveDefaultTranslationTarget()

    fun isTranslationProviderConfigured(type: TranslationProviderType): Boolean = performIsTranslationProviderConfigured(type)

    /** 使用指定服务翻译当前正文。 */
    fun translateWithTarget(target: TranslationTarget) = performTranslateWithTarget(target)

    fun translateWithProvider(type: TranslationProviderType) = performTranslateWithProvider(type)

    fun clearTranslationError() = performClearTranslationError()

    fun summarizeArticle(
        forceRefresh: Boolean = false,
        providerId: String? = null,
        modelOverride: String? = null,
        lengthOverride: AiSummaryLength? = null,
    ) = performSummarizeArticle(ReaderSummaryOptions(forceRefresh, providerId, modelOverride, lengthOverride))

    fun stopAiSummary() = performStopAiSummary()

    fun dismissAiSummary() = performDismissAiSummary()

    fun clearAiSummaryError() = performClearAiSummaryError()

    fun downloadImage(
        url: String,
        onSuccess: (Uri) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) = performDownloadImage(url, onSuccess, onFailure)
}
