package me.ash.reader.infrastructure.share

import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.translation.SecureSecretStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class NotionShareConfiguration(
    val tokenConfigured: Boolean = false,
    val tokenLength: Int = 0,
)

internal class NotionShareInProgressException : IllegalStateException("Notion 分享正在进行")

/** 使用用户个人访问令牌创建工作区私有页面；Token 只进入 Android Keystore 加密存储。 */
@Singleton
class NotionShareRepository @Inject constructor(
    private val secretStore: SecureSecretStore,
    private val httpClient: OkHttpClient,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val _configuration = MutableStateFlow(readConfiguration())
    val configuration: StateFlow<NotionShareConfiguration> = _configuration.asStateFlow()
    internal var apiBaseUrl: String = API_BASE_URL
    private val requestGuard = NotionShareRequestGuard()
    private val _shareInProgress = MutableStateFlow(false)
    val shareInProgress: StateFlow<Boolean> = _shareInProgress.asStateFlow()
    private val pendingPages = mutableMapOf<String, PendingNotionPage>()
    private val pendingPagesLock = Any()

    fun saveConfiguration(token: String) {
        val trimmedToken = token.trim()
        if (trimmedToken.isNotBlank()) secretStore.put(NOTION_TOKEN_KEY, trimmedToken)
        _configuration.value = readConfiguration()
    }

    suspend fun share(title: String?, payload: ReadingSharePayload): Result<String> {
        if (!requestGuard.tryAcquire()) return Result.failure(NotionShareInProgressException())
        _shareInProgress.value = true
        val result = CompletableDeferred<Result<String>>()
        applicationScope.launch {
            try {
                val shareResult = withContext(Dispatchers.IO) {
                    try {
                        Result.success(shareInternal(title, payload))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
                result.complete(shareResult)
            } catch (cancellation: CancellationException) {
                result.completeExceptionally(cancellation)
                throw cancellation
            } finally {
                _shareInProgress.value = false
                requestGuard.release()
            }
        }
        return result.await()
    }

    private fun shareInternal(title: String?, payload: ReadingSharePayload): String {
        val token = secretStore.get(NOTION_TOKEN_KEY).trim()
        require(token.isNotBlank()) { "请先配置 Notion 个人访问令牌" }

        val pageTitle = title.orEmpty().trim().ifBlank { "OrigRead" }
        val blocks = NotionBlockBuilder.fromHtml(payload.html)
        val chunks = blocks.chunked(MAX_BLOCKS_PER_REQUEST)
        val fingerprint = createFingerprint(pageTitle, payload)
        val pending = findPendingPage(fingerprint)
            ?: createPendingPage(fingerprint, pageTitle, token)

        while (pending.nextChunkIndex < chunks.size) {
            val chunk = chunks[pending.nextChunkIndex]
            val children = JSONArray().apply { chunk.forEach(::put) }
            requestJson(
                method = "PATCH",
                path = "/v1/blocks/${pending.pageId}/children",
                token = token,
                body = JSONObject().put("children", children),
            )
            markChunkUploaded(pending, pending.nextChunkIndex + 1)
        }

        clearPendingPage(pending)
        return pending.pageUrl
    }

    private fun createPendingPage(
        fingerprint: String,
        title: String,
        token: String,
    ): PendingNotionPage {
        val page = requestJson(
            method = "POST",
            path = "/v1/pages",
            token = token,
            body = createPageBody(title),
        )
        val pageId = page.optString("id").takeIf(String::isNotBlank)
            ?: error("Notion 未返回新页面 ID")
        val pending = PendingNotionPage(
            fingerprint = fingerprint,
            pageId = pageId,
            pageUrl = page.optString("url").ifBlank { "https://www.notion.so/$pageId" },
            createdAtMillis = System.currentTimeMillis(),
        )
        synchronized(pendingPagesLock) {
            pendingPages[fingerprint] = pending
        }
        return pending
    }

    private fun findPendingPage(fingerprint: String): PendingNotionPage? =
        synchronized(pendingPagesLock) {
            val now = System.currentTimeMillis()
            pendingPages.entries.removeIf { now - it.value.createdAtMillis > PENDING_PAGE_TTL_MILLIS }
            pendingPages[fingerprint]
        }

    private fun markChunkUploaded(pending: PendingNotionPage, nextChunkIndex: Int) {
        synchronized(pendingPagesLock) {
            if (pendingPages[pending.fingerprint]?.pageId == pending.pageId) {
                pending.nextChunkIndex = nextChunkIndex
            }
        }
    }

    private fun clearPendingPage(pending: PendingNotionPage) {
        synchronized(pendingPagesLock) {
            if (pendingPages[pending.fingerprint]?.pageId == pending.pageId) {
                pendingPages.remove(pending.fingerprint)
            }
        }
    }

    private fun createFingerprint(title: String, payload: ReadingSharePayload): String {
        val input = "$title\u0000${payload.html}".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private data class PendingNotionPage(
        val fingerprint: String,
        val pageId: String,
        val pageUrl: String,
        val createdAtMillis: Long,
        var nextChunkIndex: Int = 0,
    )

    private fun createPageBody(title: String): JSONObject =
        JSONObject().apply {
            // PAT 可以省略 parent，在工作区根目录创建私有页面。
            put(
                "properties",
                JSONObject().put(
                    "title",
                    JSONObject().put(
                        "title",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "text")
                                .put(
                                    "text",
                                    JSONObject().put("content", title.take(MAX_TITLE_LENGTH)),
                                ),
                        ),
                    ),
                ),
            )
        }

    private fun requestJson(
        method: String,
        path: String,
        token: String,
        body: JSONObject,
    ): JSONObject {
        val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)
        val requestBuilder = Request.Builder()
            .url(apiBaseUrl + path)
            .header("Authorization", "Bearer $token")
            .header("Notion-Version", NOTION_VERSION)
            .header("Content-Type", "application/json")
        when (method) {
            "POST" -> requestBuilder.post(requestBody)
            "PATCH" -> requestBuilder.patch(requestBody)
            else -> error("Unsupported Notion method: $method")
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(responseText).optString("message") }
                    .getOrNull()
                    .orEmpty()
                    .ifBlank { "HTTP ${response.code}" }
                throw IOException("Notion：$message")
            }
            return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        }
    }

    private fun readConfiguration(): NotionShareConfiguration {
        val token = secretStore.get(NOTION_TOKEN_KEY)
        return NotionShareConfiguration(
            tokenConfigured = token.isNotBlank(),
            tokenLength = token.length,
        )
    }

    companion object {
        private const val API_BASE_URL = "https://api.notion.com"
        private const val NOTION_VERSION = "2026-03-11"
        private const val NOTION_TOKEN_KEY = "notion_personal_access_token"
        private const val MAX_BLOCKS_PER_REQUEST = 100
        private const val MAX_TITLE_LENGTH = 2000
        private const val PENDING_PAGE_TTL_MILLIS = 10 * 60 * 1000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
