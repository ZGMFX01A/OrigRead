package me.ash.reader.infrastructure.rss

import android.content.Context
import com.rometools.rome.feed.synd.SyndEnclosureImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.SourceType
import me.ash.reader.infrastructure.content.ContentExtractionService
import me.ash.reader.infrastructure.content.DynamicArticleContentService
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import okhttp3.OkHttpClient
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.mock

internal const val enclosureUrlString1: String = "https://example.com/enclosure.jpg"
internal const val enclosureUrlString2: String = "https://github.blog/wp-content/uploads/2024/03/github_copilot_header.png"
internal const val imageUrlString: String = "https://example.com/image.jpg"
internal const val enclosureHtmlCase1: String = """
        <enclosure url="$enclosureUrlString1" type="image/jpeg"/>
        <img src="$imageUrlString"/>
    """
internal const val enclosureHtmlCase2: String = """
        <img src="$imageUrlString"/>
        <enclosure url="$enclosureUrlString1" type="image/jpeg"/>
        <img src="$imageUrlString"/> 
    """
internal const val enclosureHtmlCase3: String = """
        <img src="$imageUrlString"/>
        <enclosure url="$enclosureUrlString2" type="image/png"/>
        <img src="$imageUrlString"/> 
    """
internal const val imageHtmlCase1: String = """
        <img src="$enclosureUrlString1"/>
        <img src="$imageUrlString"/> 
    """
internal const val imageHtmlCase2: String = """
        <img src="$imageUrlString"/> 
        <img src="$enclosureUrlString1"/> 
        <img src="$enclosureUrlString1"/> 
    """

@RunWith(MockitoJUnitRunner::class)
class RssHelperTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockIODispatcher: CoroutineDispatcher

    @Mock
    private lateinit var mockOkHttpClient: OkHttpClient

    @Mock
    private lateinit var mockContentExtractionService: ContentExtractionService

    @Mock
    private lateinit var mockDynamicArticleContentService: DynamicArticleContentService

    @Mock
    private lateinit var mockArticleWebSessionManager: ArticleWebSessionManager

    private lateinit var rssHelper: RssHelper

    @Before
    fun setUp() {
        mockContext = mock<Context> { }
        mockIODispatcher = mock<CoroutineDispatcher> {}
        mockOkHttpClient = mock<OkHttpClient> {}
        mockContentExtractionService = mock<ContentExtractionService> {}
        mockDynamicArticleContentService = mock<DynamicArticleContentService> {}
        mockArticleWebSessionManager = mock<ArticleWebSessionManager> {}
        rssHelper =
            RssHelper(
                mockContext,
                mockIODispatcher,
                mockOkHttpClient,
                mockContentExtractionService,
                mockDynamicArticleContentService,
                mockArticleWebSessionManager,
            )
    }

    @Test
    fun testFindThumbnail() {
        Assert.assertNull(rssHelper.findThumbnail(""))
        Assert.assertNull(rssHelper.findThumbnail(" "))
        Assert.assertNull(rssHelper.findThumbnail(null))
        Assert.assertEquals(enclosureUrlString1, rssHelper.findThumbnail(enclosureHtmlCase1))
        Assert.assertEquals(enclosureUrlString1, rssHelper.findThumbnail(enclosureHtmlCase2))
        Assert.assertEquals(enclosureUrlString2, rssHelper.findThumbnail(enclosureHtmlCase3))
        Assert.assertEquals(enclosureUrlString1, rssHelper.findThumbnail(imageHtmlCase1))
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(imageHtmlCase2))
    }

    @Test
    fun testEnclosureNoFilenameExtension() {
        val case = """
            <enclosure url="$imageUrlString" type="image/jpeg" length="0"/>
        """
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(case))
    }

    @Test
    fun `audio podcast enclosure is not used as article thumbnail`() {
        val entry = SyndEntryImpl().apply {
            enclosures = listOf(
                SyndEnclosureImpl().apply {
                    url = "https://files.example.com/episode.mp3"
                    type = "audio/mp3"
                }
            )
        }

        Assert.assertNull(rssHelper.findThumbnail(entry))
    }

    @Test
    fun `image enclosure is still used as article thumbnail`() {
        val entry = SyndEntryImpl().apply {
            enclosures = listOf(
                SyndEnclosureImpl().apply {
                    url = imageUrlString
                    type = "image/jpeg"
                }
            )
        }

        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(entry))
    }

    @Test
    fun `audio enclosure in raw content falls through to real img tag`() {
        val case = """
            <enclosure url="https://files.example.com/episode.mp3" type="audio/mp3" length="123"/>
            <img src="$imageUrlString"/>
        """

        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(case))
    }

    @Test
    fun testMediaNamespaceThumbnailInRSS20() {
        val case = """
            <enclosure url="$imageUrlString" type="image/jpeg" length="0"/>
        """
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(case))
    }

    @Test
    fun `html entity in image proxy query is decoded before storing thumbnail`() {
        val case = """
            <p>
                <img src="https://wechat2rss.bestblogs.dev/img-proxy/?k=1bf25fda&amp;u=https%3A%2F%2Fmmbiz.qpic.cn%2Fcover.jpg%3Fwx_fmt%3Djpeg"/>
            </p>
        """

        Assert.assertEquals(
            "https://wechat2rss.bestblogs.dev/img-proxy/?k=1bf25fda&u=https%3A%2F%2Fmmbiz.qpic.cn%2Fcover.jpg%3Fwx_fmt%3Djpeg",
            rssHelper.findThumbnail(case),
        )
    }

    @Test
    fun `large feed conversion keeps all 705 entries in original order`() = runBlocking {
        val feed =
            Feed(
                id = "1\$large-feed",
                name = "ATP",
                url = "https://example.com/feed.xml",
                groupId = "1\$group",
                accountId = 1,
                sourceType = SourceType.RSS,
            )
        val entries =
            (0 until 705).map { index ->
                SyndEntryImpl().apply {
                    link = "https://example.com/article/$index"
                    description = com.rometools.rome.feed.synd.SyndContentImpl().apply {
                        value = "<p>Body $index</p>"
                    }
                }
            }

        val articles = rssHelper.buildArticlesFromSyndEntries(feed, 1, entries)

        Assert.assertEquals(705, articles.size)
        Assert.assertEquals(entries.map { it.link }, articles.map { it.link })
    }
}
