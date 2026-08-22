package me.ash.reader.infrastructure.share

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.translation.SecureSecretStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class NotionShareRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: NotionShareRepository
    private lateinit var applicationScope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val secretStore = mock<SecureSecretStore>()
        whenever(secretStore.get(any())).thenReturn("test-token")
        repository = NotionShareRepository(secretStore, OkHttpClient(), applicationScope).also {
            it.apiBaseUrl = server.url("").toString().trimEnd('/')
        }
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        server.shutdown()
    }

    @Test
    fun `resumes failed block upload on the same page instead of creating another page`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"page-1","url":"https://www.notion.so/page-1"}"""),
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"temporary failure"}"""),
        )

        val firstResult = repository.share("Article", payloadWithBlocks(101))

        assertFalse(firstResult.isSuccess)
        assertEquals(3, server.requestCount)

        Thread.sleep(1_700L)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))

        val secondResult = repository.share("Article", payloadWithBlocks(101))

        assertTrue(secondResult.isSuccess)
        assertEquals("https://www.notion.so/page-1", secondResult.getOrNull())
        assertEquals(4, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("PATCH", server.takeRequest().method)
        assertEquals("PATCH", server.takeRequest().method)
        assertEquals("PATCH", server.takeRequest().method)
    }

    private fun payloadWithBlocks(count: Int): ReadingSharePayload =
        ReadingSharePayload(
            html = buildString {
                repeat(count) { index -> append("<p>Block $index</p>") }
            },
            markdown = "",
            plainText = "",
        )
}
