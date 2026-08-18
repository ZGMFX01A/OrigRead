package me.ash.reader.infrastructure.website

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 可选的真实网站静态解析冒烟测试。
 * 只有脚本显式提供站点目录时才执行，避免日常单测受外网波动影响。
 */
class LiveWebsiteStaticValidationTest {
    private val tempDir = Files.createTempDirectory("origread-live-static").toFile()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `validate configured live websites`() = runBlocking {
        val catalogPath = System.getenv(ENV_CATALOG_PATH).orEmpty()
        assumeTrue("未配置真实网站目录，跳过外网冒烟测试", catalogPath.isNotBlank())

        val catalog = json.decodeFromString<LiveWebsiteCatalog>(File(catalogPath).readText())
        val cases = catalog.sites.filter { "static" in it.modes }
        assumeTrue("目录中没有静态测试站点", cases.isNotEmpty())

        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir)
        val articleWebSessionManager = mock<ArticleWebSessionManager>()
        whenever(articleWebSessionManager.httpUserAgent)
            .thenReturn("Mozilla/5.0 Chrome/151.0 Mobile Safari/537.36")
        whenever(articleWebSessionManager.desktopHttpUserAgent)
            .thenReturn(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
            )
        val helper = WebsiteHelper(
            okHttpClient = liveOkHttpClient(),
            ruleRepository = WebsiteRuleRepository(context),
            preferenceRepository = WebsiteParsePreferenceRepository(context),
            dynamicHtmlRenderer = mock(),
            articleWebSessionManager = articleWebSessionManager,
            ioDispatcher = Dispatchers.IO,
        )

        val results = cases.map { case -> validateCase(helper, case) }
        writeReport(results)

        val strict = System.getenv(ENV_STRICT).toBoolean()
        val requiredFailures = results.filter { !it.success && it.required }
        if (strict && requiredFailures.isNotEmpty()) {
            fail(requiredFailures.joinToString(prefix = "真实网站静态验证失败：", separator = "；") {
                "${it.name}=${it.error ?: "文章数不足"}"
            })
        }
    }

    /** 对单个真实站点执行全部网页候选，并保留最高分结果的诊断信息。 */
    private suspend fun validateCase(
        helper: WebsiteHelper,
        case: LiveWebsiteCase,
    ): LiveWebsiteResult {
        val startedAt = System.nanoTime()
        var latestResult: LiveWebsiteResult? = null
        repeat(MAX_VALIDATION_ATTEMPTS) { index ->
            val attempt = index + 1
            val result = validateCaseOnce(helper, case, startedAt, attempt)
            latestResult = result
            if (result.success) return result
            if (attempt < MAX_VALIDATION_ATTEMPTS) {
                delay(RETRY_DELAY_MS * attempt)
            }
        }
        return requireNotNull(latestResult)
    }

    /** 单次真实站点请求；外层只在未满足预期时进行一次有限重试。 */
    private suspend fun validateCaseOnce(
        helper: WebsiteHelper,
        case: LiveWebsiteCase,
        startedAt: Long,
        attempt: Int,
    ): LiveWebsiteResult {
        return runCatching {
            val feed = Feed(
                id = "live-static:${case.id}",
                name = case.name,
                url = case.url,
                groupId = "live-validation",
                accountId = 0,
                sourceType = SourceType.WEBSITE,
            )
            val candidates = helper.evaluateCandidates(feed, Date())
            val best = candidates
                .filter { it.diagnostics.accepted }
                .maxByOrNull { it.diagnostics.rankingScore }
            val count = best?.articles?.size ?: 0
            val expectedLinkRegex = case.expectedLinkRegex?.let(::Regex)
            val forbiddenTitleRegex = case.forbiddenTitleRegex?.let(::Regex)
            val expectedLinkMatches = best?.articles?.count { article ->
                expectedLinkRegex == null || expectedLinkRegex.matches(article.link)
            } ?: 0
            val expectedLinkRatio = if (count == 0) 0.0 else expectedLinkMatches.toDouble() / count
            val forbiddenTitleMatches = best?.articles?.count { article ->
                forbiddenTitleRegex?.containsMatchIn(article.title) == true
            } ?: 0
            val qualityErrors = buildList {
                if (best == null) {
                    add(candidates.flatMap { it.diagnostics.reasons }.distinct().joinToString().ifBlank {
                        "没有通过健康检查的候选"
                    })
                } else {
                    if (count < case.minArticles) {
                        add("文章数 $count 小于最低要求 ${case.minArticles}")
                    }
                    if (expectedLinkRegex != null && expectedLinkRatio < case.minExpectedLinkRatio) {
                        add(
                            "文章 URL 命中率 ${"%.2f".format(expectedLinkRatio)} " +
                                "低于要求 ${"%.2f".format(case.minExpectedLinkRatio)}"
                        )
                    }
                    if (forbiddenTitleMatches > 0) {
                        add("命中 $forbiddenTitleMatches 个禁止标题")
                    }
                }
            }
            val actualSuccess = best != null && qualityErrors.isEmpty()
            val expectationMet = actualSuccess == case.expectedSuccess
            val expectationError = when {
                expectationMet -> null
                case.expectedSuccess -> qualityErrors.joinToString("；").takeIf(String::isNotBlank)
                else -> "站点原本标记为预期不支持，但当前已经成功解析，请更新站点目录"
            }
            LiveWebsiteResult(
                id = case.id,
                name = case.name,
                url = case.url,
                structure = case.structure,
                mode = "static",
                required = case.required,
                success = expectationMet,
                actualSuccess = actualSuccess,
                expectedSuccess = case.expectedSuccess,
                articleCount = count,
                minArticles = case.minArticles,
                expectedLinkMatches = expectedLinkMatches,
                expectedLinkRatio = expectedLinkRatio,
                minExpectedLinkRatio = case.minExpectedLinkRatio,
                forbiddenTitleMatches = forbiddenTitleMatches,
                ruleId = best?.rule?.id,
                ruleName = best?.rule?.name,
                score = best?.diagnostics?.score,
                regionScore = best?.diagnostics?.regionScore,
                historyScore = best?.diagnostics?.historyScore,
                sampleTitles = best?.articles?.take(3)?.map { it.title }.orEmpty(),
                sampleLinks = best?.articles?.take(3)?.map { it.link }.orEmpty(),
                attempts = attempt,
                elapsedMs = elapsedMillis(startedAt),
                error = expectationError
                    ?: qualityErrors.joinToString("；").takeIf(String::isNotBlank),
            )
        }.getOrElse { error ->
            LiveWebsiteResult(
                id = case.id,
                name = case.name,
                url = case.url,
                structure = case.structure,
                mode = "static",
                required = case.required,
                success = false,
                actualSuccess = false,
                expectedSuccess = case.expectedSuccess,
                articleCount = 0,
                minArticles = case.minArticles,
                expectedLinkMatches = 0,
                expectedLinkRatio = 0.0,
                minExpectedLinkRatio = case.minExpectedLinkRatio,
                forbiddenTitleMatches = 0,
                attempts = attempt,
                elapsedMs = elapsedMillis(startedAt),
                error = "${error::class.simpleName}: ${error.message.orEmpty()}".trim(),
            )
        }
    }

    /** 外网测试使用独立短超时客户端，不复用应用同步任务的长期状态。 */
    private fun liveOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    private fun writeReport(results: List<LiveWebsiteResult>) {
        val reportPath = System.getenv(ENV_REPORT_PATH).orEmpty()
        if (reportPath.isBlank()) return
        File(reportPath).apply {
            parentFile?.mkdirs()
            writeText(json.encodeToString(LiveWebsiteReport(mode = "static", results = results)))
        }
    }

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    companion object {
        private const val ENV_CATALOG_PATH = "ORIGREAD_LIVE_SITES_FILE"
        private const val ENV_REPORT_PATH = "ORIGREAD_LIVE_STATIC_REPORT"
        private const val ENV_STRICT = "ORIGREAD_LIVE_STRICT"
        private const val MAX_VALIDATION_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 750L
    }
}

@Serializable
private data class LiveWebsiteCatalog(
    val schemaVersion: Int = 1,
    val sites: List<LiveWebsiteCase> = emptyList(),
)

@Serializable
private data class LiveWebsiteCase(
    val id: String,
    val name: String,
    val url: String,
    val structure: String,
    val modes: List<String>,
    val minArticles: Int = 3,
    val expectedLinkRegex: String? = null,
    val forbiddenTitleRegex: String? = null,
    val minExpectedLinkRatio: Double = 0.8,
    val expectedSuccess: Boolean = true,
    val required: Boolean = false,
)

@Serializable
private data class LiveWebsiteReport(
    val mode: String,
    val generatedAt: Long = System.currentTimeMillis(),
    val results: List<LiveWebsiteResult>,
)

@Serializable
private data class LiveWebsiteResult(
    val id: String,
    val name: String,
    val url: String,
    val structure: String,
    val mode: String,
    val required: Boolean,
    val success: Boolean,
    val actualSuccess: Boolean,
    val expectedSuccess: Boolean,
    val articleCount: Int,
    val minArticles: Int,
    val expectedLinkMatches: Int,
    val expectedLinkRatio: Double,
    val minExpectedLinkRatio: Double,
    val forbiddenTitleMatches: Int,
    val ruleId: String? = null,
    val ruleName: String? = null,
    val score: Int? = null,
    val regionScore: Int? = null,
    val historyScore: Int? = null,
    val sampleTitles: List<String> = emptyList(),
    val sampleLinks: List<String> = emptyList(),
    val attempts: Int = 1,
    val elapsedMs: Long,
    val error: String? = null,
)
