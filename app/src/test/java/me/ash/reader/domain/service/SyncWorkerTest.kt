package me.ash.reader.domain.service

import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class SyncWorkerTest {
    @Test
    fun `legacy ReadYou periodic work is cancelled during migration`() {
        val workManager = mock<WorkManager>()

        SyncWorker.migrateLegacyPeriodicWork(workManager)

        verify(workManager).cancelUniqueWork("ReadYou")
    }

    @Test
    fun `enqueue periodic work does not synchronously query WorkManager state`() {
        val workManager = mock<WorkManager> {
            on { getWorkInfosForUniqueWork(any()) } doThrow AssertionError("must not block")
        }
        val account =
            Account(
                id = 1,
                name = "test",
                type = AccountType.Local,
                syncInterval = SyncIntervalPreference.Every30Minutes,
            )

        SyncWorker.enqueuePeriodicWork(account, workManager)

        verify(workManager, never()).getWorkInfosForUniqueWork(any())
        verify(workManager).enqueueUniquePeriodicWork(
            any(),
            eq(ExistingPeriodicWorkPolicy.UPDATE),
            any(),
        )
    }
}
