package me.ash.reader.infrastructure.ai

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.content.ArticleWebSessionManager
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.website.DynamicWebsiteHtmlRenderer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 验证 Android JSON AI 生成的完整服务链路。
 *
 * 目标 API 使用本地 HTTP 样本，模型响应使用固定候选；因此测试稳定覆盖“抓取、生成、
 * 本地解析校验、正文阶段、预览结果”，不会因为真实供应商网络或额度波动而失效。
 */
class AiJsonRuleGenerationTest {
    @Test
    fun `generates json rule preview and verifies a shared description content field`() = runBlocking {
        val targetServer = MockWebServer()
        targetServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"items":[
                      {"id":1,"title":"第一篇测试文章","url":"/articles/1","summary":"这是一段足够长的正文样本，用于验证 JSON API 只有一个文本字段时，摘要路径和正文路径可以安全复用，并且阅读页仍然能够得到完整内容。这里再补充一些实际文章中常见的连续正文，确保本地校验不会把正常内容误判为空。"},
                      {"id":2,"title":"第二篇测试文章","url":"/articles/2","summary":"这是第二段足够长的正文样本，用于验证 JSON API 只有一个文本字段时，摘要路径和正文路径可以安全复用，并且阅读页仍然能够得到完整内容。这里再补充一些实际文章中常见的连续正文，确保本地校验不会把正常内容误判为空。"},
                      {"id":3,"title":"第三篇测试文章","url":"/articles/3","summary":"这是第三段足够长的正文样本，用于验证 JSON API 只有一个文本字段时，摘要路径和正文路径可以安全复用，并且阅读页仍然能够得到完整内容。这里再补充一些实际文章中常见的连续正文，确保本地校验不会把正常内容误判为空。"}
                    ]}
                    """.trimIndent(),
                ),
        )
        targetServer.start()

        try {
            val profile =
                AiProviderProfile(
                    id = "service-2",
                    name = "服务 2",
                    endpoint = "https://example.invalid/v1",
                    defaultModel = "mock-json-model",
                    models = listOf("mock-json-model"),
                )
            val settings = mock<AiSettingsRepository>()
            whenever(settings.current()).thenReturn(
                AiSettings(
                    enabled = true,
                    providers = listOf(profile),
                    defaultProviderId = profile.id,
                ),
            )
            whenever(settings.runtimeConfig(profile.id, "mock-json-model", null)).thenReturn(
                AiRuntimeConfig(
                    endpoint = profile.endpoint,
                    model = profile.defaultModel,
                    apiKey = "",
                ),
            )

            val provider = mock<OpenAiCompatibleProvider>()
            whenever(provider.complete(any(), any(), any())).thenReturn(
                """
                {"id":"draft","name":"本地 JSON 测试","version":1,"enabled":true,
                 "hosts":["example.invalid"],"sourceKind":"API","endpoint":".",
                 "itemsPath":"$.items[*]","titlePath":"$.title","linkPath":"$.url",
                 "datePath":null,"authorPath":null,"descriptionPath":"$.summary",
                 "contentPath":null,"imagePath":null,"idPath":"$.id","dateFormat":null,"maxItems":30}
                """.trimIndent(),
                """{"contentPath":"$.summary","sampleCount":3}""",
            )

            val jsonRepository = mock<JsonRuleRepository>()
            val websiteRepository = mock<me.ash.reader.infrastructure.website.WebsiteRuleRepository>()
            val renderer = mock<DynamicWebsiteHtmlRenderer>()
            val session = mock<ArticleWebSessionManager>()
            whenever(session.desktopHttpUserAgent).thenReturn("OrigRead-Test")

            val service =
                AiRuleGenerationService(
                    aiSettingsRepository = settings,
                    aiProvider = provider,
                    websiteRuleRepository = websiteRepository,
                    jsonRuleRepository = jsonRepository,
                    jsonArticleParser = me.ash.reader.infrastructure.json.JsonArticleParser(),
                    dynamicWebsiteHtmlRenderer = renderer,
                    articleWebSessionManager = session,
                    okHttpClient = OkHttpClient.Builder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(3, TimeUnit.SECONDS)
                        .build(),
                    ioDispatcher = Dispatchers.Unconfined,
                )

            val progress = mutableListOf<AiRuleGenerationStage>()
            val preview = service.generateJsonRule(
                url = targetServer.url("/api").toString(),
                onProgress = { progress += it.stage },
            )

            assertEquals(AiGeneratedRuleKind.JSON, preview.kind)
            assertEquals(3, preview.articleCount)
            assertEquals("服务 2", preview.providerName)
            assertEquals("mock-json-model", preview.model)
            assertEquals(AiContentRuleStatus.VERIFIED, preview.contentStatus)
            assertEquals(3, preview.contentSampleCount)
            assertTrue(preview.contentMessage.orEmpty().contains("共用同一字段"))
            assertTrue(progress.contains(AiRuleGenerationStage.VALIDATING_CANDIDATE))
            assertTrue(progress.contains(AiRuleGenerationStage.GENERATING_CONTENT))
            verify(provider, times(2)).complete(any(), any(), any())
            verify(jsonRepository).validateCandidate(any())
        } finally {
            targetServer.shutdown()
        }
    }
}
