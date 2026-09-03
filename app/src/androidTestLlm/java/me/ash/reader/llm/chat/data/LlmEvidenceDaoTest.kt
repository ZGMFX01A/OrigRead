package me.ash.reader.llm.chat.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.ash.reader.llm.runtime.LlmContextType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmEvidenceDaoTest {
    private lateinit var database: LlmChatDatabase
    private lateinit var dao: LlmChatDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                LlmChatDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.chatDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun evidenceAndCitation_roundTripAndCascadeByMessageScope() =
        runBlocking {
            dao.insertConversation(
                LlmConversationEntity(
                    id = "conversation-1",
                    title = "Evidence DAO",
                    providerId = null,
                    model = null,
                    createdAt = 1,
                    updatedAt = 1,
                )
            )
            dao.insertMessage(
                LlmMessageEntity(
                    id = "assistant-1",
                    conversationId = "conversation-1",
                    role = LlmChatRole.ASSISTANT,
                    content = "answer [[E1]]",
                    createdAt = 2,
                    updatedAt = 2,
                )
            )
            dao.insertContextRefs(
                listOf(
                    LlmContextRefEntity(
                        id = "context-ref-1",
                        conversationId = "conversation-1",
                        assistantMessageId = "assistant-1",
                        contextId = "article:article-1",
                        type = LlmContextType.ARTICLE,
                        title = "Article",
                        articleId = "article-1",
                        sourceUrl = "https://example.com/article",
                        contentSnapshot = "Evidence text.",
                        promptContentSnapshot = "Evidence text.",
                        contentSha256 = "context-hash",
                        priority = 100,
                        includedInPrompt = true,
                        truncatedInPrompt = false,
                        createdAt = 3,
                    )
                )
            )

            val locator =
                LlmEvidenceLocatorV1(
                    sourceKind = LlmEvidenceSourceKind.ARTICLE,
                    stableLocatorKey = "PARAGRAPH:root:hash:0",
                    blockIndex = 0,
                    headingPath = listOf("Section"),
                    articleId = "article-1",
                    sourceUrl = "https://example.com/article",
                    normalizedHash = "evidence-hash",
                )
            val evidence =
                LlmEvidenceBlockEntity(
                    id = "evidence-1",
                    contextRefId = "context-ref-1",
                    stableLocatorKey = locator.stableLocatorKey!!,
                    kind = LlmEvidenceBlockKind.PARAGRAPH,
                    ordinal = 0,
                    textSnapshot = "Evidence text.",
                    normalizedSha256 = "evidence-hash",
                    locator = locator,
                    createdAt = 4,
                )
            dao.replaceEvidenceBlocksForContextRef("context-ref-1", listOf(evidence))
            dao.replaceCitationRefsForAssistant(
                "assistant-1",
                listOf(
                    LlmCitationRefEntity(
                        id = "citation-1",
                        conversationId = "conversation-1",
                        assistantMessageId = "assistant-1",
                        contextRefId = "context-ref-1",
                        evidenceBlockId = "evidence-1",
                        targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK,
                        protocolId = "E1",
                        displayOrder = 1,
                        quoteSnapshot = "Evidence text.",
                        sourceUrl = "https://example.com/article",
                        locatorSnapshot = locator,
                        createdAt = 5,
                    )
                ),
            )

            val restoredEvidence = dao.getEvidenceBlocksForContextRef("context-ref-1").single()
            assertEquals(locator, restoredEvidence.locator)
            assertEquals("Evidence text.", restoredEvidence.textSnapshot)

            val restoredCitation = dao.getCitationRefsForAssistant("assistant-1").single()
            assertEquals("E1", restoredCitation.protocolId)
            assertEquals(1, restoredCitation.displayOrder)
            assertEquals(locator, restoredCitation.locatorSnapshot)

            val replacement =
                evidence.copy(
                    id = "evidence-2",
                    stableLocatorKey = "PARAGRAPH:root:new-hash:0",
                    normalizedSha256 = "new-hash",
                    locator =
                        locator.copy(
                            stableLocatorKey = "PARAGRAPH:root:new-hash:0",
                            normalizedHash = "new-hash",
                        ),
                )
            dao.replaceEvidenceBlocksForContextRef("context-ref-1", listOf(replacement))

            assertEquals(listOf("evidence-2"), dao.getEvidenceBlocksForContextRef("context-ref-1").map { it.id })
            assertTrue(dao.getCitationRefsForAssistant("assistant-1").isEmpty())
        }

    @Test
    fun contextRefsAndEvidence_replaceAtomicallyForAssistant() =
        runBlocking {
            dao.insertConversation(
                LlmConversationEntity(
                    id = "conversation-atomic",
                    title = "Atomic Evidence",
                    providerId = null,
                    model = null,
                    createdAt = 1,
                    updatedAt = 1,
                )
            )
            dao.insertMessage(
                LlmMessageEntity(
                    id = "assistant-atomic",
                    conversationId = "conversation-atomic",
                    role = LlmChatRole.ASSISTANT,
                    content = "",
                    createdAt = 2,
                    updatedAt = 2,
                )
            )
            val contextRef =
                LlmContextRefEntity(
                    id = "context-atomic",
                    conversationId = "conversation-atomic",
                    assistantMessageId = "assistant-atomic",
                    contextId = "article:atomic:original",
                    type = LlmContextType.ARTICLE,
                    contentSnapshot = "Atomic evidence.",
                    promptContentSnapshot = "Atomic evidence.",
                    contentSha256 = "context-hash",
                    priority = 100,
                    includedInPrompt = true,
                    truncatedInPrompt = false,
                    createdAt = 3,
                )
            val locator =
                LlmEvidenceLocatorV1(
                    sourceKind = LlmEvidenceSourceKind.ARTICLE,
                    stableLocatorKey = "PARAGRAPH:root:atomic:0",
                    blockIndex = 0,
                    articleId = "atomic",
                    normalizedHash = "atomic-hash",
                )
            val evidence =
                LlmEvidenceBlockEntity(
                    id = "evidence-atomic",
                    contextRefId = contextRef.id,
                    stableLocatorKey = locator.stableLocatorKey!!,
                    kind = LlmEvidenceBlockKind.PARAGRAPH,
                    ordinal = 0,
                    textSnapshot = "Atomic evidence.",
                    normalizedSha256 = "atomic-hash",
                    locator = locator,
                    createdAt = 3,
                )

            dao.replaceContextRefsAndEvidenceForAssistant(
                assistantMessageId = "assistant-atomic",
                contextRefs = listOf(contextRef),
                evidenceBlocks = listOf(evidence),
            )

            assertEquals(listOf("context-atomic"), dao.getContextRefsForAssistant("assistant-atomic").map { it.id })
            assertEquals(listOf("evidence-atomic"), dao.getEvidenceBlocksForContextRef("context-atomic").map { it.id })
        }
}
