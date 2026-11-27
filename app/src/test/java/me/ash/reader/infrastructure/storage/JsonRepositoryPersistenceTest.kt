package me.ash.reader.infrastructure.storage

import android.content.Context
import me.ash.reader.infrastructure.filter.ArticleFilterRepository
import me.ash.reader.infrastructure.json.JsonRuleRepository
import me.ash.reader.infrastructure.json.JsonRule
import me.ash.reader.infrastructure.website.WebsiteRule
import me.ash.reader.infrastructure.website.WebsiteParsePreferenceRepository
import me.ash.reader.infrastructure.website.WebsiteRuleRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class JsonRepositoryPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context(): Context = mock<Context>().also { whenever(it.filesDir).thenReturn(temp.root) }

    @Test fun `absent files return empty configuration without writing`() {
        val context = context()
        assertTrue(ArticleFilterRepository(context).getAll().isEmpty())
        assertTrue(ArticleFilterRepository(context).getFilteredArticles().isEmpty())
        assertNull(WebsiteParsePreferenceRepository(context).get("feed"))
        assertTrue(JsonRuleRepository(context).listRules().isEmpty())
        assertEquals("{\"schemaVersion\":1,\"rules\":[]}", WebsiteRuleRepository(context).exportRules().filterNot(Char::isWhitespace))
        assertTrue(temp.root.list()!!.isEmpty())
    }

    @Test fun `all file repositories preserve malformed JSON and throw without clearing`() {
        val context = context()
        val cases: List<Pair<String, () -> Unit>> = listOf(
            "article-filter-rules.json" to { ArticleFilterRepository(context); Unit },
            "article-filter-history.json" to { ArticleFilterRepository(context); Unit },
            "website-parse-preferences.json" to { WebsiteParsePreferenceRepository(context).get("feed"); Unit },
            "website-rules.json" to { WebsiteRuleRepository(context).listRules(); Unit },
            "json-source-rules.json" to { JsonRuleRepository(context).listRules(); Unit },
        )
        cases.forEach { (name, read) ->
            val file = temp.root.resolve(name).apply { writeText("{broken") }
            val error = assertThrows(IllegalStateException::class.java) { read() }
            val backup = temp.root.listFiles()!!.single { it.name.startsWith("$name.corrupt-") }
            assertEquals("{broken", file.readText())
            assertArrayEquals(file.readBytes(), backup.readBytes())
            assertTrue(error.message!!.contains(backup.absolutePath))
            // Leave backups until TemporaryFolder teardown, but remove this original for the next case.
            assertTrue(file.delete())
        }
    }

    @Test fun `invalid schemas and regexes are corruption too`() {
        val file = temp.root.resolve("article-filter-rules.json")
        file.writeText("""{"schemaVersion":99,"rules":[]}""")
        assertThrows(IllegalStateException::class.java) { ArticleFilterRepository(context()) }
        assertTrue(file.readText().contains("99"))
        file.writeText("""{"rules":[{"keyword":"[","type":"REGEX"}]}""")
        assertThrows(IllegalStateException::class.java) { ArticleFilterRepository(context()) }
        assertTrue(temp.root.list()!!.count { ".corrupt-" in it } == 2)
    }

    @Test fun `parallel filter additions survive reload and preference updates keep both fields`() {
        val context = context()
        val filter = ArticleFilterRepository(context)
        concurrently(40) { filter.add("keyword-$it") }
        assertEquals(40, filter.getAll().size)
        assertEquals(filter.getAll(), ArticleFilterRepository(context).getAll())
        val preferences = WebsiteParsePreferenceRepository(context)
        concurrently(40) {
            if (it % 2 == 0) preferences.setPreferredRule("feed", "rule", "Rule")
            else preferences.setDynamicRenderingEnabled("feed", true)
        }
        val reloaded = WebsiteParsePreferenceRepository(context).get("feed")!!
        assertEquals("rule", reloaded.preferredRuleId)
        assertTrue(reloaded.dynamicRenderingEnabled)
        assertFalse(temp.root.list()!!.any { ".tmp-" in it })
    }

    @Test fun `parallel website and JSON rule updates retain every rule`() {
        val context = context()
        val website = WebsiteRuleRepository(context)
        val json = JsonRuleRepository(context)
        concurrently(24) { index ->
            website.saveRule(WebsiteRule(
                id = "custom-$index", name = "Custom $index", hosts = listOf("example.com"),
                articleSelectors = listOf("article"), titleSelector = "h2", linkSelector = "a",
            ))
            json.saveRule(JsonRule(
                id = "custom-$index", name = "Custom $index", hosts = listOf("example.com"),
                endpoint = "/api", itemsPath = "$.items[*]", titlePath = "$.title", linkPath = "$.url",
            ))
        }
        assertEquals(24, WebsiteRuleRepository(context).listRules().count { it.id.startsWith("custom-") })
        assertEquals(24, JsonRuleRepository(context).listRules().size)
    }
}
