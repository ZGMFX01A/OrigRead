package me.ash.reader.llm.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmEvidenceBlockBuilderTest {
    @Test
    fun `article evidence fixture matches desktop semantic block contract`() {
        val html =
            """
            <h2>Overview</h2>
            <p>Alpha&nbsp;   beta.</p>
            <ul><li>Item <strong>one</strong><p>nested detail</p></li></ul>
            <blockquote><p>Quoted text</p></blockquote>
            <pre>${"line 1  \r\nline 2\t \r\n"}</pre>
            <table><tr><th>A</th><td>B</td></tr></table>
            """.trimIndent()

        val blocks =
            buildArticleEvidenceBlocks(
                html = html,
                source =
                    LlmArticleEvidenceSource(
                        articleId = "article-1",
                        sourceUrl = "https://example.com/article",
                    ),
            )

        assertEquals(
            listOf(
                LlmEvidenceBlockKind.HEADING,
                LlmEvidenceBlockKind.PARAGRAPH,
                LlmEvidenceBlockKind.LIST_ITEM,
                LlmEvidenceBlockKind.BLOCKQUOTE,
                LlmEvidenceBlockKind.CODE,
                LlmEvidenceBlockKind.TABLE_ROW,
            ),
            blocks.map(BuiltLlmEvidenceBlock::kind),
        )
        assertEquals(
            listOf(
                "Overview",
                "Alpha beta.",
                "Item one nested detail",
                "Quoted text",
                "line 1\nline 2",
                "A | B",
            ),
            blocks.map(BuiltLlmEvidenceBlock::content),
        )
        assertEquals(
            listOf(
                "d4b1ea5708dd532930a85188b45aff6f0a3ed458500c7577e0127a538eb0d100",
                "6d09c2dd9f6acb7404974f19cfc7e9036ac59abb930e505351a55e94b8b1701c",
                "02fb2493e67ba3b3fc463f93d2a1551fbd643d9610bd17454684483c68168a5c",
                "0a4c81a06e76a6fe0d2477d5b109d0e809f3c023eb061a8e14852a18065cf2d8",
                "63661ca848f6f7bb012e06a647e0e3eba49cab8d67ae3edcfb1a347334773bea",
                "2a972c9a739364716235256a420550448398860d47c31ad1534811c35ac81cdf",
            ),
            blocks.map(BuiltLlmEvidenceBlock::normalizedSha256),
        )
        assertEquals(
            listOf(
                "HEADING:d4b1ea5708:d4b1ea5708dd532930a8:0",
                "PARAGRAPH:d4b1ea5708:6d09c2dd9f6acb740497:0",
                "LIST_ITEM:d4b1ea5708:02fb2493e67ba3b3fc46:0",
                "BLOCKQUOTE:d4b1ea5708:0a4c81a06e76a6fe0d24:0",
                "CODE:d4b1ea5708:63661ca848f6f7bb012e:0",
                "TABLE_ROW:d4b1ea5708:2a972c9a739364716235:0",
            ),
            blocks.map(BuiltLlmEvidenceBlock::stableLocatorKey),
        )
        blocks.forEachIndexed { index, block ->
            assertEquals(index, block.ordinal)
            assertEquals(index, block.locator.blockIndex)
            assertEquals(listOf("Overview"), block.locator.headingPath)
            assertEquals("article-1", block.locator.articleId)
            assertEquals("https://example.com/article", block.locator.sourceUrl)
            assertEquals(block.normalizedSha256, block.locator.normalizedHash)
        }
    }

    @Test
    fun `duplicate evidence uses occurrence suffix and heading path disambiguation`() {
        val blocks =
            buildArticleEvidenceBlocks(
                """
                <h2>Section A</h2><p>Same text</p><p>Same text</p>
                <h2>Section B</h2><p>Same text</p>
                """.trimIndent()
            )

        val paragraphs = blocks.filter { it.kind == LlmEvidenceBlockKind.PARAGRAPH }
        assertEquals(3, paragraphs.size)
        assertEquals(0, paragraphs[0].stableLocatorKey.substringAfterLast(':').toInt())
        assertEquals(1, paragraphs[1].stableLocatorKey.substringAfterLast(':').toInt())
        assertEquals(0, paragraphs[2].stableLocatorKey.substringAfterLast(':').toInt())
        assertEquals(listOf("Section A"), paragraphs[0].locator.headingPath)
        assertEquals(listOf("Section B"), paragraphs[2].locator.headingPath)
        assertEquals(paragraphs[0].normalizedSha256, paragraphs[2].normalizedSha256)
    }

    @Test
    fun `selection evidence normalizes text and freezes source identity`() {
        val block =
            buildSelectionEvidenceBlock(
                content = "  selected\u00a0   evidence  ",
                source = LlmArticleEvidenceSource(articleId = "article-2"),
            )!!

        assertEquals("selected evidence", block.content)
        assertEquals(LlmEvidenceBlockKind.SELECTION, block.kind)
        assertEquals(LlmEvidenceSourceKind.SELECTION, block.locator.sourceKind)
        assertEquals("article-2", block.locator.articleId)
        assertEquals("SELECTION:${block.normalizedSha256.take(24)}:0", block.stableLocatorKey)
        assertNull(block.locator.blockIndex)
    }

    @Test
    fun `stable locator does not move when unrelated later content is inserted`() {
        val before = buildArticleEvidenceBlocks("<h2>Section</h2><p>Stable evidence.</p>")
        val after =
            buildArticleEvidenceBlocks(
                "<h2>Section</h2><p>Stable evidence.</p><p>Later new text.</p>"
            )

        assertEquals(before[0].stableLocatorKey, after[0].stableLocatorKey)
        assertEquals(before[1].stableLocatorKey, after[1].stableLocatorKey)
        assertEquals(before[1].normalizedSha256, after[1].normalizedSha256)
    }

    @Test
    fun `meaningful bare text falls back to one paragraph block`() {
        val blocks = buildArticleEvidenceBlocks("<div>Only bare text without semantic tags</div>")

        assertEquals(1, blocks.size)
        assertEquals(LlmEvidenceBlockKind.PARAGRAPH, blocks.single().kind)
        assertEquals("Only bare text without semantic tags", blocks.single().content)
    }

    @Test
    fun `empty article returns no fake evidence`() {
        assertEquals(emptyList<BuiltLlmEvidenceBlock>(), buildArticleEvidenceBlocks("<div>  </div>"))
        assertNull(buildSelectionEvidenceBlock("  \n\t "))
    }
}
