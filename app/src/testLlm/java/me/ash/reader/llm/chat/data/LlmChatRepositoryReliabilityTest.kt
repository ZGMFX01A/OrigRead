package me.ash.reader.llm.chat.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import me.ash.reader.llm.search.WebSearchRequestStatus
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class LlmChatRepositoryReliabilityTest {
    @Test fun `concurrent conversation changes never read and rewrite an old row`() = runTest {
        val dao = mock<LlmChatDao>()
        val repository = LlmChatRepository(dao)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rename = launch { entered.complete(Unit); release.await(); repository.renameConversation("c", " Renamed ") }
        entered.await()
        repository.updateConversationRuntime("c", "provider", "model", "skill")
        repository.touchConversation("c")
        release.complete(Unit)
        rename.join()
        verify(dao).renameConversation(eq("c"), eq("Renamed"), any())
        verify(dao).updateConversationRuntime(eq("c"), eq("provider"), eq("model"), eq("skill"), any())
        verify(dao).touchConversation(eq("c"), any())
        verify(dao, never()).getConversation(any())
        verify(dao, never()).updateConversation(any())
    }
    @Test fun `stale generation snapshot does not rewrite history or search state`() = runTest {
        val dao = mock<LlmChatDao>()
        val repository = LlmChatRepository(dao)
        val message = LlmMessageEntity("m", "c", LlmChatRole.ASSISTANT, "", status = LlmMessageStatus.STREAMING,
            webSearchStatus = WebSearchRequestStatus.TRIGGERED, createdAt = 0, updatedAt = 0)
        repository.setMessagesHistoryActive(listOf("m"), false)
        repository.updateMessage(message, content = "answer", touchConversation = false)
        verify(dao, never()).updateMessage(any())
        val patch = org.mockito.Mockito.mockingDetails(dao).invocations.single { it.method.name == "patchMessage" }
        assertEquals("answer", patch.arguments[2])
        assertEquals(true, patch.arguments[3]) // 正文发生变化。
        assertEquals(false, patch.arguments[7]) // 默认 status 不重放。
        assertEquals(false, patch.arguments[11]) // 默认搜索状态不重放。
        verify(dao).setMessagesHistoryActive(eq(listOf("m")), eq(false), any())
    }
    @Test fun `replacement transactions clean annotations after cascade before insert`() = runTest {
        val dao = mock<LlmChatDao>()
        doCallRealMethod().whenever(dao).replaceCitationRefsForAssistant(any(), any())
        doCallRealMethod().whenever(dao).updateMessageAndReplaceContextRefs(any(), any())
        dao.replaceCitationRefsForAssistant("m", emptyList())
        inOrder(dao) {
            verify(dao).deleteCitationRefsForAssistant("m")
            verify(dao).deleteOrphanCitationAnnotationsForAssistant("m")
        }
        org.mockito.Mockito.clearInvocations(dao)
        val message = LlmMessageEntity("m", "c", LlmChatRole.ASSISTANT, "", webSearchStatus = WebSearchRequestStatus.SUCCESS, createdAt = 0, updatedAt = 1)
        dao.updateMessageAndReplaceContextRefs(message, emptyList())
        inOrder(dao) {
            verify(dao).updateWebSearchState("m", WebSearchRequestStatus.SUCCESS, null, null, 1)
            verify(dao).deleteContextRefsForAssistant("m")
            verify(dao).deleteOrphanCitationAnnotationsForAssistant("m")
        }
        verify(dao, never()).updateMessage(any())
    }
}
