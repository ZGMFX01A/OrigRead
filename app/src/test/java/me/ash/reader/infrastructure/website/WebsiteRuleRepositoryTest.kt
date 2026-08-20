package me.ash.reader.infrastructure.website

import android.content.Context
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WebsiteRuleRepositoryTest {
    private val tempDir = Files.createTempDirectory("origread-website-rules").toFile()
    private lateinit var repository: WebsiteRuleRepository

    @Before
    fun setUp() {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir)
        repository = WebsiteRuleRepository(context)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `configured rules include disabled rules while executable rules exclude them`() {
        val disabledRule = WebsiteRule(
            id = "disabled-example",
            name = "Disabled example",
            hosts = listOf("example.com"),
            articleSelectors = listOf("article"),
            titleSelector = "h2",
            linkSelector = "a[href]",
            enabled = false,
        )
        repository.importRules(Json.encodeToString(WebsiteRuleBundle(rules = listOf(disabledRule))))

        assertEquals(
            listOf("disabled-example"),
            repository.findConfiguredRules("https://example.com/").map(WebsiteRule::id),
        )
        assertTrue(repository.findRules("https://example.com/").isEmpty())
    }

    @Test
    fun `saving a new AI rule replaces the previous AI rule for the same host`() {
        val oldRule = WebsiteRule(
            id = "ai-website-news-example-com-old",
            name = "Old AI rule",
            hosts = listOf("news.example.com"),
            articleSelectors = listOf("article.old"),
            titleSelector = "h2",
        )
        val manualRule = WebsiteRule(
            id = "manual-news-example-com",
            name = "Manual rule",
            hosts = listOf("news.example.com"),
            articleSelectors = listOf("article.manual"),
            titleSelector = "h2",
        )
        val newRule = oldRule.copy(
            id = "ai-website-news-example-com-new",
            name = "New AI rule",
            articleSelectors = listOf("article.new"),
        )

        repository.importRules(Json.encodeToString(WebsiteRuleBundle(rules = listOf(oldRule, manualRule))))
        repository.importRules(Json.encodeToString(WebsiteRuleBundle(rules = listOf(newRule))))

        assertEquals(
            listOf("manual-news-example-com", "ai-website-news-example-com-new").sorted(),
            repository.findConfiguredRules("https://news.example.com/").map(WebsiteRule::id).sorted(),
        )
    }

    @Test
    fun `saving an AI rule directly also replaces the previous AI rule`() {
        val oldRule = WebsiteRule(
            id = "ai-website-news-example-com-old",
            name = "Old AI rule",
            hosts = listOf("news.example.com"),
            articleSelectors = listOf("article.old"),
            titleSelector = "h2",
        )
        val newRule = oldRule.copy(
            id = "ai-website-news-example-com-new",
            name = "New AI rule",
            articleSelectors = listOf("article.new"),
        )

        repository.importRules(Json.encodeToString(WebsiteRuleBundle(rules = listOf(oldRule))))
        repository.saveRule(newRule)

        assertEquals(
            listOf("ai-website-news-example-com-new"),
            repository.findConfiguredRules("https://news.example.com/").map(WebsiteRule::id),
        )
    }

    @Test
    fun `website export excludes the built in IT home rule`() {
        val builtInShapedRule = WebsiteRule(
            id = "ithome-home",
            name = "IT之家首页",
            hosts = listOf("ithome.com"),
            articleSelectors = listOf("ul.nl li.n"),
            titleSelector = "a[href]",
        )
        val customRule = WebsiteRule(
            id = "manual-example",
            name = "Manual example",
            hosts = listOf("example.com"),
            articleSelectors = listOf("article"),
            titleSelector = "h2",
        )
        repository.importRules(
            Json.encodeToString(WebsiteRuleBundle(rules = listOf(builtInShapedRule, customRule))),
        )

        val exported = Json.decodeFromString<WebsiteRuleBundle>(repository.exportRules())

        assertEquals(listOf("manual-example"), exported.rules.map(WebsiteRule::id))
    }

    @Test
    fun `disabling the built in IT home rule changes the effective rule`() {
        repository.setEnabled("ithome-home", false)

        assertEquals(false, repository.findConfiguredRules("https://www.ithome.com/").single().enabled)
        assertTrue(repository.findRules("https://www.ithome.com/").isEmpty())
    }
}
