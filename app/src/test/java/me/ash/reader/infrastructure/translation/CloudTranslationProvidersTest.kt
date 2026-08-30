package me.ash.reader.infrastructure.translation

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudTranslationProvidersTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: TranslationHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = TranslationHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `microsoft sends batch request and parses translations`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"detectedLanguage":{"language":"en"},"translations":[{"text":"你好","to":"zh-Hans"}]}]"""
            )
        )
        val provider = MicrosoftTranslationProvider(httpClient)
        val result =
            provider.translate(
                texts = listOf("Hello"),
                sourceLanguage = null,
                targetLanguage = "zh-CN",
                config =
                    TranslationRuntimeConfig(
                        endpoint = server.url("/").toString().trimEnd('/'),
                        region = "eastasia",
                        apiKey = "secret",
                    ),
            )

        assertEquals(listOf("你好"), result.texts)
        assertEquals("en", result.detectedSourceLanguage)
        val request = server.takeRequest()
        assertTrue(request.path.orEmpty().contains("api-version=3.0"))
        assertEquals("secret", request.getHeader("Ocp-Apim-Subscription-Key"))
        assertEquals("eastasia", request.getHeader("Ocp-Apim-Subscription-Region"))
    }

    @Test
    fun `deepl sends json body and parses translations`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"translations":[{"detected_source_language":"EN","text":"你好"}]}"""
            )
        )
        val provider = DeepLTranslationProvider(httpClient)
        val result =
            provider.translate(
                texts = listOf("Hello"),
                sourceLanguage = null,
                targetLanguage = "zh-CN",
                config =
                    TranslationRuntimeConfig(
                        endpoint = server.url("/v2/translate").toString(),
                        region = "",
                        apiKey = "deepl-key",
                    ),
            )

        assertEquals(listOf("你好"), result.texts)
        val request = server.takeRequest()
        assertEquals("DeepL-Auth-Key deepl-key", request.getHeader("Authorization"))
        assertEquals("ZH-HANS", JSONObject(request.body.readUtf8()).getString("target_lang"))
    }

    @Test
    fun `deepl selects official endpoint from key type`() {
        assertEquals(
            "https://api-free.deepl.com/v2/translate",
            resolveDeepLEndpoint("https://api.deepl.com/v2/translate", "free-key:fx"),
        )
        assertEquals(
            "https://api.deepl.com/v2/translate",
            resolveDeepLEndpoint("https://api-free.deepl.com/v2/translate", "pro-key"),
        )
        assertEquals(
            "https://api-jp.deepl.com/v2/translate",
            resolveDeepLEndpoint("https://api-jp.deepl.com", "pro-key"),
        )
        assertEquals(
            "https://api-free.deepl.com/v2/usage",
            resolveDeepLUsageEndpoint(
                "https://api.deepl.com/v2/translate",
                "free-key:fx",
            ),
        )
    }

    @Test
    fun `deepl usage request parses current quota`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"character_count":180118,"character_limit":1250000}"""
            )
        )
        val provider = DeepLTranslationProvider(httpClient)
        val usage =
            provider.getUsage(
                TranslationRuntimeConfig(
                    endpoint = server.url("/v2/translate").toString(),
                    region = "",
                    apiKey = "deepl-key",
                )
            )

        assertEquals(180118L, usage.characterCount)
        assertEquals(1250000L, usage.characterLimit)
        assertEquals(1069882L, usage.remainingCharacters)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v2/usage", request.path)
        assertEquals("DeepL-Auth-Key deepl-key", request.getHeader("Authorization"))
    }

    @Test
    fun `deepl exposes quota response instead of generic network failure`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(456)
                .setBody("""{"message":"Quota exceeded"}"""),
        )
        val provider = DeepLTranslationProvider(httpClient)
        try {
            provider.translate(
                texts = listOf("Hello"),
                sourceLanguage = null,
                targetLanguage = "zh-CN",
                config =
                    TranslationRuntimeConfig(
                        endpoint = server.url("/v2/translate").toString(),
                        region = "",
                        apiKey = "deepl-key",
                    ),
            )
            fail("Expected TranslationException")
        } catch (error: TranslationException) {
            assertEquals(TranslationErrorCode.RATE_LIMITED, error.code)
            assertTrue(error.message.orEmpty().contains("HTTP 456"))
            assertTrue(error.message.orEmpty().contains("Quota exceeded"))
        }
    }

    @Test
    fun `google cloud decodes html entities in translated text`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"translations":[{"detectedSourceLanguage":"en","translatedText":"A &amp; B"}]}}"""
            )
        )
        val provider = GoogleCloudTranslationProvider(httpClient)
        val result =
            provider.translate(
                texts = listOf("A and B"),
                sourceLanguage = null,
                targetLanguage = "zh-CN",
                config =
                    TranslationRuntimeConfig(
                        endpoint = server.url("/language/translate/v2").toString(),
                        region = "",
                        apiKey = "google-key",
                    ),
            )

        assertEquals(listOf("A & B"), result.texts)
        val request = server.takeRequest()
        assertTrue(request.path.orEmpty().contains("key=google-key"))
        assertEquals("zh-CN", JSONObject(request.body.readUtf8()).getString("target"))
    }

    @Test
    fun `dlx accepts data response and uses translate endpoint`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":"你好"}"""))
        val provider = DlxTranslationProvider(httpClient)
        val result =
            provider.translate(
                texts = listOf("Hello"),
                sourceLanguage = null,
                targetLanguage = "zh-CN",
                config =
                    TranslationRuntimeConfig(
                        endpoint = server.url("/").toString().trimEnd('/'),
                        region = "",
                        apiKey = "optional-token",
                    ),
            )

        assertEquals(listOf("你好"), result.texts)
        val request = server.takeRequest()
        assertTrue(request.path.orEmpty().endsWith("/translate"))
        assertEquals("Bearer optional-token", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("auto", body.getString("source_lang"))
        assertEquals("ZH", body.getString("target_lang"))
    }

    @Test
    fun `dlx preserves token query while appending translate path`() {
        assertEquals(
            "https://example.com/translate?token=secret",
            resolveDlxEndpoint("https://example.com?token=secret"),
        )
        assertEquals(
            "https://example.com/custom/translate?token=secret",
            resolveDlxEndpoint("https://example.com/custom?token=secret"),
        )
        assertEquals(
            "https://example.com/v2/translate?token=secret",
            resolveDlxEndpoint("https://example.com/v2/translate?token=secret"),
        )
    }

    @Test
    fun `dlx accepts translations array response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                JSONObject()
                    .put("translations", JSONArray().put(JSONObject().put("text", "你好")))
                    .toString()
            )
        )
        val provider = DlxTranslationProvider(httpClient)
        val result =
            provider.translate(
                listOf("Hello"),
                null,
                "zh-CN",
                TranslationRuntimeConfig(
                    endpoint = server.url("/translate").toString(),
                    region = "",
                    apiKey = "",
                ),
            )

        assertEquals("你好", result.texts.single())
    }

    @Test
    fun `translation cancellation cancels blocked provider call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val provider = MicrosoftTranslationProvider(httpClient)
        val job =
            launch(Dispatchers.IO) {
                provider.translate(
                    texts = listOf("Hello"),
                    sourceLanguage = null,
                    targetLanguage = "zh-CN",
                    config =
                        TranslationRuntimeConfig(
                            endpoint = server.url("/").toString().trimEnd('/'),
                            region = "",
                            apiKey = "secret",
                        ),
                )
            }

        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
