package me.ash.reader.infrastructure.sync.core

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit

/** R13 background policy: opportunity based, resumable and never a permanent foreground socket. */
object SyncBackgroundScheduler {
    private const val IMMEDIATE_WORK = "origread-sync-immediate"
    private const val COMPENSATION_WORK = "origread-sync-compensation"

    @Volatile
    var runner: (suspend () -> Boolean)? = null

    fun enqueueImmediate(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<SyncOpportunityWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun scheduleCompensation(context: Context, intervalHours: Long = 6L) {
        val request =
            PeriodicWorkRequestBuilder<SyncOpportunityWorker>(intervalHours.coerceAtLeast(1L), TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            COMPENSATION_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}

class SyncOpportunityWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result =
        runCatching {
            val completed = SyncBackgroundScheduler.runner?.invoke() ?: true
            if (completed) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
}
