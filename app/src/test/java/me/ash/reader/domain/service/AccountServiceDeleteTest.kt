package me.ash.reader.domain.service

import android.content.Context
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/** 验证拒绝分支会结束且不删除数据，以及实际删除后的账户切换。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountServiceDeleteTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)
    private val dispatcher = StandardTestDispatcher()
    private val directory = Files.createTempDirectory("origread-account-delete").toFile()
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStoreAccessor: org.mockito.MockedStatic<*>
    private val context = mock<Context>()
    private val accountDao = mock<AccountDao>()
    private val articleDao = mock<ArticleDao>()
    private val feedDao = mock<FeedDao>()
    private val groupDao = mock<GroupDao>()
    private val settingsProvider = mock<SettingsProvider>()
    private val first = Account(id = 1, name = "First", type = AccountType.Local)
    private val second = Account(id = 2, name = "Second", type = AccountType.Local)

    // 仅提供临时测试目录，不访问应用或设备上的用户偏好。
    @Before fun setUp() {
        runBlocking {
            org.mockito.kotlin.doCallRealMethod().whenever(accountDao).deleteWithRelatedData(any(), any(), any(), any())
        }
        Dispatchers.setMain(dispatcher)
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(directory)
        val testStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            directory.resolve("settings.preferences_pb")
        }
        // Keep a real DataStore per test; the top-level production delegate caches one instance.
        dataStoreAccessor = mockStatic(Class.forName("me.ash.reader.ui.ext.DataStoreExtKt"), org.mockito.Mockito.CALLS_REAL_METHODS)
        dataStoreAccessor.`when`<DataStore<Preferences>> { context.dataStore }.thenReturn(testStore)
        whenever(settingsProvider.preferencesFlow).thenReturn(MutableStateFlow(emptyPreferences()))
        whenever(accountDao.queryAllAsFlow()).thenReturn(flowOf(emptyList()))
    }

    @After fun tearDown() {
        if (::dataStoreAccessor.isInitialized) dataStoreAccessor.close()
        runBlocking { dataStoreScope.coroutineContext[Job]!!.cancelAndJoin() }
        Dispatchers.resetMain()
        check(directory.deleteRecursively()) { "Could not remove test DataStore directory" }
    }

    @Test fun `last account is refused without entering a loop or deleting rows`() = runTest(dispatcher) {
        whenever(accountDao.queryAll()).thenReturn(listOf(first))
        whenever(context.getString(R.string.must_have_an_account)).thenReturn("Keep an account")
        val toast = mock<Toast>()
        mockStatic(Toast::class.java).use { toastFactory ->
            toastFactory.`when`<Toast> {
                Toast.makeText(context, "Keep an account", Toast.LENGTH_SHORT)
            }.thenReturn(toast)

            assertEquals(false, service(backgroundScope).delete(first.id!!))

            verify(toast).show()
            verify(accountDao, never()).delete(any())
            verifyNoInteractions(articleDao, feedDao, groupDao)
        }
    }

    @Test fun `missing target is not reported as a successful deletion`() = runTest(dispatcher) {
        whenever(accountDao.queryAll()).thenReturn(listOf(first, second))
        whenever(accountDao.queryById(MISSING_ACCOUNT_ID)).thenReturn(null)

        assertEquals(false, service(backgroundScope).delete(MISSING_ACCOUNT_ID))

        verify(accountDao, never()).delete(any())
        verifyNoInteractions(articleDao, feedDao, groupDao)
    }

    @Test fun `deletion clears only the target and selects the remaining account`() = runTest(dispatcher) {
        whenever(accountDao.queryAll()).thenReturn(listOf(first, second), listOf(second))
        whenever(accountDao.queryById(first.id!!)).thenReturn(first)

        assertEquals(true, service(backgroundScope).delete(first.id!!))

        verify(articleDao).deleteByAccountId(first.id!!)
        verify(feedDao).deleteByAccountId(first.id!!)
        verify(groupDao).deleteByAccountId(first.id!!)
        verify(accountDao).delete(first)
        val preferences = context.dataStore.data.first()
        assertEquals(second.id, preferences[DataStoreKey.keys.getValue(DataStoreKey.currentAccountId).key])
        assertEquals(second.type.id, preferences[DataStoreKey.keys.getValue(DataStoreKey.currentAccountType).key])
    }

    @Test fun `switch commits id and matching account before returning with static preferences flow`() = runTest(dispatcher) {
        whenever(accountDao.queryById(second.id!!)).thenReturn(second)
        val service = service(backgroundScope)
        service.switch(first)
        service.switch(second)
        assertEquals(second.id, service.getCurrentAccountId())
        assertEquals(second, service.getCurrentAccount())
        val preferences = context.dataStore.data.first()
        assertEquals(second.id, preferences[DataStoreKey.keys.getValue(DataStoreKey.currentAccountId).key])
        assertEquals(second.type.id, preferences[DataStoreKey.keys.getValue(DataStoreKey.currentAccountType).key])
    }

    @Test fun `addAccount commits new id before returning`() = runTest(dispatcher) {
        whenever(accountDao.insert(any())).thenReturn(2L)
        whenever(context.getString(R.string.defaults)).thenReturn("Default")
        val service = service(backgroundScope)
        service.switch(first)
        val added = service.addAccount(second.copy(id = null))
        assertEquals(added.id, service.getCurrentAccountId())
    }

    @Test fun `deleting noncurrent account preserves active account`() = runTest(dispatcher) {
        val third = first.copy(id = 3, name = "Third")
        whenever(accountDao.queryAll()).thenReturn(listOf(first, second, third), listOf(first, third))
        whenever(accountDao.queryById(second.id!!)).thenReturn(second)
        val service = service(backgroundScope)
        service.switch(third)
        assertEquals(true, service.delete(second.id!!))
        assertEquals(third.id, service.getCurrentAccountId())
        assertEquals(third.id, context.dataStore.data.first()[DataStoreKey.keys.getValue(DataStoreKey.currentAccountId).key])
    }

    @Test fun `deleting current account commits remaining account before returning`() = runTest(dispatcher) {
        whenever(accountDao.queryAll()).thenReturn(listOf(first, second), listOf(first))
        whenever(accountDao.queryById(second.id!!)).thenReturn(second)
        val service = service(backgroundScope)
        service.switch(second)
        assertEquals(true, service.delete(second.id!!))
        assertEquals(first.id, service.getCurrentAccountId())
    }

    // 使用真实 AccountService；仅替换存储边界，拒绝分支中的 Looper 不做模拟。
    private fun service(scope: CoroutineScope) = AccountService(
        context = context,
        accountDao = accountDao,
        groupDao = groupDao,
        feedDao = feedDao,
        articleDao = articleDao,
        coroutineScope = scope,
        settingsProvider = settingsProvider,
    )

    private companion object {
        // 单个回归测试的最长执行时间，避免旧拒绝路径阻塞测试进程。
        const val TEST_TIMEOUT_SECONDS = 60L
        // 不存在的目标，用于防止空删除误报成功。
        const val MISSING_ACCOUNT_ID = 99
    }
}
