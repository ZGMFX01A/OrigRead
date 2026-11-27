package me.ash.reader.infrastructure.editionsync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.BuildConfig
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.backup.ConfigurationBackupService
import me.ash.reader.infrastructure.preference.KeepArchivedPreference
import me.ash.reader.infrastructure.preference.SyncBlockListPreference
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import me.ash.reader.infrastructure.preference.SyncOnStartPreference
import me.ash.reader.infrastructure.preference.SyncOnlyOnWiFiPreference
import me.ash.reader.infrastructure.preference.SyncOnlyWhenChargingPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** P7 Edition Sync 写入阶段失败后的补偿回滚测试。 */
class EditionSyncServiceRollbackTest {

    @Test
    fun `export bundle always includes shared secrets`() {
        runBlocking {
            val configurationBackupService = mock<ConfigurationBackupService>()
            val readingSnapshotService = mock<EditionSyncReadingSnapshotService>()
            val accountResolver = mock<EditionSyncAccountResolver>()
            val service =
                EditionSyncService(
                    configurationBackupService = configurationBackupService,
                    readingSnapshotService = readingSnapshotService,
                    accountResolver = accountResolver,
                )
            val reading = readingSnapshot(accountName = "Source")

            whenever(configurationBackupService.exportBackup(eq(true), any())).thenReturn("config-with-secrets")
            whenever(readingSnapshotService.exportCurrentAccount()).thenReturn(reading)

            service.exportBundle()

            verify(configurationBackupService).exportBackup(eq(true), any())
            verify(readingSnapshotService).validate(reading)
        }
    }

    @Test
    fun `reading failure restores previous account snapshot before configuration`() {
        runBlocking {
            val configurationBackupService = mock<ConfigurationBackupService>()
            val readingSnapshotService = mock<EditionSyncReadingSnapshotService>()
            val accountResolver = mock<EditionSyncAccountResolver>()
            val service =
                EditionSyncService(
                    configurationBackupService = configurationBackupService,
                    readingSnapshotService = readingSnapshotService,
                    accountResolver = accountResolver,
                )

            val sourceReading = readingSnapshot(accountName = "Source")
            val rollbackReading = readingSnapshot(accountName = "Target before sync")
            val targetAccount =
                Account(
                    id = 1,
                    name = "Target before sync",
                    type = AccountType.Local,
                    syncInterval = SyncIntervalPreference.Every30Minutes,
                    syncOnStart = SyncOnStartPreference.Off,
                    syncOnlyOnWiFi = SyncOnlyOnWiFiPreference.Off,
                    syncOnlyWhenCharging = SyncOnlyWhenChargingPreference.Off,
                    keepArchived = KeepArchivedPreference.For1Month,
                    syncBlockList = SyncBlockListPreference.default,
                )
            val resolution =
                EditionSyncAccountResolution(
                    account = targetAccount.copy(name = "Source"),
                    previousAccountId = 1,
                    originalAccount = targetAccount,
                    created = false,
                )
            val sourcePassword = "source-password"
            val sourceConfig = "source-config"
            val rollbackConfig = "rollback-config"
            val forcedFailure = IllegalStateException("forced reader restore failure")

            whenever(configurationBackupService.inspectBackup(sourceConfig)).thenReturn(mock())
            whenever(configurationBackupService.exportBackup(eq(true), any())).thenReturn(rollbackConfig)
            whenever(readingSnapshotService.exportCurrentAccount()).thenReturn(rollbackReading)
            whenever(accountResolver.resolveOrCreate(sourceReading.sourceAccount)).thenReturn(resolution)
            whenever(configurationBackupService.restoreBackup(sourceConfig, sourcePassword, 1)).thenReturn(mock())
            whenever(readingSnapshotService.restoreCurrentAccount(sourceReading, 1)).thenThrow(forcedFailure)
            whenever(configurationBackupService.restoreBackup(eq(rollbackConfig), any(), eq(1))).thenReturn(mock())
            whenever(readingSnapshotService.replaceCurrentAccount(rollbackReading, 1)).thenReturn(
                EditionSyncReadingRestoreResult(
                    targetAccountId = 1,
                    restoredGroups = 1,
                    restoredFeeds = 0,
                    restoredArticles = 0,
                    restoredArchivedArticles = 0,
                )
            )

            val bundle =
                EditionSyncBundle(
                    sourceEdition = EditionSyncEdition.fromBuildConfig(BuildConfig.EDITION).opposite().buildConfigValue,
                    sourcePackageName = EditionSyncContract.peerPackageName(),
                    sourceVersion = "test",
                    createdAtEpochMillis = 1L,
                    configurationBackupJson = sourceConfig,
                    configurationBackupPassword = sourcePassword,
                    reading = sourceReading,
                )
            val content = Json { encodeDefaults = true }.encodeToString(bundle)

            var thrown: Throwable? = null
            try {
                service.restoreBundle(content)
            } catch (error: Throwable) {
                thrown = error
            }

            assertNotNull(thrown)
            assertEquals(forcedFailure.message, thrown?.message)
            verify(configurationBackupService, never()).restoreBackup(sourceConfig, sourcePassword, 1)
            verify(configurationBackupService, times(2)).exportBackup(eq(true), any())
            verify(configurationBackupService).restoreBackup(eq(rollbackConfig), any(), eq(1))
            verify(readingSnapshotService).replaceCurrentAccount(rollbackReading, 1)
            verify(accountResolver).restoreOriginalAccount(resolution)
            verify(accountResolver).switchTo(1)
            inOrder(readingSnapshotService, configurationBackupService).apply {
                verify(readingSnapshotService).restoreCurrentAccount(sourceReading, 1)
                verify(readingSnapshotService).replaceCurrentAccount(rollbackReading, 1)
                verify(configurationBackupService).restoreBackup(eq(rollbackConfig), any(), eq(1))
            }
        }
    }

    @Test
    fun `cancelled restore still completes rollback before rethrowing cancellation`() {
        runBlocking {
            val configurationBackupService = mock<ConfigurationBackupService>()
            val readingSnapshotService = mock<EditionSyncReadingSnapshotService>()
            val accountResolver = mock<EditionSyncAccountResolver>()
            val service = EditionSyncService(configurationBackupService, readingSnapshotService, accountResolver)
            val sourceReading = readingSnapshot(accountName = "Source")
            val rollbackReading = readingSnapshot(accountName = "Target before sync")
            val targetAccount = Account(id = 1, name = "Target", type = AccountType.Local)
            val resolution =
                EditionSyncAccountResolution(
                    account = targetAccount.copy(name = "Source"),
                    previousAccountId = 1,
                    originalAccount = targetAccount,
                    created = false,
                )
            val sourcePassword = "source-password"
            val sourceConfig = "source-config"
            val rollbackConfig = "rollback-config"
            val cancellation = CancellationException("user cancelled restore")
            var rollbackCompleted = false

            whenever(configurationBackupService.inspectBackup(sourceConfig)).thenReturn(mock())
            whenever(configurationBackupService.exportBackup(eq(true), any())).thenReturn(rollbackConfig)
            whenever(readingSnapshotService.exportCurrentAccount()).thenReturn(rollbackReading)
            whenever(accountResolver.resolveOrCreate(sourceReading.sourceAccount)).thenReturn(resolution)
            whenever(configurationBackupService.restoreBackup(sourceConfig, sourcePassword, 1)).thenReturn(mock())
            whenever(readingSnapshotService.restoreCurrentAccount(sourceReading, 1)).thenThrow(cancellation)
            whenever(configurationBackupService.restoreBackup(eq(rollbackConfig), any(), eq(1))).thenReturn(mock())
            whenever(readingSnapshotService.replaceCurrentAccount(rollbackReading, 1)).thenAnswer {
                rollbackCompleted = true
                EditionSyncReadingRestoreResult(1, 1, 0, 0, 0)
            }

            val bundle =
                EditionSyncBundle(
                    sourceEdition = EditionSyncEdition.fromBuildConfig(BuildConfig.EDITION).opposite().buildConfigValue,
                    sourcePackageName = EditionSyncContract.peerPackageName(),
                    sourceVersion = "test",
                    createdAtEpochMillis = 1L,
                    configurationBackupJson = sourceConfig,
                    configurationBackupPassword = sourcePassword,
                    reading = sourceReading,
                )

            var thrown: Throwable? = null
            try {
                service.restoreBundle(Json { encodeDefaults = true }.encodeToString(bundle))
            } catch (error: Throwable) {
                thrown = error
            }

            assertEquals(cancellation, thrown)
            assertTrue("取消后仍必须完整执行 rollback", rollbackCompleted)
            verify(configurationBackupService).restoreBackup(eq(rollbackConfig), any(), eq(1))
            verify(readingSnapshotService).replaceCurrentAccount(rollbackReading, 1)
        }
    }

    @Test fun `configuration failure rolls back reading and configuration`() = verifyConfigurationFailure(false, false)

    @Test fun `configuration cancellation rolls back reading and configuration`() = verifyConfigurationFailure(true, false)

    @Test fun `configuration failure deletes newly created account after restoring previous snapshot`() = verifyConfigurationFailure(false, true)

    @Test fun `configuration cancellation deletes newly created account after restoring previous snapshot`() = verifyConfigurationFailure(true, true)

    @Test fun `reading failure rolls back a different existing target`() = verifyConfigurationFailure(false, false, true)

    @Test fun `reading cancellation rolls back a different existing target`() = verifyConfigurationFailure(true, false, true)

    @Test fun `reading failure deletes newly created account after full rollback`() = verifyConfigurationFailure(false, true, true)

    @Test fun `reading cancellation deletes newly created account after full rollback`() = verifyConfigurationFailure(true, true, true)

    private fun verifyConfigurationFailure(cancel: Boolean, created: Boolean, failReading: Boolean = false): Unit = runBlocking {
        val configuration = mock<ConfigurationBackupService>()
        val reading = mock<EditionSyncReadingSnapshotService>()
        val resolver = mock<EditionSyncAccountResolver>()
        val service = EditionSyncService(configuration, reading, resolver)
        val source = readingSnapshot("Source")
        val rollback = readingSnapshot("Before")
        val targetId = 7
        val previousId = 3
        val original = Account(targetId, "Before", AccountType.Local)
        val resolution = EditionSyncAccountResolution(original.copy(name = "Source"), previousId, if (created) null else original, created)
        val failure = if (cancel) CancellationException("cancel configuration") else IllegalStateException("fail configuration")
        val callerJob = Job()
        val result = EditionSyncReadingRestoreResult(targetId, 1, 0, 0, 0)
        whenever(configuration.exportBackup(eq(true), any())).thenReturn("rollback-config")
        whenever(reading.exportCurrentAccount()).thenReturn(rollback)
        whenever(resolver.resolveOrCreate(source.sourceAccount)).thenReturn(resolution)
        if (failReading) {
            whenever(reading.restoreCurrentAccount(source, targetId)).thenAnswer {
                if (cancel) callerJob.cancel(failure as CancellationException)
                throw failure
            }
        } else {
            whenever(reading.restoreCurrentAccount(source, targetId)).thenReturn(result)
            whenever(configuration.restoreBackup("source-config", "password", targetId)).thenAnswer {
                if (cancel) callerJob.cancel(failure as CancellationException)
                throw failure
            }
        }
        whenever(configuration.restoreBackup(eq("rollback-config"), any(), any())).thenReturn(mock())
        whenever(reading.replaceCurrentAccount(eq(rollback), any())).thenReturn(result)
        val bundle = EditionSyncBundle(
            sourceEdition = EditionSyncEdition.fromBuildConfig(BuildConfig.EDITION).opposite().buildConfigValue,
            sourcePackageName = EditionSyncContract.peerPackageName(), sourceVersion = "test", createdAtEpochMillis = 1,
            configurationBackupJson = "source-config", configurationBackupPassword = "password", reading = source,
        )
        var thrown: Throwable? = null
        try { withContext(callerJob) { service.restoreBundle(Json.encodeToString(bundle)) } } catch (error: Throwable) { thrown = error }
        finally { callerJob.complete() }
        assertEquals(failure::class.java, thrown?.javaClass)
        assertEquals(failure.message, thrown?.message)
        if (failReading) verify(configuration, never()).restoreBackup("source-config", "password", targetId)
        val rollbackId = if (created) previousId else targetId
        inOrder(reading, configuration, resolver).apply {
            verify(reading).restoreCurrentAccount(source, targetId)
            if (!failReading) verify(configuration).restoreBackup("source-config", "password", targetId)
            if (created) verify(resolver).switchTo(previousId)
            verify(reading).replaceCurrentAccount(rollback, rollbackId)
            verify(configuration).restoreBackup(eq("rollback-config"), any(), eq(rollbackId))
            if (created) verify(resolver).deleteCreatedAccount(resolution)
            else verify(resolver).restoreOriginalAccount(resolution)
            verify(resolver).switchTo(previousId)
        }
    }

    /** 构造最小合法阅读快照；Service 校验和失败补偿都复用同一协议模型。 */
    private fun readingSnapshot(accountName: String): EditionSyncReadingSnapshot =
        EditionSyncReadingSnapshot(
            sourceAccount =
                EditionSyncAccountSnapshot(
                    name = accountName,
                    typeId = AccountType.Local.id,
                    securityKey = null,
                    updatedAtEpochMillis = null,
                    lastArticleKey = null,
                ),
            groups = listOf(EditionSyncGroupSnapshot(key = "default", name = "Default", isDefault = true)),
            feeds = emptyList(),
            articles = emptyList(),
            archivedArticles = emptyList(),
        )
}
