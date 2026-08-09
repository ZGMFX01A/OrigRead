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
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.filter.ArticleFilterEngine
import me.ash.reader.infrastructure.filter.ArticleFilterMatch
import me.ash.reader.infrastructure.rss.RssHelper
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
        val accountId = accountService.getCurrentAccountId()
        val feedId = accountId.spacerDollar(UUID.randomUUID().toString())
        feedDao.insert(
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
        )
        return feedId
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
        val accountId = accountService.getCurrentAccountId()
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
            searchedFeed.entries.map { entry ->
                rssHelper.buildArticleFromSyndEntry(feed, accountId, entry)
            }
        feedDao.insert(feed)
        articleDao.insertList(articles.map { article -> article.copy(feedId = feedId) })
        rssHubSubscriptionRepository.record(feedId, sourcePageUrl)
        return feedId
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
        val accountId = accountService.getCurrentAccountId()
        feedDao.insert(
            Feed(
                id = accountId.spacerDollar(UUID.randomUUID().toString()),
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
        )
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
                            val archivedArticles =
                                feedDao
                                    .queryArchivedArticles(currentFeed.id)
                                    .map { it.link }
                                    .toSet()
                            val fetchedFeed = localSourceService.fetch(currentFeed, preDate)
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
                                articleDao.insertListIfNotExist(
                                    articles = fetchedArticles,
                                    feed = currentFeed,
                                )
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

}
