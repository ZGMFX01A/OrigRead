package me.ash.reader.domain.data

import android.content.Context
import androidx.annotation.Keep
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import java.io.File
import javax.inject.Inject

@OptIn(FlowPreview::class)
class DiffMapHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val accountService: AccountService,
    private val rssService: RssService,
) {
    val diffMap = mutableStateMapOf<String, Diff>()

    private val pendingDbDiffs = mutableMapOf<String, PendingDbDiff>()
    private val syncState = DiffSyncState()
    private val stateLock = Any()
    private val cacheWriteMutex = Mutex()
    private val remoteSyncMutex = Mutex()
    private val cacheWriteRevisions = mutableMapOf<String, CacheWriteToken>()
    private var revision = 0L
    private var sessionGeneration = 0L
    private var accountTransitioning = false
    private var commitJob: Job? = null
    private var cacheRestoreJob: Job? = null
    private var remoteRetryJob: Job? = null
    private var remoteRetryToken: Any? = null
    private var remoteRetryRequested = false

    private val diffMapSnapshotWithRevisionFlow = snapshotFlow {
        synchronized(stateLock) {
            DiffMapSnapshot(
                diffs = diffMap.toMap(),
                pendingDbDiffs = pendingDbDiffs.mapValues { it.value.diff },
                cacheDir = userCacheDir,
                cacheToken = CacheWriteToken(sessionGeneration, revision),
            )
        }
    }

    val diffMapSnapshotFlow = diffMapSnapshotWithRevisionFlow.map { it.diffs }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    private val pendingSyncDiffsSnapshotFlow = snapshotFlow {
        synchronized(stateLock) { syncState.pending.toMap() }
    }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap()
    )

    val shouldSyncWithRemote get() = currentAccount?.type != AccountType.Local

    private val gson = Gson()

    private val cacheDir = context.cacheDir.resolve("diff")
    private var userCacheDir = cacheDir

    private var currentAccount: Account? = null

    var dbJob: Job? = null
    var remoteJob: Job? = null

    init {
        applicationScope.launch {
            accountService.currentAccountFlow.mapNotNull { it }.collect { account ->
                val previousAccount = synchronized(stateLock) { currentAccount }
                if (previousAccount != null && previousAccount != account) {
                    cleanup()
                }
                synchronized(stateLock) {
                    if (previousAccount == null) {
                        sessionGeneration++
                    }
                    currentAccount = account
                    accountTransitioning = false
                }
                init(account)
            }
        }
    }

    private fun init(account: Account) {
        synchronized(stateLock) {
            userCacheDir = cacheDir.resolve(account.id.toString())
        }
        commitDiffsFromCache()
        commitOnChange()
        if (account.type != AccountType.Local) {
            syncOnChange(account)
        }
    }

    private suspend fun cleanup() {
        val jobsToCancel = synchronized(stateLock) {
            accountTransitioning = true
            val jobs = listOfNotNull(
                dbJob,
                remoteJob,
                commitJob,
                cacheRestoreJob,
                remoteRetryJob,
            ).distinct()
            dbJob = null
            remoteJob = null
            commitJob = null
            cacheRestoreJob = null
            remoteRetryJob = null
            remoteRetryToken = null
            remoteRetryRequested = false
            val snapshot = DiffMapSnapshot(
                diffs = diffMap.toMap(),
                pendingDbDiffs = pendingDbDiffs.mapValues { it.value.diff },
                cacheDir = userCacheDir,
                cacheToken = CacheWriteToken(sessionGeneration, revision),
            )
            sessionGeneration++
            diffMap.clear()
            pendingDbDiffs.clear()
            syncState.clear()
            revision = 0L
            jobs to snapshot
        }
        jobsToCancel.first.forEach { it.cancel() }
        val oldState = jobsToCancel.second
        writeDiffsToCache(
            diffs = oldState.diffs,
            pendingDbDiffs = oldState.pendingDbDiffs,
            targetCacheDir = oldState.cacheDir,
            cacheToken = oldState.cacheToken,
        )
    }

    private fun commitOnChange() {
        dbJob = applicationScope.launch(ioDispatcher) {
            diffMapSnapshotWithRevisionFlow.debounce(2_000).collect { snapshot ->
                if (snapshot.diffs.isNotEmpty() || snapshot.pendingDbDiffs.isNotEmpty()) {
                    writeDiffsToCache(
                        diffs = snapshot.diffs,
                        pendingDbDiffs = snapshot.pendingDbDiffs,
                        targetCacheDir = snapshot.cacheDir,
                        cacheToken = snapshot.cacheToken,
                    )
                }
            }
        }
    }

    private fun syncOnChange(account: Account) {
        val context = synchronized(stateLock) {
            account.id?.let {
                CommitContext(
                    accountId = it,
                    accountTypeId = account.type.id,
                    sessionGeneration = sessionGeneration,
                    cacheDir = userCacheDir,
                )
            }
        } ?: return
        remoteJob = applicationScope.launch(ioDispatcher) {
            pendingSyncDiffsSnapshotFlow.debounce(2_000).collect {
                if (isCurrentSession(context)) {
                    withContext(ioDispatcher) {
                        syncDiffsWithRemote(it, account, context)
                    }
                }
            }
        }
    }

    fun checkIfUnread(articleWithFeed: ArticleWithFeed): Boolean {
        return synchronized(stateLock) {
            if (accountTransitioning) return@synchronized articleWithFeed.article.isUnread
            diffMap[articleWithFeed.article.id]?.isUnread ?: articleWithFeed.article.isUnread
        }
    }

    /**
     * Updates the diff map with changes to an article's read/unread status.
     *
     * This function manages a map (`diffMap`) that tracks pending changes (diffs) to the
     * read/unread status of articles. These changes are not immediately applied to the
     * underlying data store but are held in `diffMap` until a later commit operation.
     *
     * The function supports three modes of updating:
     *
     * 1. **Toggle:** If `isUnread` is `null`, the function toggles the current read/unread
     *    status of the article.  If the article is currently unread, it will be marked as read,
     *    and vice-versa.
     * 2. **Mark as Unread:** If `isUnread` is `true`, the article will be marked as unread,
     *    regardless of its current status.
     * 3. **Mark as Read:** If `isUnread` is `false`, the article will be marked as read,
     *    regardless of its current status.
     *
     * The function determines if a change needs to be tracked based on the current status and desired status:
     *  - If the requested change matches the article's current status, the diff is removed from the map, if it exists. (No change is needed.)
     *  - Otherwise, the diff is added to or updated in the map.
     *
     * @param articleWithFeed The article and its associated feed data. This is used to identify the article
     *                        and access its current read/unread state.
     * @param isUnread An optional boolean indicating the desired read/unread status of the article.
     *                 - `null`: Toggles the current read/unread status.
     *                 - `true`: Marks the article as unread.
     *                 - `false`: Marks the article as read.
     *
     * @return A [Diff] object representing the changes made to the article.
     *
     * @see Diff
     */
    private fun updateDiffInternal(
        articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ): Diff? {
        val articleId = articleWithFeed.article.id
        val currentIsUnread =
            diffMap[articleId]?.isUnread ?: articleWithFeed.article.isUnread
        val targetIsUnread = isUnread ?: !currentIsUnread
        if (targetIsUnread == currentIsUnread) return null

        val diff = Diff(
            isUnread = targetIsUnread,
            articleWithFeed = articleWithFeed,
        )
        if (targetIsUnread == articleWithFeed.article.isUnread) {
            diffMap.remove(articleId)
        } else {
            diffMap[articleId] = diff
        }

        revision++
        pendingDbDiffs[articleId] = PendingDbDiff(diff = diff, revision = revision)
        return diff
    }

    fun updateDiff(
        vararg articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null
    ) {
        updateDiff(articleWithFeed.asList(), isUnread)
    }

    fun updateDiff(
        articleWithFeed: List<ArticleWithFeed>, isUnread: Boolean? = null
    ) {
        synchronized(stateLock) {
            if (accountTransitioning) return
            val appliedDiffs = articleWithFeed.mapNotNull {
                updateDiffInternal(it, isUnread)
            }
            if (shouldSyncWithRemote) {
                appliedDiffs.forEach {
                    appendDiffToSync(it)
                }
            }
        }
    }

    private fun appendDiffToSync(diff: Diff) {
        syncState.append(diff)
    }

    fun commitDiffsToDb() {
        synchronized(stateLock) {
            if (commitJob?.isActive == true) return
            commitJob = applicationScope.launch(ioDispatcher) {
                commitPendingDiffs()
            }
        }
    }

    private suspend fun commitPendingDiffs() {
        val context = synchronized(stateLock) {
            currentAccount?.let { account ->
                account.id?.let {
                    CommitContext(
                        accountId = it,
                        accountTypeId = account.type.id,
                        sessionGeneration = sessionGeneration,
                        cacheDir = userCacheDir,
                    )
                }
            }
        } ?: return

        while (true) {
            val snapshot = synchronized(stateLock) {
                if (!isCurrentSessionLocked(context)) return
                pendingDbDiffs.toMap()
            }
            if (snapshot.isEmpty()) return

            val markAsReadArticles = snapshot
                .filter { !it.value.diff.isUnread }
                .keys
            val markAsUnreadArticles = snapshot
                .filter { it.value.diff.isUnread }
                .keys
            try {
                val rssRepository = rssService.get(context.accountTypeId)

                if (!isCurrentSession(context)) return
                if (markAsReadArticles.isNotEmpty()) {
                    rssRepository.batchMarkAsRead(
                        articleIds = markAsReadArticles,
                        isUnread = false,
                        accountId = context.accountId,
                    )
                }
                if (markAsUnreadArticles.isNotEmpty()) {
                    if (!isCurrentSession(context)) return
                    rssRepository.batchMarkAsRead(
                        articleIds = markAsUnreadArticles,
                        isUnread = true,
                        accountId = context.accountId,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                persistCurrentState(context)
                return
            }

            val remainingSnapshot = synchronized(stateLock) {
                if (!isCurrentSessionLocked(context)) return
                snapshot.forEach { (articleId, pendingDiff) ->
                    if (pendingDbDiffs[articleId]?.revision == pendingDiff.revision) {
                        pendingDbDiffs.remove(articleId)
                        if (diffMap[articleId] == pendingDiff.diff) {
                            diffMap.remove(articleId)
                        }
                        revision++
                    }
                }
                DiffMapSnapshot(
                    diffs = diffMap.toMap(),
                    pendingDbDiffs = pendingDbDiffs.mapValues { it.value.diff },
                    cacheDir = userCacheDir,
                    cacheToken = CacheWriteToken(sessionGeneration, revision),
                )
            }
            writeDiffsToCache(
                diffs = remainingSnapshot.diffs,
                pendingDbDiffs = remainingSnapshot.pendingDbDiffs,
                targetCacheDir = remainingSnapshot.cacheDir,
                cacheToken = remainingSnapshot.cacheToken,
            )
        }
    }

    private suspend fun persistCurrentState(context: CommitContext) {
        val snapshot = synchronized(stateLock) {
            if (!isCurrentSessionLocked(context)) return
            DiffMapSnapshot(
                diffs = diffMap.toMap(),
                pendingDbDiffs = pendingDbDiffs.mapValues { it.value.diff },
                cacheDir = context.cacheDir,
                cacheToken = CacheWriteToken(context.sessionGeneration, revision),
            )
        }
        writeDiffsToCache(
            diffs = snapshot.diffs,
            pendingDbDiffs = snapshot.pendingDbDiffs,
            targetCacheDir = snapshot.cacheDir,
            cacheToken = snapshot.cacheToken,
        )
    }

    private suspend fun writeDiffsToCache(
        diffs: Map<String, Diff>,
        pendingDbDiffs: Map<String, Diff> = emptyMap(),
        targetCacheDir: File = userCacheDir,
        cacheToken: CacheWriteToken? = null,
    ) {
        cacheWriteMutex.withLock {
            withContext(ioDispatcher) {
                val targetCacheFile = targetCacheDir.resolve("diff_map.json")
                try {
                    val targetPath = targetCacheFile.absolutePath
                    val lastWrite = cacheWriteRevisions[targetPath]
                    if (cacheToken != null && lastWrite != null &&
                        !lastWrite.isOlderThan(cacheToken)
                    ) {
                        return@withContext
                    }
                    if (diffs.isEmpty() && pendingDbDiffs.isEmpty()) {
                        if (targetCacheFile.exists() && !targetCacheFile.delete()) {
                            return@withContext
                        }
                        if (cacheToken != null) {
                            cacheWriteRevisions[targetPath] = cacheToken
                        }
                        return@withContext
                    }

                    val tmpJson = gson.toJson(
                        StoredDiffs(
                            diffs = diffs,
                            pendingDbDiffs = pendingDbDiffs,
                        )
                    )
                    targetCacheDir.mkdirs()
                    val tmpFile = targetCacheDir.resolve("${targetCacheFile.name}.tmp")
                    tmpFile.writeText(tmpJson)
                    if (!tmpFile.renameTo(targetCacheFile)) {
                        targetCacheFile.writeText(tmpJson)
                        tmpFile.delete()
                    }
                    if (cacheToken != null) {
                        cacheWriteRevisions[targetPath] = cacheToken
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun syncDiffsWithRemote(
        diffs: Map<String, Diff>,
        account: Account,
        context: CommitContext,
    ) = remoteSyncMutex.withLock {
        syncDiffsWithRemoteLocked(diffs, account, context)
    }

    private suspend fun syncDiffsWithRemoteLocked(
        diffs: Map<String, Diff>,
        account: Account,
        context: CommitContext,
    ) {
        if (diffs.isEmpty()) return
        if (!isCurrentSession(context)) return
        val toBeSync = synchronized(stateLock) {
            if (!isCurrentSessionLocked(context)) return
            syncState.begin(diffs)
        }
        if (toBeSync.isEmpty()) return
        val markAsReadArticles =
            toBeSync.filter { !it.value.isUnread }.map { it.key }.toSet()
        val markAsUnreadArticles =
            toBeSync.filter { it.value.isUnread }.map { it.key }.toSet()

        val rssService = rssService.get(context.accountTypeId)

        val synced = supervisorScope {
            val read = async {
                rssService.syncReadStatus(
                    articleIds = markAsReadArticles,
                    isUnread = false,
                    account = account,
                )
            }
            val unread = async {
                rssService.syncReadStatus(
                    articleIds = markAsUnreadArticles,
                    isUnread = true,
                    account = account,
                )
            }
            runCatching { read.await() }.getOrElse { error ->
                if (error is CancellationException) throw error
                emptySet()
            } + runCatching { unread.await() }.getOrElse { error ->
                if (error is CancellationException) throw error
                emptySet()
            }
        }

        if (isCurrentSession(context)) {
            synchronized(stateLock) {
                if (!isCurrentSessionLocked(context)) return
                syncState.complete(toBeSync, synced)
            }
        }

        if (synced.size < toBeSync.size && isCurrentSession(context)) {
            scheduleRemoteRetry(account, context)
        }
    }

    private fun scheduleRemoteRetry(account: Account, context: CommitContext) {
        synchronized(stateLock) {
            if (!isCurrentSessionLocked(context)) return
            if (remoteRetryJob?.isActive == true) {
                remoteRetryRequested = true
                return
            }
            val retryToken = Any()
            remoteRetryRequested = false
            remoteRetryToken = retryToken
            remoteRetryJob = applicationScope.launch(ioDispatcher) {
                try {
                    delay(5_000)
                    if (isCurrentSession(context)) {
                        val pending = synchronized(stateLock) {
                            syncState.pending.toMap()
                        }
                        syncDiffsWithRemote(pending, account, context)
                    }
                } finally {
                    val retryAgain = synchronized(stateLock) {
                        if (remoteRetryToken !== retryToken ||
                            !isCurrentSessionLocked(context)
                        ) {
                            false
                        } else {
                            remoteRetryJob = null
                            remoteRetryToken = null
                            val requested = remoteRetryRequested
                            remoteRetryRequested = false
                            requested && syncState.pending.isNotEmpty()
                        }
                    }
                    if (retryAgain && isCurrentSession(context)) {
                        scheduleRemoteRetry(account, context)
                    }
                }
            }
        }
    }

    private fun commitDiffsFromCache() {
        val context = synchronized(stateLock) {
            currentAccount?.let { account ->
                account.id?.let {
                    CommitContext(
                        accountId = it,
                        accountTypeId = account.type.id,
                        sessionGeneration = sessionGeneration,
                        cacheDir = userCacheDir,
                    )
                }
            }
        } ?: return

        cacheRestoreJob = applicationScope.launch(ioDispatcher) {
            val targetCacheFile = context.cacheDir.resolve("diff_map.json")
            val storedDiffs = runCatching {
                if (targetCacheFile.exists() && targetCacheFile.canRead()) {
                    val tmpJson = targetCacheFile.readText()
                    if (JsonParser.parseString(tmpJson).asJsonObject.has("diffs")) {
                        gson.fromJson<StoredDiffs>(tmpJson, StoredDiffs::class.java)
                    } else {
                        val mapType = object : TypeToken<Map<String, Diff>>() {}.type
                        val legacyDiffs = gson.fromJson<Map<String, Diff>>(tmpJson, mapType)
                        StoredDiffs(diffs = legacyDiffs, pendingDbDiffs = legacyDiffs)
                    }
                } else {
                    null
                }
            }.getOrNull()

            synchronized(stateLock) {
                if (!isCurrentSessionLocked(context)) return@launch
                if (diffMap.isEmpty() && pendingDbDiffs.isEmpty()) {
                    storedDiffs?.diffs.orEmpty().forEach { (articleId, diff) ->
                        diffMap[articleId] = diff
                    }
                    val pending = storedDiffs?.pendingDbDiffs.orEmpty()
                        .ifEmpty { storedDiffs?.diffs.orEmpty() }
                    pending.forEach { (articleId, diff) ->
                        revision++
                        pendingDbDiffs[articleId] = PendingDbDiff(
                            diff = diff,
                            revision = revision,
                        )
                    }
                }
            }
            commitDiffsToDb()
        }
    }

    private fun isCurrentSession(context: CommitContext): Boolean = synchronized(stateLock) {
        isCurrentSessionLocked(context)
    }

    private fun isCurrentSessionLocked(context: CommitContext): Boolean =
        currentAccount?.id == context.accountId &&
                currentAccount?.type?.id == context.accountTypeId &&
                sessionGeneration == context.sessionGeneration

    private data class PendingDbDiff(
        val diff: Diff,
        val revision: Long,
    )

    private data class DiffMapSnapshot(
        val diffs: Map<String, Diff>,
        val pendingDbDiffs: Map<String, Diff>,
        val cacheDir: File,
        val cacheToken: CacheWriteToken,
    )

    @Keep
    private data class StoredDiffs(
        @field:SerializedName("diffs")
        val diffs: Map<String, Diff>? = null,
        @field:SerializedName("pendingDbDiffs")
        val pendingDbDiffs: Map<String, Diff>? = null,
    )

    private data class CommitContext(
        val accountId: Int,
        val accountTypeId: Int,
        val sessionGeneration: Long,
        val cacheDir: File,
    )

    private data class CacheWriteToken(
        val sessionGeneration: Long,
        val revision: Long,
    ) {
        fun isOlderThan(other: CacheWriteToken): Boolean =
            sessionGeneration < other.sessionGeneration ||
                    (sessionGeneration == other.sessionGeneration && revision < other.revision)
    }
}

@Keep
data class Diff(
    @field:SerializedName("isUnread") val isUnread: Boolean,
    @field:SerializedName("articleId") val articleId: String,
    @field:SerializedName("feedId") val feedId: String,
) {
    constructor(isUnread: Boolean, articleWithFeed: ArticleWithFeed) : this(
        isUnread = isUnread,
        articleId = articleWithFeed.article.id,
        feedId = articleWithFeed.feed.id,
    )
}

internal class DiffSyncState {
    val pending = mutableStateMapOf<String, Diff>()

    private val inFlight = mutableMapOf<String, Diff>()
    private val synced = mutableMapOf<String, Diff>()

    fun append(diff: Diff) {
        val syncedDiff = synced[diff.articleId]
        if (syncedDiff != null &&
            syncedDiff.isUnread == diff.isUnread &&
            inFlight[diff.articleId] == null
        ) {
            pending.remove(diff.articleId)
        } else {
            pending[diff.articleId] = diff
        }
    }

    fun begin(snapshot: Map<String, Diff>): Map<String, Diff> {
        return snapshot.filter { (articleId, diff) -> pending[articleId] == diff }
            .also { current ->
                current.forEach { (articleId, diff) ->
                    inFlight[articleId] = diff
                }
            }
    }

    fun complete(request: Map<String, Diff>, syncedArticleIds: Set<String>) {
        request.forEach { (articleId, diff) ->
            if (inFlight[articleId] != diff) return@forEach
            inFlight.remove(articleId)
            if (articleId in syncedArticleIds) {
                if (pending[articleId] == diff) {
                    pending.remove(articleId)
                }
                synced[articleId] = diff
            }
        }
    }

    fun clear() {
        pending.clear()
        inFlight.clear()
        synced.clear()
    }
}
