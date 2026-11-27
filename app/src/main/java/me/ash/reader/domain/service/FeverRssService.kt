package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import androidx.annotation.CheckResult
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.rometools.rome.feed.synd.SyndFeed
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.security.FeverSecurityKey
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.exception.FeverAPIException
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.provider.fever.FeverAPI
import me.ash.reader.infrastructure.rss.provider.fever.FeverDTO
import me.ash.reader.ui.ext.dollarLast
import me.ash.reader.ui.ext.spacerDollar

class FeverRssService
@Inject
constructor(
    @ApplicationContext internal val context: Context,
    internal val articleDao: ArticleDao,
    internal val feedDao: FeedDao,
    internal val rssHelper: RssHelper,
    internal val notificationHelper: NotificationHelper,
    internal val groupDao: GroupDao,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    workManager: WorkManager,
    internal val accountService: AccountService,
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

    companion object {
        internal fun resolveFeedGroupId(
            accountId: Int,
            remoteFeedId: String,
            feedsGroupsMap: Map<String, String>,
            defaultGroupId: String,
        ): String =
            feedsGroupsMap[remoteFeedId]?.let { accountId.spacerDollar(it) } ?: defaultGroupId
    }

    override val importSubscription: Boolean = false
    override val addSubscription: Boolean = false
    override val moveSubscription: Boolean = false
    override val deleteSubscription: Boolean = false
    override val updateSubscription: Boolean = false

    internal suspend fun getFeverAPI(account: Account? = null) =
        FeverSecurityKey((account ?: accountService.getCurrentAccount()).securityKey).run {
            FeverAPI.getInstance(
                context = context,
                serverUrl = serverUrl!!,
                username = username!!,
                password = password!!,
                httpUsername = null,
                httpPassword = null,
                clientCertificateAlias = clientCertificateAlias,
            )
        }

    override suspend fun validCredentials(account: Account): Boolean =
        getFeverAPI(account).validCredentials() > 0

    override suspend fun clearAuthorization() {
        FeverAPI.clearInstance()
    }

    override suspend fun subscribe(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ) {
        throw FeverAPIException("Unsupported")
    }

    override suspend fun addGroup(destFeed: Feed?, newGroupName: String, accountId: Int): String {
        throw FeverAPIException("Unsupported")
    }

    override suspend fun renameGroup(group: Group) {
        throw FeverAPIException("Unsupported")
    }

    override suspend fun renameFeed(feed: Feed) {
        throw FeverAPIException("Unsupported")
    }

    override suspend fun deleteGroup(group: Group, onlyDeleteNoStarred: Boolean?) {
        throw FeverAPIException("Unsupported")
    }

    override suspend fun deleteFeed(feed: Feed, onlyDeleteNoStarred: Boolean?) {
        throw FeverAPIException("Unsupported")
    }

    override suspend fun moveFeed(originGroupId: String, feed: Feed) {
        throw FeverAPIException("Unsupported")
    }

    override suspend fun changeFeedUrl(feed: Feed) {
        throw FeverAPIException("Unsupported")
    }

    /** 后台刷新固定账户，远端分组、状态和孤儿清理不读取界面当前账户。 */
    override suspend fun sync(accountId: Int, feedId: String?, groupId: String?): ListenableWorker.Result =
        syncFeverAccount(accountId)

    override suspend fun markAsRead(
        groupId: String?,
        feedId: String?,
        articleId: String?,
        before: Date?,
        isUnread: Boolean,
    ) {
        super.markAsRead(groupId, feedId, articleId, before, isUnread)
        val feverAPI = getFeverAPI()
        val beforeUnixTimestamp = (before?.time ?: Date(Long.MAX_VALUE).time) / 1000
        when {
            groupId != null && groupId.dollarLast().toLongOrNull() != null -> {
                feverAPI.markGroup(
                    status = if (isUnread) FeverDTO.StatusEnum.Unread else FeverDTO.StatusEnum.Read,
                    id = requireNotNull(groupId.dollarLast().toLongOrNull()),
                    before = beforeUnixTimestamp,
                )
            }

            feedId != null -> {
                feverAPI.markFeed(
                    status = if (isUnread) FeverDTO.StatusEnum.Unread else FeverDTO.StatusEnum.Read,
                    id = feedId.dollarLast().toLong(),
                    before = beforeUnixTimestamp,
                )
            }

            articleId != null -> {
                feverAPI.markItem(
                    status = if (isUnread) FeverDTO.StatusEnum.Unread else FeverDTO.StatusEnum.Read,
                    id = articleId.dollarLast(),
                )
            }

            else -> {
                (if (groupId != null) feedDao.queryByGroupId(accountService.getCurrentAccountId(), groupId)
                else feedDao.queryAll(accountService.getCurrentAccountId())).forEach {
                    feverAPI.markFeed(
                        status =
                            if (isUnread) FeverDTO.StatusEnum.Unread else FeverDTO.StatusEnum.Read,
                        id = it.id.dollarLast().toLong(),
                        before = beforeUnixTimestamp,
                    )
                }
            }
        }
    }

    @CheckResult
    override suspend fun syncReadStatus(
        articleIds: Set<String>,
        isUnread: Boolean,
        account: Account?,
    ): Set<String> {
        val feverAPI = getFeverAPI(account)
        val syncedEntries = mutableSetOf<String>()
        articleIds
            .takeIf { it.isNotEmpty() }
            ?.forEachIndexed { index, it ->
                Log.d("RLog", "sync markAsRead: ${index}/${articleIds.size} num")
                feverAPI.markItem(
                    status = if (isUnread) FeverDTO.StatusEnum.Unread else FeverDTO.StatusEnum.Read,
                    id = it.dollarLast(),
                )
                syncedEntries += it
            }
        return syncedEntries
    }

    /** Fever 收藏同样固定文章账户，使本地状态与远端凭据保持一致。 */
    override suspend fun markAsStarred(account: Account, articleId: String, isStarred: Boolean) {
        super.markAsStarred(account, articleId, isStarred)
        val feverAPI = getFeverAPI(account)
        feverAPI.markItem(
            status = if (isStarred) FeverDTO.StatusEnum.Saved else FeverDTO.StatusEnum.Unsaved,
            id = articleId.dollarLast(),
        )
    }
}
