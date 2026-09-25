package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.Test
import org.mockito.kotlin.*

class SyncApplyFairnessTest {
    @Test fun aFullPageOfSequenceGapsDoesNotHideAnotherActor(): Unit = runBlocking {
        val db: AndroidDatabase = mock()
        val inbox: SyncInboxDao = mock()
        val operations: SyncOperationDao = mock()
        whenever(db.syncInboxDao()).thenReturn(inbox)
        whenever(db.syncOperationDao()).thenReturn(operations)
        val rows = listOf(
            SyncInboxOperationEntity("a2", "space", "a", "LIBRARY", 2, "PENDING", "{}", receivedAt = 1),
            SyncInboxOperationEntity("a3", "space", "a", "LIBRARY", 3, "PENDING", "{}", receivedAt = 1),
            SyncInboxOperationEntity("z1", "space", "z", "LIBRARY", 1, "PENDING", "{}", receivedAt = 1),
        )
        whenever(inbox.listPending(eq("space"), any())).thenAnswer { rows.take(it.getArgument(1)) }
        whenever(inbox.listPendingPage(eq("space"), any(), any(), any(), any(), any())).thenAnswer { call ->
            rows.filter { it.replicationLaneId > call.getArgument<String>(1) ||
                (it.replicationLaneId == call.getArgument<String>(1) && (it.actorIncarnationId > call.getArgument<String>(2) ||
                    (it.actorIncarnationId == call.getArgument<String>(2) && it.sequence > call.getArgument<Long>(3))))
            }.take(call.getArgument(5))
        }
        whenever(inbox.listActorOperations(any(), any(), any())).thenReturn(emptyList())
        whenever(inbox.listCoverage("space")).thenReturn(emptyList())
        whenever(inbox.listRevokedRejected("space")).thenReturn(emptyList())
        SyncRemoteApplyCoordinator(db, mock(), mock()).applyPending("space", SyncRemoteApplyHandler {}, limit = 2)
        // z1 reaches validation even though the preceding actor cannot make progress.
        verify(operations).findById("z1")
    }
}
