package me.ash.reader.infrastructure.json

import android.content.Context
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class JsonRuleRepositoryTest {
    private val tempDir = Files.createTempDirectory("origread-json-rules").toFile()
    private lateinit var repository: JsonRuleRepository

    @Before
    fun setUp() {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir)
        repository = JsonRuleRepository(context)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `saving a new AI rule replaces the previous AI rule for the same host`() {
        val oldRule = rule("ai-json-api-example-com-old", "$.items[*]")
        val newRule = rule("ai-json-api-example-com-new", "$.data.items[*]")

        repository.importRules(Json.encodeToString(JsonRuleBundle(rules = listOf(oldRule))))
        repository.saveRule(newRule)

        assertEquals(
            listOf("ai-json-api-example-com-new"),
            repository.listRules().map(JsonRule::id),
        )
    }

    private fun rule(id: String, itemsPath: String) = JsonRule(
        id = id,
        name = id,
        hosts = listOf("api.example.com"),
        endpoint = "https://api.example.com/posts",
        itemsPath = itemsPath,
        titlePath = "$.title",
        linkPath = "$.url",
    )
}
