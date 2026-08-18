package me.ash.reader.domain.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.LocalSubscriptionDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.filter.ArticleFilterEngine
import me.ash.reader.infrastructure.filter.ArticleFilterMatch
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.RssHttpCache
import me.ash.reader.infrastructure.rss.RssHttpCacheDao
import me.ash.reader.infrastructure.rsshub.RssHubSubscriptionRepository
import me.ash.reader.infrastructure.website.WebsiteHelper
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.spacerDollar
import timber.log.Timber

private const val TAG = "LocalRssService"

class LocalRssService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val rssHelper: RssHelper,
    private val localSourceService: LocalSourceService,
    private val websiteHelper: WebsiteHelper,
    private val articleFilterEngine: ArticleFilterEngine,
    private val notificationHelper: NotificationHelper,
    private val groupDao: GroupDao,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val workManager: WorkManager,
    private val accountService: AccountService,
    private val syncLogger: SyncLogger,
    private val rssHubSubscriptionRepository: RssHubSubscriptionRepository,
    private val localSubscriptionDao: LocalSubscriptionDao,
    private val rssHttpCacheDao: RssHttpCacheDao,
) :
    AbstractRssRepository(
        articleDao,
        groupDao,
        feedDao,
        workManager,
        rssHelper,
        notificationHelper,
        ioDispatcher,
        defaultDispatcher,
        accountService,
    ) {

    override suspend fun subscribe(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ) =
        subscribeWithHttpValidators(
            feedLink = feedLink,
            searchedFeed = searchedFeed,
            groupId = groupId,
            isNotification = isNotification,
            isFullContent = isFullContent,
            isBrowser = isBrowser,
            etag = null,
            lastModified = null,
        )

    suspend fun subscribeWithHttpValidators(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
        etag: String?,
        lastModified: String?,
    ) {
        withSubscriptionLock { accountId ->
            if (findExistingFeed(accountId, feedLink) != null) return@withSubscriptionLock

            val feed =
                Feed(
                    id = accountId.spacerDollar(UUID.randomUUID().toString()),
                    name = searchedFeed.title.decodeHTML() ?: searchedFeed.title,
                    url = feedLink,
                    groupId = groupId,
                    accountId = accountId,
                    icon = searchedFeed.icon?.link,
                    isBrowser = isBrowser,
                    isNotification = isNotification,
                    isFullContent = isFullContent,
                    sourceType = SourceType.RSS,
                )
            val articles =
                rssHelper.buildArticlesFromSyndEntries(
                    feed = feed,
                    accountId = accountId,
                    entries = searchedFeed.entries,
                )
            insertFeedAndArticles(feed, articles)
            if (!etag.isNullOrBlank() || !lastModified.isNullOrBlank()) {
                rssHttpCacheDao.upsert(
                    RssHttpCache(
                        feedId = feed.id,
                        feedUrl = feed.url,
                        etag = etag,
                        lastModified = lastModified,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    /**
     * 保存普通网站来源，网站内容会在后续同步阶段交给独立解析器处理。
     */
    suspend fun subscribeWebsite(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ): String {
        return withSubscriptionLock { accountId ->
            findExistingFeed(accountId, feedLink)?.let { return@withSubscriptionLock it.id }

            val feedId = accountId.spacerDollar(UUID.randomUUID().toString())
            val feed =
                Feed(
                    id = feedId,
                    name = searchedFeed.title,
                    url = feedLink,
                    groupId = groupId,
                    accountId = accountId,
                    icon = searchedFeed.icon?.url,
                    isBrowser = isBrowser,
                    isNotification = isNotification,
                    isFullContent = isFullContent,
                    sourceType = SourceType.WEBSITE,
                )
            val articles =
                rssHelper.buildArticlesFromSyndEntries(
                    feed = feed,
                    accountId = accountId,
                    entries = searchedFeed.entries,
                )
            val filterMatches = mutableListOf<ArticleFilterMatch>()
            val filteredArticles =
                articles.filterNot { article ->
                    articleFilterEngine.match(article)?.also(filterMatches::add) != null
                }
            articleFilterEngine.recordMatches(filterMatches)

            // 网站探测阶段已经成功拿到了这一批文章。首次订阅直接复用，不能先写空 Feed
            // 再立刻二次请求；二次请求被 418/429/动态验证拦截时会造成“预览有文章、添加后为空”。
            insertFeedAndArticles(
                feed = feed,
                articles = filteredArticles.map { article -> article.copy(feedId = feedId) },
            )
            feedId
        }
    }

    /** 保存 RSSHub 候选及其原始页面 URL，供后续路由或实例失效时重新探测。 */
    suspend fun subscribeRssHub(
        feedLink: String,
        sourcePageUrl: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ): String {
        return withSubscriptionLock { accountId ->
            findExistingFeed(accountId, feedLink)?.let { return@withSubscriptionLock it.id }

            val feedId = accountId.spacerDollar(UUID.randomUUID().toString())
            val feed =
                Feed(
                    id = feedId,
                    name = searchedFeed.title.decodeHTML() ?: searchedFeed.title,
                    url = feedLink,
                    groupId = groupId,
                    accountId = accountId,
                    icon = searchedFeed.icon?.url,
                    isBrowser = isBrowser,
                    isNotification = isNotification,
                    isFullContent = isFullContent,
                    sourceType = SourceType.RSS,
                )
            val articles =
                rssHelper.buildArticlesFromSyndEntries(
                    feed = feed,
                    accountId = accountId,
                    entries = searchedFeed.entries,
                )
            insertFeedAndArticles(
                feed = feed,
                articles = articles.map { article -> article.copy(feedId = feedId) },
            )
            rssHubSubscriptionRepository.record(feedId, sourcePageUrl)
            feedId
        }
    }

    override suspend fun deleteFeed(feed: Feed, onlyDeleteNoStarred: Boolean?) {
        super.deleteFeed(feed, onlyDeleteNoStarred)
        if (feedDao.queryById(feed.id) == null) {
            rssHubSubscriptionRepository.remove(feed.id)
        }
    }

    /** 保存 JSON/API 来源，接口地址作为后续同步地址。 */
    suspend fun subscribeJson(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ) {
        withSubscriptionLock { accountId ->
            if (findExistingFeed(accountId, feedLink) != null) return@withSubscriptionLock

            val feedId = accountId.spacerDollar(UUID.randomUUID().toString())
            val feed =
                Feed(
                    id = feedId,
                    name = searchedFeed.title,
                    url = feedLink,
                    groupId = groupId,
                    accountId = accountId,
                    icon = searchedFeed.icon?.url,
                    isBrowser = isBrowser,
                    isNotification = isNotification,
                    isFullContent = isFullContent,
                    sourceType = SourceType.JSON,
                )
            val articles =
                rssHelper.buildArticlesFromSyndEntries(
                    feed = feed,
                    accountId = accountId,
                    entries = searchedFeed.entries,
                )
            val filterMatches = mutableListOf<ArticleFilterMatch>()
            val filteredArticles =
                articles.filterNot { article ->
                    articleFilterEngine.match(article)?.also(filterMatches::add) != null
                }
            articleFilterEngine.recordMatches(filterMatches)

            // JSON 探测阶段已经成功请求并解析过一次；首次订阅直接复用这批数据。
            // 不再先保存空 Feed 再立即发第二次网络请求，避免“预览有文章、订阅后空来源”。
            insertFeedAndArticles(
                feed = feed,
                articles = filteredArticles.map { article -> article.copy(feedId = feedId) },
            )
        }
    }

    private suspend fun insertFeedAndArticles(
        feed: Feed,
        articles: List<me.ash.reader.domain.model.article.Article>,
    ) {
        localSubscriptionDao.insertFeedWithArticles(feed, articles)
    }

    override suspend fun sync(
        accountId: Int,
        feedId: String?,
        groupId: String?
    ): ListenableWorker.Result = supervisorScope {
        return@supervisorScope runCatching {
            val preTime = System.currentTimeMillis()
            val preDate = Date(preTime)
            val currentAccount = accountService.getAccountById(accountId)!!
            require(currentAccount.type.id == AccountType.Local.id) {
                "Account type is invalid"
            }
            val semaphore = Semaphore(16)

            val feedsToSync =
                when {
                    feedId != null -> listOfNotNull(feedDao.queryById(feedId))
                    groupId != null -> feedDao.queryByGroupId(accountId, groupId)
                    else -> feedDao.queryAll(accountId)
                }

            feedsToSync
                .mapIndexed { _, currentFeed ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val syncFetch = localSourceService.fetchForSync(currentFeed, preDate)
                            if (syncFetch.notModified) return@withPermit
                            val fetchedFeed = syncFetch.feedWithArticle

                            val archivedArticles =
                                feedDao
                                    .queryArchivedArticles(currentFeed.id)
                                    .map { it.link }
                                    .toSet()
                            if (currentFeed.sourceType == SourceType.WEBSITE) {
                                val existingArticles =
                                    articleDao.queryAllByFeedId(accountId, currentFeed.id)
                                val obsoleteArticleIds =
                                    websiteHelper.findObsoleteArticleIds(
                                        feed = currentFeed,
                                        existingArticles = existingArticles,
                                        fetchedArticles = fetchedFeed.articles,
                                    )
                                if (obsoleteArticleIds.isNotEmpty()) {
                                    articleDao.deleteByIds(obsoleteArticleIds)
                                }
                            }
                            val filterMatches = mutableListOf<ArticleFilterMatch>()
                            val fetchedArticles =
                                fetchedFeed.articles.filterNot {
                                    archivedArticles.contains(it.link)
                                }.filterNot { article ->
                                    articleFilterEngine.match(article)?.also(filterMatches::add) != null
                                }
                            articleFilterEngine.recordMatches(filterMatches)

                            val newArticles =
                                if (currentFeed.sourceType == SourceType.JSON) {
                                    updateJsonArticlesAndInsertNew(
                                        feed = currentFeed,
                                        fetchedArticles = fetchedArticles,
                                    )
                                } else {
                                    articleDao.insertListIfNotExist(
                                        articles = fetchedArticles,
                                        feed = currentFeed,
                                    )
                                }
                            if (currentFeed.isNotification && newArticles.isNotEmpty()) {
                                notificationHelper.notify(
                                    fetchedFeed.copy(articles = newArticles, feed = currentFeed)
                                )
                            }
                        }
                    }
                }
                .awaitAll()

            Timber.tag("RlOG").i("onCompletion: ${System.currentTimeMillis() - preTime}")
            accountService.update(currentAccount.copy(updateAt = Date()))
            ListenableWorker.Result.success()
        }
            .onFailure { syncLogger.log(it) }
            .getOrNull() ?: ListenableWorker.Result.retry()
    }

    /**
     * JSON/API 的服务端内容可能在同一文章 URL 下补充或修正正文。
     * 刷新时更新远端内容字段，但保留本地阅读状态和首次入库时间，避免：
     * 1. WordPress 从 excerpt 修正为 content 后旧文章永远无法升级；
     * 2. 刷新正文把已读/收藏/稍后读重置；
     * 3. 仅因内容刷新修改 updateAt，干扰 keepArchived 的保留周期。
     */
    private suspend fun updateJsonArticlesAndInsertNew(
        feed: Feed,
        fetchedArticles: List<me.ash.reader.domain.model.article.Article>,
    ): List<me.ash.reader.domain.model.article.Article> {
        if (fetchedArticles.isEmpty()) return emptyList()

        val existingByLink =
            articleDao.queryArticlesByLinksChunked(
                linkList = fetchedArticles.map { it.link },
                feedId = feed.id,
                accountId = feed.accountId,
            ).associateBy { it.link }

        val newArticles = mutableListOf<me.ash.reader.domain.model.article.Article>()
        val updatedArticles = mutableListOf<me.ash.reader.domain.model.article.Article>()
        fetchedArticles.forEach { fetched ->
            val existing = existingByLink[fetched.link]
            if (existing == null) {
                newArticles += fetched
            } else {
                updatedArticles += mergeJsonArticleRefresh(existing, fetched)
            }
        }

        if (updatedArticles.isNotEmpty()) {
            articleDao.update(*updatedArticles.toTypedArray())
        }
        if (newArticles.isNotEmpty()) {
            articleDao.insertList(newArticles)
        }
        return newArticles
    }

}

internal fun mergeJsonArticleRefresh(
    existing: me.ash.reader.domain.model.article.Article,
    fetched: me.ash.reader.domain.model.article.Article,
): me.ash.reader.domain.model.article.Article =
    existing.copy(
        date = fetched.date,
        title = fetched.title,
        author = fetched.author,
        rawDescription = fetched.rawDescription,
        shortDescription = fetched.shortDescription,
        img = fetched.img ?: existing.img,
        link = fetched.link,
        feedId = existing.feedId,
        accountId = existing.accountId,
        // id / isUnread / isStarred / isReadLater / updateAt / fullContent 均保留 existing。
    )
