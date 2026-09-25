package me.ash.reader.infrastructure.rss

import android.content.Context
import androidx.annotation.CheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.content.FullContentException
import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.sync.core.LibrarySyncMutationCapture
import me.ash.reader.infrastructure.sync.core.SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND
import me.ash.reader.infrastructure.sync.core.SyncBlobAvailabilityState
import me.ash.reader.infrastructure.sync.core.SyncBlobStateService
import me.ash.reader.infrastructure.sync.core.SyncLocalBlobStore
import me.ash.reader.infrastructure.sync.core.SyncLocalEvictionService
import me.ash.reader.infrastructure.sync.core.SyncReplicationLane
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

class ReaderCacheHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val rssHelper: RssHelper,
    private val accountService: AccountService,
    private val syncMutations: LibrarySyncMutationCapture,
    private val database: AndroidDatabase,
    private val syncBlobStore: SyncLocalBlobStore,
    private val localEviction: SyncLocalEvictionService,
) {
    private val cacheDir = context.cacheDir.resolve("readability")
    private val blobState = SyncBlobStateService(database)

    private val currentCacheDir: File
        get() = cacheDir.resolve(accountService.getCurrentAccountId().toString())

    private fun cacheDirFor(accountId: Int): File = cacheDir.resolve(accountId.toString())

    @OptIn(ExperimentalStdlibApi::class)
    private fun getFileNameFor(articleId: String): String {
        val bytes = "$CACHE_VERSION:$articleId".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.toHexString() + ".html"
    }

    suspend fun writeContentToCache(content: String, articleId: String): Boolean {
        val accountId = accountService.getCurrentAccountId()
        return syncMutations.captureArticleFullContent(accountId, articleId, content) {
            writeContentToCacheRaw(accountId, content, articleId)
        }
    }

    /** Remote Sync materialization path. It bypasses local mutation capture to prevent echo. */
    suspend fun writeContentToCacheFromSync(
        accountId: Int,
        content: String,
        articleId: String,
    ): Boolean = writeContentToCacheRaw(accountId, content, articleId)

    private suspend fun writeContentToCacheRaw(
        accountId: Int,
        content: String,
        articleId: String,
    ): Boolean =
        withContext(ioDispatcher) {
            runCatching {
                    cacheDirFor(accountId).run {
                        mkdirs()
                        resolve(getFileNameFor(articleId)).run {
                            createNewFile()
                            writeText(content)
                        }
                    }
                }
                .fold(onSuccess = { true }, onFailure = { false })
        }

    @CheckResult
    suspend fun readFullContent(articleId: String): Result<String> {
        return readFullContentForAccount(accountService.getCurrentAccountId(), articleId)
    }

    suspend fun readFullContentForAccount(
        accountId: Int,
        articleId: String,
    ): Result<String> {
        return withContext(ioDispatcher) {
            runCatching {
                val file = cacheDirFor(accountId).resolve(getFileNameFor(articleId))
                if (!file.exists()) return@withContext Result.failure(FileNotFoundException())
                file.readText()
            }
        }
    }

    private suspend fun fetchFullContentInternal(
        article: Article,
        allowDynamicFallback: Boolean,
    ): Result<String> {
        return withContext(ioDispatcher) {
            runCatching {
                val fullContent =
                    rssHelper.parseFullContent(
                        link = article.link,
                        title = article.title,
                        allowDynamicFallback = allowDynamicFallback,
                    )
                if (fullContent.isNotBlank()) {
                    writeContentToCache(fullContent, article.id)
                    fullContent
                } else return@withContext Result.failure(Exception())
            }
        }
    }

    @CheckResult
    suspend fun readOrFetchFullContent(article: Article): Result<String> {
        return withContext(ioDispatcher) {
            runCatching {
                val result = readFullContent(article.id)
                if (result.isSuccess) return@withContext result
                val accountId = accountService.getCurrentAccountId()
                val synced = restoreSyncedFullContent(accountId, article.id)
                if (synced != null) return@withContext synced
                return@withContext fetchFullContentInternal(article, allowDynamicFallback = true)
            }
        }
    }

    suspend fun checkOrFetchFullContent(article: Article): Boolean {
        return withContext(ioDispatcher) {
            val file = currentCacheDir.resolve(getFileNameFor(article.id))
            try {
                if (!file.exists()) {
                    val accountId = accountService.getCurrentAccountId()
                    val synced = restoreSyncedFullContent(accountId, article.id)
                    if (synced != null) return@withContext synced.isSuccess
                    // ReaderWorker 可能一次预取多篇文章，后台任务禁止启动 WebView。
                    return@withContext fetchFullContentInternal(article, allowDynamicFallback = false)
                        .fold(onFailure = { false }, onSuccess = { true })
                } else {
                    return@withContext true
                }
            } catch (_: SecurityException) {
                return@withContext false
            }
        }
    }

    /**
     * Recover a synced full-content Blob before falling back to the source URL.
     *
     * null means there is no active Sync Blob reference for this article (or the user explicitly
     * LOCAL_EVICTed it), so normal source fetching may proceed. A failed Result means metadata says
     * the synced full content exists but its bytes are not ready yet.
     */
    private suspend fun restoreSyncedFullContent(
        accountId: Int,
        articleId: String,
    ): Result<String>? {
        val binding = database.syncRuntimeDao().findBinding(accountId) ?: return null
        val mapping =
            database.syncIdentityMappingDao().findByLocalId(
                binding.syncSpaceId,
                SyncEntityType.ARTICLE.wireName,
                articleId,
            ) ?: return null
        if (
            localEviction.isEvicted(
                binding.syncSpaceId,
                SyncEntityType.ARTICLE.wireName,
                mapping.syncId,
                mapping.generation,
                SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND,
            )
        ) {
            return null
        }
        val reference =
            database.syncBlobDao()
                .listReferencesForOwner(
                    binding.syncSpaceId,
                    SyncReplicationLane.ARTICLE_STATE.wireName,
                    SyncEntityType.ARTICLE.wireName,
                    mapping.syncId,
                    mapping.generation,
                )
                .firstOrNull { it.referenceKind == SYNC_ARTICLE_FULL_CONTENT_REFERENCE_KIND }
                ?: return null
        val manifest = database.syncBlobDao().findManifest(reference.hash) ?: return null
        val bytes = syncBlobStore.readVerified(reference.hash)
        if (bytes != null && bytes.size.toLong() == manifest.totalBytes) {
            blobState.markReadyVerified(reference.hash, bytes.size.toLong())
            val content = bytes.toString(Charsets.UTF_8)
            if (content.isNotBlank() && writeContentToCacheRaw(accountId, content, articleId)) {
                return Result.success(content)
            }
        }
        if (manifest.availabilityState == SyncBlobAvailabilityState.READY.name) {
            blobState.markMissing(reference.hash)
        }
        return Result.failure(
            FullContentException(
                reason = FullContentFailureReason.SYNC_PENDING,
                message = "Synced full content metadata is available but Blob bytes are not ready",
            )
        )
    }

    suspend fun deleteCacheFor(articleId: String): Boolean {
        val accountId = accountService.getCurrentAccountId()
        val deleted = withContext(ioDispatcher) {
            runCatching {
                    val file = cacheDirFor(accountId).resolve(getFileNameFor(articleId))
                    if (!file.exists()) return@runCatching false
                    return@runCatching file.delete()
                }
                .fold(onSuccess = { true }, onFailure = { false })
        }
        syncMutations.markArticleFullContentEvicted(accountId, articleId)
        return deleted
    }

    suspend fun clearCache(): Boolean {
        val accountId = accountService.getCurrentAccountId()
        val cleared = withContext(ioDispatcher) {
            runCatching {
                    return@withContext cacheDirFor(accountId).deleteRecursively()
                }
                .fold(onSuccess = { true }, onFailure = { false })
        }
        if (cleared) syncMutations.markAllArticleFullContentEvicted(accountId)
        return cleared
    }

    private companion object {
        /** 全文清洗规则变化时递增，使旧缓存自动失效并重新抓取。 */
        const val CACHE_VERSION = 4
    }
}
