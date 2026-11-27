package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import kotlin.collections.chunked
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.security.GoogleReaderSecurityKey
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.net.onFailure
import me.ash.reader.infrastructure.net.onSuccess
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.ofCategoryIdToStreamId
import me.ash.reader.infrastructure.rss.provider.greader.GoogleReaderAPI.Companion.ofCategoryStreamIdToId
import me.ash.reader.ui.ext.dollarLast
import me.ash.reader.ui.ext.spacerDollar

private const val TAG = "GoogleReaderRssService"

internal fun resolveGoogleReaderRemoteCategoryId(
    groupId: String,
    defaultGroupId: String,
): String? =
    groupId
        .takeUnless { it == defaultGroupId }
        ?.dollarLast()
        ?.ofCategoryStreamIdToId()

class GoogleReaderRssService
@Inject
constructor(
    @ApplicationContext internal val context: Context,
    internal val articleDao: ArticleDao,
    internal val feedDao: FeedDao,
    internal val rssHelper: RssHelper,
    internal val notificationHelper: NotificationHelper,
    internal val groupDao: GroupDao,
    @IODispatcher internal val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val workManager: WorkManager,
    internal val accountService: AccountService,
    internal val syncLogger: SyncLogger,
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

    override val importSubscription: Boolean = false
    override val addSubscription: Boolean = true
    override val moveSubscription: Boolean = true
    override val deleteSubscription: Boolean = true
    override val updateSubscription: Boolean = true

    internal suspend fun getGoogleReaderAPI(account: Account? = null) =
        GoogleReaderSecurityKey((account ?: accountService.getCurrentAccount()).securityKey).run {
            GoogleReaderAPI.getInstance(
                context = context,
                serverUrl = serverUrl!!,
                username = username!!,
                password = password!!,
                httpUsername = null,
                httpPassword = null,
                clientCertificateAlias = clientCertificateAlias,
                syncLogger = syncLogger,
            )
        }

    override suspend fun validCredentials(account: Account): Boolean {
        return getGoogleReaderAPI(account).validCredentials().also { success ->
            if (success)
                try {
                    getGoogleReaderAPI(account).getUserInfo().userName?.let {
                        accountService.update(account.copy(name = it))
                    }
                } catch (ignore: Exception) {
                    Log.e("RLog", "get user info is failed: ", ignore)
                }
        }
    }

    override suspend fun clearAuthorization() {
        GoogleReaderAPI.clearInstance()
    }

    /** 来源提交固定账户，部分远端成功的重试由幂等入口处理。 */
    override suspend fun subscribe(request: SubscriptionRequest): SubscriptionResult =
        saveGoogleReaderSubscription(request)

    /** 现有调用入口在进入时固定账户，实际保存仍校验分组归属。 */
    override suspend fun subscribe(
        feedLink: String, searchedFeed: SyndFeed, groupId: String,
        isNotification: Boolean, isFullContent: Boolean, isBrowser: Boolean,
    ) {
        subscribe(SubscriptionRequest(
            accountId = accountService.getCurrentAccountId(), groupId = groupId,
            feedLink = feedLink, searchedFeed = searchedFeed,
            notification = isNotification, fullContent = isFullContent, browser = isBrowser,
        ))
    }


    /** 远端分类编辑和本地分组使用同一账户及其凭据。 */
    override suspend fun addGroup(destFeed: Feed?, newGroupName: String, accountId: Int): String {
        val account = requireNotNull(accountService.getAccountById(accountId)) { "分组账户不存在" }
        require(destFeed == null || destFeed.accountId == accountId) { "来源不属于分组账户" }
        getGoogleReaderAPI(account)
            .subscriptionEdit(
                destFeedId = destFeed?.id?.dollarLast(),
                destCategoryId = newGroupName,
            )
        val id = accountId.spacerDollar(newGroupName.ofCategoryIdToStreamId())
        groupDao.insert(Group(id = id, name = newGroupName, accountId = accountId))
        return id
    }

    override suspend fun renameGroup(group: Group) {
        getGoogleReaderAPI()
            .renameTag(categoryId = group.id.dollarLast(), renameToName = group.name)
        // TODO: Whether to switch the old ID to the new ID?
        super.renameGroup(group)
    }

    override suspend fun moveFeed(originGroupId: String, feed: Feed) {
        val defaultGroupId = accountService.getDefaultGroup().id
        getGoogleReaderAPI()
            .subscriptionEdit(
                destFeedId = feed.id.dollarLast(),
                destCategoryId =
                    resolveGoogleReaderRemoteCategoryId(
                        groupId = feed.groupId,
                        defaultGroupId = defaultGroupId,
                    ),
                originCategoryId =
                    resolveGoogleReaderRemoteCategoryId(
                        groupId = originGroupId,
                        defaultGroupId = defaultGroupId,
                    ),
            )
        super.moveFeed(originGroupId, feed)
    }

    override suspend fun changeFeedUrl(feed: Feed) {
        throw Exception("Unsupported")
    }

    override suspend fun renameFeed(feed: Feed) {
        getGoogleReaderAPI()
            .subscriptionEdit(destFeedId = feed.id.dollarLast(), destFeedName = feed.name)
        // TODO: Whether to switch the old ID to the new ID?
        super.renameFeed(feed)
    }

    override suspend fun deleteGroup(group: Group, onlyDeleteNoStarred: Boolean?) {
        feedDao.queryByGroupId(accountService.getCurrentAccountId(), group.id).forEach {
            deleteFeed(it)
        }
        getGoogleReaderAPI().disableTag(group.id.dollarLast())
        super.deleteGroup(group, false)
    }

    override suspend fun deleteFeed(feed: Feed, onlyDeleteNoStarred: Boolean?) {
        getGoogleReaderAPI()
            .subscriptionEdit(action = "unsubscribe", destFeedId = feed.id.dollarLast())
        super.deleteFeed(feed, false)
    }

    /** 按任务账户分派刷新，远端 API、文章状态和清理共享同一身份。 */
    override suspend fun sync(accountId: Int, feedId: String?, groupId: String?): ListenableWorker.Result =
        if (feedId != null) syncGoogleReaderFeed(accountId, feedId) else syncGoogleReaderAccount(accountId)

    /** 保留内容批量获取的公开契约，内部使用不可变上下文传递固定账户。 */
    fun fetchItemsContentsDeferred(
        itemIds: Set<String>, googleReaderAPI: GoogleReaderAPI, accountId: Int,
        unreadIds: Set<String>, starredIds: Set<String>, scope: CoroutineScope,
    ): List<Deferred<List<Article>>> = fetchGoogleReaderContents(
        GoogleReaderContentRequest(itemIds, googleReaderAPI, accountId, unreadIds, starredIds), scope,
    )

    /** 阅读操作在发起时捕获账户，网络返回后不重新读取界面当前账户。 */
    override suspend fun markAsRead(
        groupId: String?, feedId: String?, articleId: String?, before: Date?, isUnread: Boolean,
    ) = markGoogleReaderSelection(
        ReadSelection(accountService.getCurrentAccountId(), groupId, feedId, articleId, before, isUnread)
    )

    override suspend fun syncReadStatus(
        articleIds: Set<String>,
        isUnread: Boolean,
        account: Account?,
    ): Set<String> {
        val googleReaderAPI = getGoogleReaderAPI(account)
        val syncedEntries = mutableSetOf<String>()
        articleIds
            .takeIf { it.isNotEmpty() }
            ?.chunked(500)
            ?.forEachIndexed { index, idList ->
                Log.d(
                    "RLog",
                    "sync markAsRead:  ${(index * 500) + idList.size}/${articleIds.size} num",
                )
                googleReaderAPI
                    .editTag(
                        itemIds = idList.map { it.dollarLast() },
                        mark = if (!isUnread) GoogleReaderAPI.Stream.Read.tag else null,
                        unmark = if (isUnread) GoogleReaderAPI.Stream.Read.tag else null,
                    )
                    .onFailure { it.printStackTrace() }
                    .onSuccess {
                        syncedEntries += idList
                        println("synced $idList to isUnread: $isUnread")
                    }
            }
        return syncedEntries
    }

    /** 本地收藏与远端标签使用同一账户，远端请求不得重新读取当前界面凭据。 */
    override suspend fun markAsStarred(account: Account, articleId: String, isStarred: Boolean) {
        super.markAsStarred(account, articleId, isStarred)
        getGoogleReaderAPI(account)
            .editTag(
                itemIds = listOf(articleId.dollarLast()),
                mark = if (isStarred) GoogleReaderAPI.Stream.Starred.tag else null,
                unmark = if (!isStarred) GoogleReaderAPI.Stream.Starred.tag else null,
            ).getOrThrow()
    }
}
