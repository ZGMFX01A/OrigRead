package me.ash.reader.llm.chat.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.ash.reader.llm.chat.data.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LlmChatStateSafetyTest {
    @Test
    fun `foreign transient citations are excluded from selected conversation`() {
        fun override(conversation: String): TransientChatPresentationOverride {
            val message = message(conversation)
            val ref = LlmCitationRefEntity(
                id = "ref-$conversation", conversationId = conversation,
                assistantMessageId = message.id, contextRefId = "context", protocolId = "E1",
                targetKind = LlmCitationTargetKind.EVIDENCE_BLOCK, evidenceBlockId = "block",
                quoteSnapshot = "quote", createdAt = 1L,
            )
            val annotation = LlmCitationAnnotationWithRefs(
                LlmCitationAnnotationEntity(
                    id = "annotation-$conversation", conversationId = conversation,
                    assistantMessageId = message.id, canonicalInsertionOffset = 1,
                    occurrenceOrdinal = 0, createdAt = 1L,
                ), listOf(ref), emptyList(),
            )
            return TransientChatPresentationOverride(message, listOf(ref), listOf(annotation))
        }
        val overrides = listOf(override("current"), override("foreign")).associateBy { it.message.id }
        val scoped = conversationScopedPresentationOverrides(overrides, "current").values
        assertEquals(listOf("ref-current"), scoped.flatMap { it.citationRefs.orEmpty() }.map { it.id })
        assertEquals(listOf("annotation-current"), scoped.flatMap { it.citationAnnotations.orEmpty() }.map { it.annotation.id })
        assertTrue(conversationScopedPresentationOverrides(overrides, null).isEmpty())
    }

    @Test
    fun `first tool exception closes remaining running calls without rewriting completed assistant`() = runTest {
        val assistant = message("current")
        val calls = calls().associateBy { it.id }.toMutableMap()
        var errorMessage: String? = null
        val continued = coordinateAutomaticToolPhase(
            closeUnfinished = { cancelled, text ->
                closeUnfinishedToolCalls(calls.values.toList(), cancelled, text) { calls[it.id] = it }
            },
            publishError = { errorMessage = it },
        ) { throw IllegalStateException("first tool failed") }
        assertFalse(continued)
        assertEquals("first tool failed", errorMessage)
        assertEquals(listOf(LlmToolCallStatus.ERROR, LlmToolCallStatus.ERROR,
            LlmToolCallStatus.PENDING_APPROVAL, LlmToolCallStatus.COMPLETE), calls.values.map { it.status })
        assertEquals(LlmMessageStatus.COMPLETE, assistant.status)
    }

    @Test
    fun `caller cancellation closes all running and pending calls in non cancellable context`() = runTest {
        val assistant = message("current")
        val calls = calls().associateBy { it.id }.toMutableMap()
        val started = CompletableDeferred<Unit>()
        var propagated = false
        val job = launch {
            try {
                coordinateAutomaticToolPhase(
                    closeUnfinished = { cancelled, text ->
                        // This suspension also proves cleanup can run after caller cancellation.
                        kotlinx.coroutines.yield()
                        closeUnfinishedToolCalls(calls.values.toList(), cancelled, text) { calls[it.id] = it }
                    },
                    publishError = { fail("Cancellation must propagate") },
                ) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (error: CancellationException) {
                propagated = true
                throw error
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(propagated)
        assertTrue(calls.values.take(3).all { it.status == LlmToolCallStatus.ERROR })
        assertTrue(calls.values.take(3).all { it.resultContent == "Tool execution was cancelled." })
        assertEquals(LlmToolCallStatus.COMPLETE, calls.getValue("3").status)
        assertEquals(LlmMessageStatus.COMPLETE, assistant.status)
    }

    @Test
    fun `fatal errors escape tool coordination`() = runTest {
        val fatal = AssertionError("fatal")
        try {
            coordinateAutomaticToolPhase(
                closeUnfinished = { _, _ -> fail("JVM Error must not be caught") },
                publishError = { fail("JVM Error must not be published") },
            ) { throw fatal }
            fail("Expected fatal error")
        } catch (error: AssertionError) {
            assertSame(fatal, error)
        }
    }

    @Test
    fun `citation layout timeout returns null and external cancellation propagates`() = runTest {
        assertNull(awaitCitationReturnPlacement { awaitCancellation() })
        assertEquals(1_500L, testScheduler.currentTime)
        val started = CompletableDeferred<Unit>()
        var propagated = false
        val job = launch {
            try {
                awaitCitationReturnPlacement {
                    started.complete(Unit)
                    awaitCancellation()
                }
                fail("Caller cancellation must not become null")
            } catch (error: CancellationException) {
                propagated = true
                throw error
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(propagated)
    }

    private fun message(conversation: String) = LlmMessageEntity(
        id = "assistant-$conversation", conversationId = conversation, role = LlmChatRole.ASSISTANT,
        content = "Completed response", status = LlmMessageStatus.COMPLETE, createdAt = 1L, updatedAt = 1L,
    )

    private fun calls() = listOf(LlmToolCallStatus.RUNNING, LlmToolCallStatus.RUNNING,
        LlmToolCallStatus.PENDING_APPROVAL, LlmToolCallStatus.COMPLETE).mapIndexed { index, status ->
        LlmToolCallEntity(
            id = "$index", conversationId = "current", assistantMessageId = "assistant-current",
            providerCallId = "call-$index", toolId = "tool", apiName = "tool", argumentsJson = "{}",
            status = status, createdAt = 1L, updatedAt = 1L,
        )
    }
}
