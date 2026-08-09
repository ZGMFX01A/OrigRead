package me.ash.reader.domain.service

import com.rometools.rome.feed.synd.SyndFeed
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.di.ApplicationScope

class RssService
@Inject
constructor(
    @ApplicationScope private val coroutineScope: CoroutineScope,
    private val accountService: AccountService,
    private val localRssService: LocalRssService,
    private val feverRssService: FeverRssService,
    private val googleReaderRssService: GoogleReaderRssService,
) {

    private val currentServiceFlow =
        accountService.currentAccountFlow
            .mapNotNull { it }
            .map { it.type.id }
            .distinctUntilChanged()
            .map { get(it) }
            .stateIn(coroutineScope, SharingStarted.Eagerly, localRssService)

    fun get() = get(accountService.getCurrentAccount().type.id)

    fun flow() = currentServiceFlow

    /**
     * 普通网站来源仅保存到本地账户，避免同步到第三方 RSS 服务。
     */
    suspend fun subscribeWebsite(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ): String {
        require(accountService.getCurrentAccount().type.id == AccountType.Local.id) {
            "Website sources are only supported for local accounts"
        }
        return localRssService.subscribeWebsite(
            feedLink = feedLink,
            searchedFeed = searchedFeed,
            groupId = groupId,
            isNotification = isNotification,
            isFullContent = isFullContent,
            isBrowser = isBrowser,
        )
    }

    /** RSSHub 来源仅支持本地账户，并额外保存原始页面 URL 用于失效回退。 */
    suspend fun subscribeRssHub(
        feedLink: String,
        sourcePageUrl: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ): String {
        require(accountService.getCurrentAccount().type.id == AccountType.Local.id) {
            "RSSHub sources are only supported for local accounts"
        }
        return localRssService.subscribeRssHub(
            feedLink = feedLink,
            sourcePageUrl = sourcePageUrl,
            searchedFeed = searchedFeed,
            groupId = groupId,
            isNotification = isNotification,
            isFullContent = isFullContent,
            isBrowser = isBrowser,
        )
    }

    /** JSON/API 来源仅支持本地账户。 */
    suspend fun subscribeJson(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ) {
        require(accountService.getCurrentAccount().type.id == AccountType.Local.id) {
            "JSON sources are only supported for local accounts"
        }
        localRssService.subscribeJson(
            feedLink = feedLink,
            searchedFeed = searchedFeed,
            groupId = groupId,
            isNotification = isNotification,
            isFullContent = isFullContent,
            isBrowser = isBrowser,
        )
    }

    fun get(accountTypeId: Int) =
        when (accountTypeId) {
            AccountType.Local.id -> localRssService
            AccountType.Fever.id -> feverRssService
            AccountType.GoogleReader.id -> googleReaderRssService
            AccountType.FreshRSS.id -> googleReaderRssService
            AccountType.Inoreader.id -> localRssService
            AccountType.Feedly.id -> localRssService
            else -> localRssService
        }
}
