package me.ash.reader.infrastructure.json

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class JsonSourceHelperTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: JsonRuleRepository
    private lateinit var helper: JsonSourceHelper

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = mock()
        whenever(repository.findRules(any())).thenReturn(emptyList())
        whenever(repository.resolveEndpoint(any(), any())).thenAnswer { invocation ->
            URI(invocation.getArgument<String>(0))
                .resolve(invocation.getArgument<String>(1))
                .toString()
        }
        helper = JsonSourceHelper(
            ruleRepository = repository,
            parser = JsonArticleParser(),
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `should automatically detect wordpress installed under subdirectory`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [{
                      "id": 7,
                      "date_gmt": "2026-08-03T08:00:00",
                      "link": "${server.url("/article/7")}",
                      "title": {"rendered": "WordPress article"},
                      "excerpt": {"rendered": "Article summary"},
                      "content": {"rendered": "<p>Full WordPress article body</p>"}
                    }]
                    """.trimIndent()
                )
        )

        val result = helper.probe(server.url("/news/").toString())

        assertNotNull(result)
        assertEquals("WordPress article", result!!.feed.entries.single().title)
        assertEquals("<p>Full WordPress article body</p>", result.feed.entries.single().description.value)
        assertEquals(
            "/news/wp-json/wp/v2/posts?_embed=1&per_page=30",
            server.takeRequest().path,
        )
    }

    @Test
    fun `should fall back to root wordpress installation`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [{
                      "id": 8,
                      "date_gmt": "2026-08-03T09:00:00",
                      "link": "${server.url("/article/8")}",
                      "title": {"rendered": "Root WordPress article"},
                      "excerpt": {"rendered": "Article summary"},
                      "content": {"rendered": "<p>Root WordPress article body</p>"}
                    }]
                    """.trimIndent()
                )
        )

        val result = helper.probe(server.url("/news/").toString())

        assertNotNull(result)
        assertEquals("/news/wp-json/wp/v2/posts?_embed=1&per_page=30", server.takeRequest().path)
        assertEquals("/wp-json/wp/v2/posts?_embed=1&per_page=30", server.takeRequest().path)
    }

    @Test
    fun `should not misidentify invalid wordpress response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not json</html>"))

        assertNull(helper.probe(server.url("/news").toString()))
    }
}
