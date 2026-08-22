package me.ash.reader.domain.service

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.domain.model.feed.normalizeRssReadingMode
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.put
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.spacerDollar

class AccountService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    @ApplicationScope private val coroutineScope: CoroutineScope,
    private val settingsProvider: SettingsProvider,
) {

    private val accountIdKey = intPreferencesKey(DataStoreKey.currentAccountId)
    private val rssReadingModeMigrationKey = booleanPreferencesKey("migration_rss_reading_mode_v1")

    val currentAccountIdFlow =
        settingsProvider.preferencesFlow
            .map { it[accountIdKey] }
            .stateIn(scope = coroutineScope, started = SharingStarted.Eagerly, initialValue = null)

    val currentAccountFlow =
        currentAccountIdFlow
            .combine(getAccounts()) { id, accounts ->
                id?.let { accounts.firstOrNull { it.id == id } }
            }
            .stateIn(scope = coroutineScope, SharingStarted.Eagerly, initialValue = null)

    fun getAccounts(): Flow<List<Account>> = accountDao.queryAllAsFlow()

    fun getAccountFlowById(accountId: Int): Flow<Account?> = accountDao.queryAccount(accountId)

    suspend fun getAccountById(accountId: Int): Account? = accountDao.queryById(accountId)

    fun getCurrentAccount(): Account =
        currentAccountFlow.value ?: runBlocking {
            currentAccountFlow.first { it != null } as Account
        }

    fun getCurrentAccountId(): Int =
        currentAccountIdFlow.value ?: runBlocking {
            currentAccountIdFlow.first { it != null } as Int
        }

    suspend fun isNoAccount(): Boolean = accountDao.queryAll().isEmpty()

    suspend fun addAccount(account: Account): Account {
        val id = accountDao.insert(account).toInt()
        return account.copy(id = id).also {
            when (it.type) {
                AccountType.Local -> {
                    groupDao.insert(
                        Group(
                            id = it.id!!.getDefaultGroupId(),
                            name = context.getString(R.string.defaults),
                            accountId = it.id!!,
                        )
                    )
                }
            }
            context.dataStore.put(DataStoreKey.currentAccountId, it.id!!)
            context.dataStore.put(DataStoreKey.currentAccountType, it.type.id)
        }
    }

    private fun getDefaultAccount(): Account =
        Account(type = AccountType.Local, name = context.getString(R.string.read_you))

    private suspend fun addDefaultAccount(): Account = addAccount(getDefaultAccount())

    suspend fun initWithDefaultAccount() {
        val account = addDefaultAccount()
        val group = getDefaultGroup()
        val initialFeed = getInitialFeed(account, group)
        feedDao.insert(initialFeed)
    }

    private fun getInitialFeed(account: Account, group: Group): Feed =
        Feed(
            id = account.id!!.spacerDollar(UUID.randomUUID().toString()),
            name = "OrigRead Releases",
            icon = "https://github.com/ZGMFX01A.png",
            url = "https://github.com/ZGMFX01A/OrigRead/releases.atom",
            groupId = group.id,
            accountId = account.id,
        )

    /**
     * 迁移旧版默认品牌数据，仅修改仍保持原始默认值的账户和订阅，避免覆盖用户自定义内容。
     */
    suspend fun migrateLegacyBranding() {
        accountDao.queryAll()
            .filter { it.type == AccountType.Local && it.name == "Read You" }
            .forEach { accountDao.update(it.copy(name = context.getString(R.string.read_you))) }

        accountDao.queryAll().forEach { account ->
            feedDao.queryByLink(
                accountId = account.id ?: return@forEach,
                url = "https://github.com/ReadYouApp/ReadYou/releases.atom",
            ).forEach { feed ->
                feedDao.update(
                    feed.copy(
                        name = "OrigRead Releases",
                        icon = "https://github.com/ZGMFX01A.png",
                        url = "https://github.com/ZGMFX01A/OrigRead/releases.atom",
                    )
                )
            }
        }
    }

    /**
     * 旧版添加来源界面曾可能让 RSS 来源保存成“全文解析 / 浏览器打开”。
     * 当前产品语义下 RSS / Atom 始终直接使用 Feed 内容，因此升级后仅执行一次归一化。
     */
    suspend fun migrateLegacyRssReadingMode() {
        if (settingsProvider.dataStore.first()[rssReadingModeMigrationKey] == true) return

        val existingFeeds = mutableListOf<Feed>()
        accountDao.queryAll().forEach { account ->
            account.id?.let { accountId -> existingFeeds += feedDao.queryAll(accountId) }
        }
        val feedsToNormalize =
            existingFeeds
                .filter { feed ->
                    feed.sourceType == SourceType.RSS && (feed.isFullContent || feed.isBrowser)
                }
                .map(Feed::normalizeRssReadingMode)

        if (feedsToNormalize.isNotEmpty()) {
            feedDao.updateAll(feedsToNormalize)
        }
        context.dataStore.edit { preferences ->
            preferences[rssReadingModeMigrationKey] = true
        }
    }

    fun getDefaultGroup(): Group =
        getCurrentAccountId().let {
            Group(
                id = it.getDefaultGroupId(),
                name = context.getString(R.string.defaults),
                accountId = it,
            )
        }

    suspend fun update(accountId: Int, block: Account.() -> Account) {
        accountDao.queryById(accountId)?.let { accountDao.update(it.run(block)) }
    }

    suspend fun update(account: Account) = accountDao.update(account)

    suspend fun delete(accountId: Int) {
        if (accountDao.queryAll().size == 1) {
            Looper.myLooper() ?: Looper.prepare()
            context.showToast(context.getString(R.string.must_have_an_account))
            Looper.loop()
            return
        }
        accountDao.queryById(accountId)?.let {
            articleDao.deleteByAccountId(accountId)
            feedDao.deleteByAccountId(accountId)
            groupDao.deleteByAccountId(accountId)
            accountDao.delete(it)
            accountDao.queryAll().getOrNull(0)?.let {
                context.dataStore.put(DataStoreKey.currentAccountId, it.id!!)
                context.dataStore.put(DataStoreKey.currentAccountType, it.type.id)
            }
        }
    }

    suspend fun switch(account: Account) {
        context.dataStore.put(DataStoreKey.currentAccountId, account.id!!)
        context.dataStore.put(DataStoreKey.currentAccountType, account.type.id)
    }
}
