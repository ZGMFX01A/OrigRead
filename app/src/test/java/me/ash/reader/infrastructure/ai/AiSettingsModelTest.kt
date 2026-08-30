package me.ash.reader.infrastructure.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiSettingsModelTest {
    @Test
    fun `legacy provider settings default output token style to auto`() {
        assertEquals(AiOutputTokenLimitStyle.AUTO, parseAiOutputTokenLimitStyle(null))
        assertEquals(AiOutputTokenLimitStyle.AUTO, parseAiOutputTokenLimitStyle(""))
        assertEquals(AiOutputTokenLimitStyle.AUTO, parseAiOutputTokenLimitStyle("UNKNOWN_LEGACY_VALUE"))
        assertEquals(
            AiOutputTokenLimitStyle.MAX_COMPLETION_TOKENS,
            parseAiOutputTokenLimitStyle("MAX_COMPLETION_TOKENS"),
        )
    }

    @Test
    fun `disabled default provider falls back to enabled provider`() {
        val settings =
            AiSettings(
                providers =
                    listOf(
                        AiProviderProfile(
                            id = "cheap",
                            name = "便宜模型",
                            enabled = false,
                            defaultModel = "cheap-model",
                        ),
                        AiProviderProfile(
                            id = "strong",
                            name = "强模型",
                            enabled = true,
                            defaultModel = "strong-model",
                        ),
                    ),
                defaultProviderId = "cheap",
            )

        assertEquals("strong", settings.defaultProvider()?.id)
    }

    @Test
    fun `configured profile keeps fetched models independent`() {
        val cheap =
            AiProviderProfile(
                id = "cheap",
                defaultModel = "flash",
                models = listOf("flash", "reasoner"),
            )
        val strong =
            AiProviderProfile(
                id = "strong",
                defaultModel = "pro",
                models = listOf("pro", "pro-thinking"),
            )

        val settings = AiSettings(providers = listOf(cheap, strong), defaultProviderId = "cheap")

        assertEquals("flash", settings.providers[0].defaultModel)
        assertEquals(listOf("flash", "reasoner"), settings.providers[0].models)
        assertEquals("pro", settings.providers[1].defaultModel)
        assertEquals(listOf("pro", "pro-thinking"), settings.providers[1].models)
    }

    @Test
    fun `empty default model falls back to first discovered model`() {
        val provider =
            AiProviderProfile(
                id = "migrated",
                defaultModel = " ",
                models = listOf("deepseek-chat", "deepseek-reasoner"),
            )

        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), provider.availableModels())
        assertEquals("deepseek-chat", provider.resolvedDefaultModel())
    }
}
