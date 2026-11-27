package me.ash.reader.domain.service

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import me.ash.reader.ui.ext.showToastSuspend
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
    private val origReadBrandMigrationKey = booleanPreferencesKey("migration_origread_brand_v1")

    private val accountTypeKey = intPreferencesKey(DataStoreKey.currentAccountType)
    private val accountSwitchMutex = Mutex()
    private val committedAccountId = MutableStateFlow<Int?>(null)
    @Volatile private var committedAccountIdValue: Int? = null

    val currentAccountIdFlow =
        combine(settingsProvider.preferencesFlow, committedAccountId) { prefs, override ->
            override ?: prefs[accountIdKey]
        }
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

    fun getCurrentAccount(): Account {
        val id = getCurrentAccountId()
        return currentAccountFlow.value?.takeIf { it.id == id }
            ?: runBlocking { requireNotNull(accountDao.queryById(id)) { "当前账户不存在" } }
    }

    fun getCurrentAccountId(): Int =
        committedAccountIdValue ?: currentAccountIdFlow.value ?: runBlocking {
            currentAccountIdFlow.first { it != null } as Int
        }

    private suspend fun persistCurrentAccount(accountId: Int, accountTypeId: Int) {
        accountSwitchMutex.withLock {
            context.dataStore.edit { preferences ->
                preferences[accountIdKey] = accountId
                preferences[accountTypeKey] = accountTypeId
            }
            committedAccountIdValue = accountId
            committedAccountId.value = accountId
            context.dataStore.data.first {
                it[accountIdKey] == accountId && it[accountTypeKey] == accountTypeId
            }
        }
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
            persistCurrentAccount(it.id!!, it.type.id)
        }
    }

    private fun getDefaultAccount(): Account =
        Account(type = AccountType.Local, name = context.getString(R.string.origread))

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
     * 过渡期运行时兜底：处理极少数未经过标准 DB v10→v11 路径的开发版/异常数据。
     * 正常正式版升级的账户名和 Release 源已由 MIGRATION_10_11 迁移。
     * 连续保留若干公开版本后可删除本方法及 migration_origread_brand_v1 标记。
     */
    suspend fun migrateOrigReadIdentifiers() {
        if (settingsProvider.dataStore.first()[origReadBrandMigrationKey] == true) return

        accountDao.queryAll()
            .filter { it.type == AccountType.Local && it.name == "Read You" }
            .forEach { accountDao.update(it.copy(name = context.getString(R.string.origread))) }

        accountDao.queryAll().forEach { account ->
            val accountId = account.id ?: return@forEach
            feedDao.queryByLink(
                accountId = accountId,
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

        context.dataStore.edit { preferences ->
            preferences[origReadBrandMigrationKey] = true
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

    fun getDefaultGroup(accountId: Int = getCurrentAccountId()): Group =
        accountId.let {
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

    /** 同步完成只推进时间与游标列，不把抓取开始时的账户配置整行写回。 */
    suspend fun updateSyncMetadata(accountId: Int, updatedAt: Date, lastArticleId: String?) =
        accountDao.updateSyncMetadata(accountId, updatedAt, lastArticleId)

    /** 同步完成使用列更新，不把抓取开始时的账户配置整行写回。 */
    suspend fun updateSyncTime(accountId: Int, updatedAt: Date) =
        accountDao.updateSyncTime(accountId, updatedAt)

    /** 删除目标账户并切换到剩余账户；最后账户或不存在的目标返回 false，不能作为删除成功。 */
    suspend fun delete(accountId: Int): Boolean {
        if (accountDao.queryAll().size == 1) {
            context.showToastSuspend(context.getString(R.string.must_have_an_account))
            return false
        }
        val account = accountDao.queryById(accountId) ?: return false
        accountDao.deleteWithRelatedData(account, articleDao, feedDao, groupDao)
        val currentId = committedAccountIdValue ?: currentAccountIdFlow.value
            ?: context.dataStore.data.first()[accountIdKey]
        if (currentId == null || currentId == accountId) {
            accountDao.queryAll().firstOrNull()?.let {
                persistCurrentAccount(it.id!!, it.type.id)
            }
        }
        return true
    }

    suspend fun switch(account: Account) {
        persistCurrentAccount(account.id!!, account.type.id)
    }
}
