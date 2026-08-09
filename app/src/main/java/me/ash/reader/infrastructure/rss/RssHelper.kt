package me.ash.reader.infrastructure.rss

import android.content.Context
import android.util.Log
import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.MediaModule
import com.rometools.modules.mediarss.types.UrlReference
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndImageImpl
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.Charset
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.content.ContentExtractionService
import me.ash.reader.infrastructure.content.DynamicArticleContentService
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.content.FullContentException
import me.ash.reader.infrastructure.content.FullContentFailureClassifier
import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.ui.ext.currentAccountId
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.executeAsync
import okhttp3.internal.commonIsSuccessful
import okio.IOException
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

val enclosureRegex = """<enclosure\s+url="([^"]+)"\s+type=".*"\s*/>""".toRegex()
val imgRegex = """img.*?src=(["'])((?!data).*?)\1""".toRegex(RegexOption.DOT_MATCHES_ALL)

/** RSS 探测结果，feedUrl 为最终应保存和同步的真实 Feed 地址。 */
data class DiscoveredFeed(
    val feedUrl: String,
    val feed: SyndFeed,
    val discoveredFromPage: Boolean,
)

/** Some operations on RSS. */
class RssHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient,
    private val contentExtractionService: ContentExtractionService,
    private val dynamicArticleContentService: DynamicArticleContentService,
    private val articleWebSessionManager: ArticleWebSessionManager,
) {

    @Throws(Exception::class)
    suspend fun searchFeed(feedLink: String): SyndFeed {
        return discoverFeed(feedLink).feed
    }

    /**
     * 优先将输入地址直接按 Feed 解析；失败后再从普通网页的 alternate 链接发现 RSS/Atom。
     * 所有候选都会实际请求并解析，避免仅凭 type 或文件后缀误判。
     */
    @Throws(Exception::class)
    suspend fun discoverFeed(inputUrl: String): DiscoveredFeed =
        withContext(ioDispatcher) {
            runCatching { parseFeedUrl(inputUrl, inputUrl) }
                .map { DiscoveredFeed(inputUrl, it, discoveredFromPage = false) }
                .getOrElse { directError ->
                    val pageResponse = response(okHttpClient, inputUrl)
                    val html = pageResponse.body.string()
                    val document = Jsoup.parse(html, inputUrl)
                    val candidates =
                        buildList {
                            addAll(
                                document
                                    .select("link[rel~=alternate][href]")
                                    .asSequence()
                                    .filter { element ->
                                        val type = element.attr("type").lowercase()
                                        type.contains("rss") ||
                                            type.contains("atom") ||
                                            type.contains("rdf") ||
                                            type.contains("xml")
                                    }
                                    .map { it.absUrl("href") }
                                    .filter { it.isNotBlank() }
                                    .toList()
                            )
                            addAll(buildCommonFeedCandidates(inputUrl))
                        }.distinct()

                    candidates.firstNotNullOfOrNull { candidateUrl ->
                        runCatching {
                            DiscoveredFeed(
                                feedUrl = candidateUrl,
                                feed = parseFeedUrl(candidateUrl, inputUrl),
                                discoveredFromPage = true,
                            )
                        }.getOrNull()
                    } ?: throw directError
                }
        }

    /** 直接解析指定 Feed，不执行网页发现；可传入短超时客户端。 */
    suspend fun parseFeedDirect(
        feedUrl: String,
        iconSourceUrl: String = feedUrl,
        client: OkHttpClient = okHttpClient,
    ): SyndFeed = withContext(ioDispatcher) {
        parseFeedUrl(feedUrl, iconSourceUrl, client)
    }

    /** 请求并解析单个 Feed 地址，站点图标优先按原始网页地址查找。 */
    private suspend fun parseFeedUrl(
        feedUrl: String,
        iconSourceUrl: String,
        client: OkHttpClient = okHttpClient,
    ): SyndFeed {
        val response = response(client, feedUrl)
        val contentType = response.header("Content-Type")
        val httpContentType =
            contentType?.let {
                if (it.contains("charset=", ignoreCase = true)) it
                else "$it; charset=UTF-8"
            } ?: "text/xml; charset=UTF-8"
        val bytes = response.body.bytes()
        return SyndFeedInput()
            .build(XmlReader(ByteArrayInputStream(bytes), httpContentType))
            .also {
                require(it.title?.isNotBlank() == true || it.entries.isNotEmpty()) {
                    "Feed 内容为空或格式无效：$feedUrl"
                }
                it.icon = SyndImageImpl()
                it.icon.link = queryRssIconLink(iconSourceUrl)
                it.icon.url = it.icon.link
            }
    }

    /**
     * 当网页没有声明 alternate Feed 时，尝试行业中最常见的同域名 Feed 路径。
     * 候选仍会经过真实 XML 解析，错误地址不会被保存。
     */
    private fun buildCommonFeedCandidates(inputUrl: String): List<String> {
        val uri = runCatching { URI(inputUrl) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme ?: return emptyList()
        val authority = uri.rawAuthority ?: return emptyList()
        val origin = "$scheme://$authority"
        return listOf(
            "$origin/feed",
            "$origin/feed/",
            "$origin/rss",
            "$origin/rss.xml",
            "$origin/atom.xml",
            "$origin/feed.xml",
            "$origin/index.xml",
        )
    }

    @Throws(Exception::class)
    suspend fun parseFullContent(
        link: String,
        title: String,
        allowDynamicFallback: Boolean = true,
    ): String {
        return withContext(ioDispatcher) {
            val uri = runCatching { URI(link) }.getOrNull()
            if (uri?.scheme !in setOf("http", "https")) {
                throw FullContentException(
                    reason = FullContentFailureReason.INVALID_URL,
                    message = "Unsupported article URL",
                )
            }

            // 微信公众号在真实浏览器中可以直接打开，但 OkHttp 即使伪装 Chrome UA，
            // TLS/HTTP2 指纹仍与 Chromium 不同，先请求一次反而可能触发文章级安全验证。
            // 前台阅读全文因此直接交给隐藏 WebView，并同步使用移动 Chrome UA 与 Client Hints。
            // 后台预取仍保持纯 HTTP，不启动 WebView。
            if (allowDynamicFallback && uri != null && isWeChatArticleUrl(uri)) {
                val dynamicContent =
                    runCatching {
                        dynamicArticleContentService.extract(
                            url = link,
                            expectedTitle = title,
                            staticHtml = "",
                            staticFailureReason = FullContentFailureReason.ACCESS_RESTRICTED,
                            allowRestrictedFallback = true,
                        )
                    }.getOrNull()
                if (dynamicContent != null) return@withContext dynamicContent.html
                throw FullContentException(
                    reason = FullContentFailureReason.ACCESS_RESTRICTED,
                    message = "WeChat article remained restricted after browser rendering",
                )
            }

            try {
                val response = articleResponse(okHttpClient, link)
                if (response.commonIsSuccessful) {
                    val responseBody = response.body
                    val charset = responseBody.contentType()?.charset()
                    val content =
                        responseBody.source().use {
                            if (charset != null) {
                                return@use it.readString(charset)
                            }

                            val peekContent = it.peek().readString(Charsets.UTF_8)

                            val charsetFromMeta =
                                runCatching {
                                        val element =
                                            Jsoup.parse(peekContent, link)
                                                .selectFirst("meta[http-equiv=content-type]")
                                        return@runCatching if (element == null) Charsets.UTF_8
                                        else {
                                            element
                                                .attr("content")
                                                .substringAfter("charset=")
                                                .removeSurrounding("\"")
                                                .lowercase()
                                                .let { Charset.forName(it) }
                                        }
                                    }
                                    .getOrDefault(Charsets.UTF_8)

                            if (charsetFromMeta == Charsets.UTF_8) {
                                peekContent
                            } else {
                                it.readString(charsetFromMeta)
                            }
                        }

                    val staticContent = contentExtractionService.extract(content, link, title)
                    if (staticContent != null) return@withContext staticContent.html

                    val staticFailureReason = FullContentFailureClassifier.classifyHtml(content)
                    val dynamicContent =
                        if (allowDynamicFallback) {
                            runCatching {
                                dynamicArticleContentService.extract(
                                    url = link,
                                    expectedTitle = title,
                                    staticHtml = content,
                                    staticFailureReason = staticFailureReason,
                                    allowRestrictedFallback =
                                        staticFailureReason == FullContentFailureReason.ACCESS_RESTRICTED,
                                )
                            }.getOrNull()
                        } else {
                            null
                        }
                    dynamicContent?.html
                        ?: throw FullContentException(
                            reason = staticFailureReason,
                            message = "No valid article content was extracted",
                        )
                } else {
                    val failureReason = FullContentFailureClassifier.classifyHttpStatus(response.code)
                    if (
                        allowDynamicFallback &&
                            failureReason == FullContentFailureReason.ACCESS_RESTRICTED
                    ) {
                        val restrictedHtml = runCatching { response.body.string() }.getOrDefault("")
                        val dynamicContent =
                            runCatching {
                                dynamicArticleContentService.extract(
                                    url = link,
                                    expectedTitle = title,
                                    staticHtml = restrictedHtml,
                                    staticFailureReason = failureReason,
                                    allowRestrictedFallback = true,
                                )
                            }.getOrNull()
                        if (dynamicContent != null) return@withContext dynamicContent.html
                    }
                    throw FullContentException(
                        reason = failureReason,
                        message = "Article request failed with HTTP ${response.code}",
                    )
                }
            } catch (failure: FullContentException) {
                throw failure
            } catch (failure: Throwable) {
                throw FullContentException(
                    reason = FullContentFailureClassifier.classifyThrowable(failure),
                    message = "Article request failed",
                    cause = failure,
                )
            }
        }
    }

    suspend fun queryRssXml(
        feed: Feed,
        latestLink: String?,
        preDate: Date = Date(),
    ): List<Article> =
        try {
            val accountId = context.currentAccountId
            val response = response(okHttpClient, feed.url)
            val contentType = response.header("Content-Type")

            val httpContentType =
                contentType?.let {
                    if (it.contains("charset=", ignoreCase = true)) it
                    else "$it; charset=UTF-8"
                } ?: "text/xml; charset=UTF-8"

            response.body.byteStream().use { inputStream ->
                SyndFeedInput()
                    .apply { isPreserveWireFeed = true }
                    .build(XmlReader(inputStream, httpContentType))
                    .entries
                    .asSequence()
                    .takeWhile { latestLink == null || latestLink != it.link }
                    .map { buildArticleFromSyndEntry(feed, accountId, it, preDate) }
                    .toList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("RLog", "queryRssXml[${feed.name}]: ${e.message}")
            listOf()
        }

    fun buildArticleFromSyndEntry(
        feed: Feed,
        accountId: Int,
        syndEntry: SyndEntry,
        preDate: Date = Date(),
    ): Article {
        val desc = syndEntry.description?.value
        val content =
            syndEntry.contents
                .takeIf { it.isNotEmpty() }
                ?.let { it.joinToString("\n") { it.value } }
        //        Log.i(
        //            "RLog",
        //            "request rss:\n" +
        //                    "name: ${feed.name}\n" +
        //                    "feedUrl: ${feed.url}\n" +
        //                    "url: ${syndEntry.link}\n" +
        //                    "title: ${syndEntry.title}\n" +
        //                    "desc: ${desc}\n" +
        //                    "content: ${content}\n"
        //        )
        return Article(
            id = accountId.spacerDollar(UUID.randomUUID().toString()),
            accountId = accountId,
            feedId = feed.id,
            date =
                (syndEntry.publishedDate ?: syndEntry.updatedDate)?.takeIf { !it.isFuture(preDate) }
                    ?: preDate,
            title = syndEntry.title.decodeHTML() ?: feed.name,
            author = syndEntry.author,
            rawDescription = content ?: desc ?: "",
            shortDescription = Readability.parseToText(desc ?: content, syndEntry.link).take(280),
            //            fullContent = content,
            img = findThumbnail(syndEntry) ?: findThumbnail(content ?: desc),
            link = syndEntry.link ?: "",
            updateAt = preDate,
        )
    }

    fun findThumbnail(syndEntry: SyndEntry): String? {
        if (syndEntry.enclosures?.firstOrNull()?.url != null) {
            return syndEntry.enclosures.first().url
        }

        val mediaModule = syndEntry.getModule(MediaModule.URI) as? MediaEntryModule
        if (mediaModule != null) {
            return findThumbnail(mediaModule)
        }

        return null
    }

    private fun findThumbnail(mediaModule: MediaEntryModule): String? {
        val candidates =
            buildList {
                    add(mediaModule.metadata)
                    addAll(mediaModule.mediaGroups.map { mediaGroup -> mediaGroup.metadata })
                    addAll(mediaModule.mediaContents.map { content -> content.metadata })
                }
                .flatMap { it.thumbnail.toList() }

        val thumbnail = candidates.firstOrNull()

        if (thumbnail != null) {
            return thumbnail.url.toString()
        } else {
            val imageMedia = mediaModule.mediaContents.firstOrNull { it.medium == "image" }
            if (imageMedia != null) {
                return (imageMedia.reference as? UrlReference)?.url.toString()
            }
        }
        return null
    }

    fun findThumbnail(text: String?): String? {
        text ?: return null
        val enclosure = enclosureRegex.find(text)?.groupValues?.get(1)
        if (enclosure?.isNotBlank() == true) {
            return enclosure
        }
        // From https://gitlab.com/spacecowboy/Feeder
        // Using negative lookahead to skip data: urls, being inline base64
        // And capturing original quote to use as ending quote
        // Base64 encoded images can be quite large - and crash database cursors
        return imgRegex.find(text)
            ?.groupValues
            ?.get(2)
            // content:encoded 中的 HTML 属性常保留 `&amp;` 等实体。
            // 若直接把正则结果存进 Article.img，查询参数会变成 `amp;u`，图片代理因此拿不到真实 URL。
            ?.let { Parser.unescapeEntities(it, false) }
            ?.takeIf { !it.startsWith("data:") }
    }

    suspend fun queryRssIconLink(feedLink: String?): String? {
        if (feedLink.isNullOrEmpty()) return null
        val iconFinder = BestIconFinder(okHttpClient)
        val domain = feedLink.extractDomain()
        return iconFinder.findBestIcon(domain ?: feedLink).also {
            Log.i("RLog", "queryRssIconByLink: get $it from $domain")
        }
    }

    suspend fun saveRssIcon(feedDao: FeedDao, feed: Feed, iconLink: String) {
        feedDao.update(feed.copy(icon = iconLink))
    }

    private suspend fun response(client: OkHttpClient, url: String): okhttp3.Response =
        client.newCall(Request.Builder().url(url).build()).executeAsync()

    /**
     * 正文网页使用浏览器风格请求头，并复用用户在内置 WebView 中已经取得的站点 Cookie。
     * Feed/XML 请求仍走普通 response()，避免把网页登录状态扩散到后台订阅同步链。
     */
    private suspend fun articleResponse(client: OkHttpClient, url: String): okhttp3.Response {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", articleWebSessionManager.httpUserAgent)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                )
                .header("Upgrade-Insecure-Requests", "1")
                .apply {
                    articleWebSessionManager.cookieHeader(url)?.let { cookie ->
                        header("Cookie", cookie)
                    }
                }
                .build()
        return client.newCall(request).executeAsync()
    }

    /** 微信公众号单篇文章前台直接使用浏览器渲染，避免 OkHttp 预请求先触发风控。 */
    private fun isWeChatArticleUrl(uri: URI): Boolean {
        val host = uri.host.orEmpty().lowercase()
        return host == WECHAT_ARTICLE_HOST || host.endsWith(".$WECHAT_ARTICLE_HOST")
    }

    private companion object {
        const val WECHAT_ARTICLE_HOST = "mp.weixin.qq.com"
    }
}
