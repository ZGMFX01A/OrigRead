package me.ash.reader.infrastructure.website

import android.util.Base64
import android.os.Bundle
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 由 scripts/run-live-website-validation.ps1 逐站调用的真机 WebView 测试。
 * 测试结果使用 Base64 JSON 输出，避免 ADB shell 对中文、空格和 URL 参数进行二次拆分。
 */
@RunWith(AndroidJUnit4::class)
class LiveWebsiteDynamicValidationTest {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    @Test
    fun validateRenderedWebsite() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val caseId = arguments.getString(ARG_CASE_ID).orEmpty().ifBlank { "dynamic-site" }
        val caseName = decodeArgument(arguments.getString(ARG_CASE_NAME_BASE64)).ifBlank { caseId }
        val url = decodeArgument(arguments.getString(ARG_URL_BASE64))
        val structure = decodeArgument(arguments.getString(ARG_STRUCTURE_BASE64))
        val minArticles = arguments.getString(ARG_MIN_ARTICLES)?.toIntOrNull()?.coerceAtLeast(1) ?: 3
        val expectedLinkRegex = decodeArgument(arguments.getString(ARG_EXPECTED_LINK_REGEX_BASE64))
            .takeIf(String::isNotBlank)
            ?.let(::Regex)
        val forbiddenTitleRegex = decodeArgument(arguments.getString(ARG_FORBIDDEN_TITLE_REGEX_BASE64))
            .takeIf(String::isNotBlank)
            ?.let(::Regex)
        val minExpectedLinkRatio = arguments.getString(ARG_MIN_EXPECTED_LINK_RATIO)
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 1.0)
            ?: 0.8
        val expectedSuccess = arguments.getString(ARG_EXPECTED_SUCCESS)?.toBooleanStrictOrNull() ?: true
        val required = arguments.getString(ARG_REQUIRED).toBoolean()
        val startedAt = System.nanoTime()

        val result = runCatching {
            require(url.startsWith("https://") || url.startsWith("http://")) {
                "未提供有效 HTTP(S) 测试地址"
            }
            val context = ApplicationProvider.getApplicationContext<Context>()
            val articleWebSessionManager = ArticleWebSessionManager(context)
            val helper = WebsiteHelper(
                okHttpClient = OkHttpClient(),
                ruleRepository = WebsiteRuleRepository(context),
                preferenceRepository = WebsiteParsePreferenceRepository(context),
                dynamicHtmlRenderer =
                    DynamicWebsiteHtmlRenderer(
                        context,
                        Dispatchers.Main.immediate,
                        articleWebSessionManager,
                    ),
                articleWebSessionManager = articleWebSessionManager,
                ioDispatcher = Dispatchers.IO,
            )
            val inspected = helper.inspectDynamic(url, Date())
            val entries = inspected.entries.orEmpty()
            val count = entries.size
            val expectedLinkMatches = entries.count { entry ->
                expectedLinkRegex == null || expectedLinkRegex.matches(entry.link.orEmpty())
            }
            val expectedLinkRatio = if (count == 0) 0.0 else expectedLinkMatches.toDouble() / count
            val forbiddenTitleMatches = entries.count { entry ->
                forbiddenTitleRegex?.containsMatchIn(entry.title.orEmpty()) == true
            }
            val qualityErrors = buildList {
                if (count < minArticles) {
                    add("文章数 $count 小于最低要求 $minArticles")
                }
                if (expectedLinkRegex != null && expectedLinkRatio < minExpectedLinkRatio) {
                    add(
                        "文章 URL 命中率 ${"%.2f".format(expectedLinkRatio)} " +
                            "低于要求 ${"%.2f".format(minExpectedLinkRatio)}"
                    )
                }
                if (forbiddenTitleMatches > 0) {
                    add("命中 $forbiddenTitleMatches 个禁止标题")
                }
            }
            val actualSuccess = qualityErrors.isEmpty()
            val expectationMet = actualSuccess == expectedSuccess
            LiveDynamicWebsiteResult(
                id = caseId,
                name = caseName,
                url = url,
                structure = structure,
                mode = "dynamic",
                required = required,
                success = expectationMet,
                actualSuccess = actualSuccess,
                expectedSuccess = expectedSuccess,
                articleCount = count,
                minArticles = minArticles,
                expectedLinkMatches = expectedLinkMatches,
                expectedLinkRatio = expectedLinkRatio,
                minExpectedLinkRatio = minExpectedLinkRatio,
                forbiddenTitleMatches = forbiddenTitleMatches,
                sampleTitles = entries.take(3).mapNotNull { it.title },
                sampleLinks = entries.take(3).mapNotNull { it.link },
                attempts = 1,
                elapsedMs = elapsedMillis(startedAt),
                error = when {
                    expectationMet -> qualityErrors.joinToString("；").takeIf(String::isNotBlank)
                    expectedSuccess -> qualityErrors.joinToString("；").takeIf(String::isNotBlank)
                    else -> "站点原本标记为预期不支持，但当前已经成功解析，请更新站点目录"
                },
            )
        }.getOrElse { error ->
            LiveDynamicWebsiteResult(
                id = caseId,
                name = caseName,
                url = url,
                structure = structure,
                mode = "dynamic",
                required = required,
                success = false,
                actualSuccess = false,
                expectedSuccess = expectedSuccess,
                articleCount = 0,
                minArticles = minArticles,
                expectedLinkMatches = 0,
                expectedLinkRatio = 0.0,
                minExpectedLinkRatio = minExpectedLinkRatio,
                forbiddenTitleMatches = 0,
                attempts = 1,
                elapsedMs = elapsedMillis(startedAt),
                error = "${error::class.simpleName}: ${error.message.orEmpty()}".trim(),
            )
        }

        printResult(result)
    }

    private fun printResult(result: LiveDynamicWebsiteResult) {
        val payload = json.encodeToString(result).toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        println("$RESULT_PREFIX$encoded")
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString("stream", "$RESULT_PREFIX$encoded\n")
            },
        )
    }

    private fun decodeArgument(encoded: String?): String {
        if (encoded.isNullOrBlank() || encoded == EMPTY_ARGUMENT) return ""
        return runCatching {
            Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    companion object {
        const val RESULT_PREFIX = "ORIGREAD_LIVE_RESULT_BASE64="
        private const val EMPTY_ARGUMENT = "-"
        private const val ARG_CASE_ID = "caseId"
        private const val ARG_CASE_NAME_BASE64 = "caseNameBase64"
        private const val ARG_URL_BASE64 = "urlBase64"
        private const val ARG_STRUCTURE_BASE64 = "structureBase64"
        private const val ARG_MIN_ARTICLES = "minArticles"
        private const val ARG_EXPECTED_LINK_REGEX_BASE64 = "expectedLinkRegexBase64"
        private const val ARG_FORBIDDEN_TITLE_REGEX_BASE64 = "forbiddenTitleRegexBase64"
        private const val ARG_MIN_EXPECTED_LINK_RATIO = "minExpectedLinkRatio"
        private const val ARG_EXPECTED_SUCCESS = "expectedSuccess"
        private const val ARG_REQUIRED = "required"
    }
}

@Serializable
private data class LiveDynamicWebsiteResult(
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
    val sampleTitles: List<String> = emptyList(),
    val sampleLinks: List<String> = emptyList(),
    val attempts: Int = 1,
    val elapsedMs: Long,
    val error: String? = null,
)
