package me.ash.reader.infrastructure.content

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import me.ash.reader.infrastructure.website.DynamicWebsiteHtmlRenderer
import org.jsoup.Jsoup

/**
 * 静态正文提取失败后的受限 WebView 兜底。
 * 全局只允许一个正文 WebView，防止连续阅读或并发任务造成明显内存和功耗压力。
 */
@Singleton
class DynamicArticleContentService @Inject constructor(
    private val renderer: DynamicWebsiteHtmlRenderer,
    private val contentExtractionService: ContentExtractionService,
) {
    private val renderSemaphore = Semaphore(permits = 1)

    /** 仅在策略判断页面可能依赖 JavaScript 时执行动态正文提取。 */
    suspend fun extract(
        url: String,
        expectedTitle: String?,
        staticHtml: String,
        staticFailureReason: FullContentFailureReason,
        allowRestrictedFallback: Boolean = false,
    ): ExtractedContent? {
        if (
            !DynamicArticleContentPolicy.shouldAttempt(
                html = staticHtml,
                reason = staticFailureReason,
                enabled = true,
                allowRestrictedFallback = allowRestrictedFallback,
            )
        ) {
            return null
        }

        return withTimeoutOrNull(TOTAL_FALLBACK_TIMEOUT_MILLIS) {
            renderSemaphore.withPermit {
                val rendered = renderer.render(url)
                // 若浏览器渲染后仍返回限制页，不能把验证提示交给 Readability 当成正文。
                if (
                    FullContentFailureClassifier.classifyHtml(rendered.html) ==
                        FullContentFailureReason.ACCESS_RESTRICTED
                ) {
                    return@withPermit null
                }
                contentExtractionService.extract(
                    html = rendered.html,
                    sourceUrl = rendered.finalUrl,
                    expectedTitle = expectedTitle,
                )
            }
        }
    }

    private companion object {
        /** 比渲染器自身 15 秒略宽，只为包含排队和正文提取时间。 */
        const val TOTAL_FALLBACK_TIMEOUT_MILLIS = 18_000L
    }
}

/** 与 Android WebView 无关的动态正文触发策略，便于 JVM 自动回归。 */
internal object DynamicArticleContentPolicy {
    private const val MAX_STATIC_VISIBLE_TEXT = 500

    fun shouldAttempt(
        html: String,
        reason: FullContentFailureReason,
        enabled: Boolean,
        allowRestrictedFallback: Boolean = false,
    ): Boolean {
        if (!enabled) return false
        if (reason == FullContentFailureReason.DYNAMIC_CONTENT) return true
        if (reason == FullContentFailureReason.ACCESS_RESTRICTED) return allowRestrictedFallback
        if (reason != FullContentFailureReason.NO_CONTENT) return false

        val document = runCatching { Jsoup.parse(html) }.getOrNull() ?: return false
        val visibleTextLength = document.body()?.text()?.trim()?.length ?: 0
        if (visibleTextLength > MAX_STATIC_VISIBLE_TEXT) return false

        val normalized = html.lowercase()
        val hasHydrationRoot = DYNAMIC_ROOT_MARKERS.any(normalized::contains)
        val hasExecutableScripts = document.select("script[src], script:not([type=application/ld+json])").isNotEmpty()
        return hasHydrationRoot || hasExecutableScripts
    }

    private val DYNAMIC_ROOT_MARKERS = listOf(
        "id=\"__next\"",
        "id='__next'",
        "id=\"__nuxt\"",
        "id='__nuxt'",
        "data-reactroot",
        "data-server-rendered",
        "ng-version=",
        "id=\"app\"",
        "id='app'",
    )
}
