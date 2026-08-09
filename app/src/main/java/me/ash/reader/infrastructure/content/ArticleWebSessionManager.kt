package me.ash.reader.infrastructure.content

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文章网页会话桥接层。
 *
 * RSS/网站同步仍使用 OrigRead 自己的网络身份；只有“打开文章正文”这条链使用浏览器风格 UA。
 * 正文 WebView 与静态正文请求共用 WebView Cookie，但不把网页登录状态扩散到订阅同步链。
 */
@Singleton
class ArticleWebSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * 仅供 OkHttp 正文请求使用的浏览器风格 UA。
     *
     * 版本号取自设备 WebView 的真实 Chromium 内核，避免硬编码过期版本。
     */
    val httpUserAgent: String by lazy {
        val raw =
            runCatching { WebSettings.getDefaultUserAgent(context) }
                .getOrElse { System.getProperty("http.agent").orEmpty() }
        BrowserUserAgentPolicy.normalize(raw)
    }

    /** 读取 WebView 当前对该地址可见的 Cookie；不落日志、不复制到额外持久化存储。 */
    fun cookieHeader(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    fun flushCookies() {
        CookieManager.getInstance().flush()
    }
}

/** 与 Android WebView 生命周期无关的 Chrome UA 生成策略，便于 JVM 回归。 */
internal object BrowserUserAgentPolicy {
    private val chromeVersionRegex = Regex("\\bChrome/([0-9.]+)", RegexOption.IGNORE_CASE)

    fun normalize(rawUserAgent: String): String {
        // Android WebView 默认 UA 自带当前 Chromium 版本，但也带有 `wv`、设备型号等 WebView 特征。
        // 这里只借用 Chromium 版本号，正文请求统一使用 Chrome UA Reduction 后的标准 Android 形态。
        chromeVersionRegex.find(rawUserAgent)?.groupValues?.getOrNull(1)?.let { chromeVersion ->
            return "Mozilla/5.0 (Linux; Android 10; K) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chromeVersion Mobile Safari/537.36"
        }

        // 极少数系统拿不到 Chromium 版本时再退回旧的 WebView 特征清理，避免返回空 UA。
        val normalized =
            rawUserAgent
                .replace(Regex(";\\s*wv(?=\\))", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\bVersion/4\\.0\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
        return normalized.ifBlank { rawUserAgent.trim() }
    }

}
