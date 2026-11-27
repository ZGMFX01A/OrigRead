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

    /** 任务与后处理始终使用输入账户，部分来源失败也要刷新已完成来源的派生数据。 */
    override suspend fun doWork(): Result {
        val data = inputData
        val accountId = data.getInt("accountId", MISSING_ACCOUNT_ID)
        require(accountId != MISSING_ACCOUNT_ID) { "同步任务缺少账户 ID" }
        val feedId = data.getString("feedId")
        val groupId = data.getString("groupId")

        val service = rssService.forAccount(accountId)
        val result = service.sync(accountId = accountId, feedId = feedId, groupId = groupId)
        if (!shouldRunPostSync(result)) return result
        service.clearKeepArchivedArticles(accountId).forEach {
            readerCacheHelper.deleteCacheFor(articleId = it.id, accountId = it.accountId)
        }
        enqueuePostSync(accountId)
        return result
    }

    /** 全文任务按账户使用唯一名称，避免不同账户共用一项后处理。 */
    private fun enqueuePostSync(accountId: Int) {
        workManager.beginUniqueWork(
            uniqueWorkName = "$POST_SYNC_WORK_NAME-$accountId",
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReaderWorker>()
                .setInputData(workDataOf("accountId" to accountId))
                .addTag(READER_TAG)
                .addTag(ONETIME_WORK_TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SYNC_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        ).then(OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()).enqueue()
    }

    companion object {
        /** 无输入账户时明确失败，不按当前 UI 账户补值。 */
        private const val MISSING_ACCOUNT_ID = -1
        /** 沿用同步任务原有的最短退避秒数。 */
        private const val SYNC_BACKOFF_SECONDS = 30L
        /** 当前唯一周期同步名称，用于更新和取消调度。 */
        private const val SYNC_WORK_NAME_PERIODIC = "OrigRead"
        // 过渡期迁移键：旧版本的周期任务使用该唯一名称。
        private const val LEGACY_SYNC_WORK_NAME_PERIODIC = "ReadYou"
        @Deprecated("do not use")
        private const val READER_WORK_NAME_PERIODIC = "FETCH_FULL_CONTENT_PERIODIC"
        /** 全文与小组件后处理名称前缀，实际名称附加账户 ID。 */
        private const val POST_SYNC_WORK_NAME = "POST_SYNC_WORK"

        /** 现有前台一次性同步名称，重复触发替换尚未完成的任务。 */
        private const val SYNC_ONETIME_NAME = "SYNC_ONETIME"

        /** 同步任务查询标签。 */
        const val SYNC_TAG = "SYNC_TAG"
        /** 全文抓取任务查询标签。 */
        const val READER_TAG = "READER_TAG"
        /** 一次性任务查询标签。 */
        const val ONETIME_WORK_TAG = "ONETIME_WORK_TAG"
        /** 周期任务查询标签。 */
        const val PERIODIC_WORK_TAG = "PERIODIC_WORK_TAG"

        /** 取消现有一次性同步。 */
        fun cancelOneTimeWork(workManager: WorkManager) {
            workManager.cancelUniqueWork(SYNC_ONETIME_NAME)
        }

        /** 取消当前及迁移窗口内的旧名称周期任务。 */
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

        /** 一次性同步使用调用者提供的固定账户输入。 */
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

        /** 周期调度保留账户偏好，并将账户 ID 写入任务输入。 */
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
                        backoffDelay = SYNC_BACKOFF_SECONDS,
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

/** 账户级错误仍阻止后处理；已完成来源汇总的部分失败不阻止清理、全文缓存和小组件更新。 */
internal fun shouldRunPostSync(result: ListenableWorker.Result): Boolean =
    result is ListenableWorker.Result.Success ||
        (result is ListenableWorker.Result.Failure && result.outputData.getBoolean("sourcesProcessed", false))
