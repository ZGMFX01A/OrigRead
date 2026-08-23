package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import me.ash.reader.domain.model.account.Account
import me.ash.reader.infrastructure.rss.ReaderCacheHelper

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rssService: RssService,
    private val readerCacheHelper: ReaderCacheHelper,
    private val workManager: WorkManager,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val data = inputData
        val accountId = data.getInt("accountId", -1)
        require(accountId != -1)
        val feedId = data.getString("feedId")
        val groupId = data.getString("groupId")

        return rssService
            .get()
            .sync(accountId = accountId, feedId = feedId, groupId = groupId)
            .also {
                rssService.get().clearKeepArchivedArticles().forEach {
                    readerCacheHelper.deleteCacheFor(articleId = it.id)
                }
                workManager
                    .beginUniqueWork(
                        uniqueWorkName = POST_SYNC_WORK_NAME,
                        existingWorkPolicy = ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<ReaderWorker>()
                            .addTag(READER_TAG)
                            .addTag(ONETIME_WORK_TAG)
                            .setBackoffCriteria(
                                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                                backoffDelay = 30,
                                timeUnit = TimeUnit.SECONDS,
                            )
                            .build(),
                    )
                    .then(OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build())
                    .enqueue()
            }
    }

    companion object {
        private const val SYNC_WORK_NAME_PERIODIC = "OrigRead"
        // 过渡期迁移键：旧版本的周期任务使用该唯一名称。
        private const val LEGACY_SYNC_WORK_NAME_PERIODIC = "ReadYou"
        @Deprecated("do not use")
        private const val READER_WORK_NAME_PERIODIC = "FETCH_FULL_CONTENT_PERIODIC"
        private const val POST_SYNC_WORK_NAME = "POST_SYNC_WORK"

        private const val SYNC_ONETIME_NAME = "SYNC_ONETIME"

        const val SYNC_TAG = "SYNC_TAG"
        const val READER_TAG = "READER_TAG"
        const val ONETIME_WORK_TAG = "ONETIME_WORK_TAG"
        const val PERIODIC_WORK_TAG = "PERIODIC_WORK_TAG"

        fun cancelOneTimeWork(workManager: WorkManager) {
            workManager.cancelUniqueWork(SYNC_ONETIME_NAME)
        }

        fun cancelPeriodicWork(workManager: WorkManager) {
            workManager.cancelUniqueWork(SYNC_WORK_NAME_PERIODIC)
            workManager.cancelUniqueWork(LEGACY_SYNC_WORK_NAME_PERIODIC)
            workManager.cancelUniqueWork(READER_WORK_NAME_PERIODIC)
        }

        /**
         * 覆盖升级时清理旧品牌名下的唯一周期任务，避免旧 `ReadYou` 与新 `OrigRead` 两套任务并存。
         * 该方法幂等，属于可在迁移窗口结束后删除的临时运行时迁移。
         */
        fun migrateLegacyPeriodicWork(workManager: WorkManager) {
            workManager.cancelUniqueWork(LEGACY_SYNC_WORK_NAME_PERIODIC)
        }

        fun enqueueOneTimeWork(workManager: WorkManager, inputData: Data = workDataOf()) {
            workManager
                .beginUniqueWork(
                    SYNC_ONETIME_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<SyncWorker>()
                        .addTag(SYNC_TAG)
                        .addTag(ONETIME_WORK_TAG)
                        .setInputData(inputData)
                        .build(),
                )
                .enqueue()
        }

        fun enqueuePeriodicWork(account: Account, workManager: WorkManager) {
            val syncInterval = account.syncInterval
            val syncOnlyWhenCharging = account.syncOnlyWhenCharging
            val syncOnlyOnWiFi = account.syncOnlyOnWiFi

            workManager.enqueueUniquePeriodicWork(
                SYNC_WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(syncInterval.value, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresCharging(syncOnlyWhenCharging.value)
                            .setRequiredNetworkType(
                                if (syncOnlyOnWiFi.value) NetworkType.UNMETERED
                                else NetworkType.CONNECTED
                            )
                            .build()
                    )
                    .setBackoffCriteria(
                        backoffPolicy = BackoffPolicy.EXPONENTIAL,
                        backoffDelay = 30,
                        timeUnit = TimeUnit.SECONDS,
                    )
                    .setInputData(workDataOf("accountId" to account.id))
                    .addTag(SYNC_TAG)
                    .addTag(PERIODIC_WORK_TAG)
                    .setInitialDelay(syncInterval.value, TimeUnit.MINUTES)
                    .build(),
            )

            workManager.cancelUniqueWork(READER_WORK_NAME_PERIODIC)
        }
    }
}
