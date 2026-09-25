package me.ash.reader.llm.chat.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.core.SyncAppliedFrontierEntity
import me.ash.reader.infrastructure.sync.core.SyncActorRollbackDetectedException
import me.ash.reader.infrastructure.sync.core.SyncMutationType
import me.ash.reader.infrastructure.sync.core.SyncOutboxAllocator
import me.ash.reader.infrastructure.sync.core.SyncOutboxDraft
import me.ash.reader.infrastructure.sync.core.SyncReplicationLane
import me.ash.reader.infrastructure.sync.core.SyncRuntimeCoordinator
import me.ash.reader.infrastructure.sync.core.SyncWritableActorContext
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity

data class LlmSyncMutationDraft(
    val entityType: SyncEntityType,
    val localId: String,
    val mutationType: SyncMutationType,
    val payloadJson: String,
)

/**
 * LLM Chat DB local-mutation boundary.
 *
 * The Chat database owns AI_HISTORY sequence allocation so Chat mutation + Outbox row are committed by the
 * same SQLite transaction. Remote ingestion must bypass this local repository/capture path to avoid echo.
 */
@Singleton
class LlmSyncMutationCapture @Inject constructor(
    private val chatDatabase: LlmChatDatabase,
    private val accountService: AccountService,
    private val coordinator: SyncRuntimeCoordinator,
    private val allocator: SyncOutboxAllocator,
    private val readerDatabase: AndroidDatabase,
) {
    suspend fun <T> capture(
        drafts: List<LlmSyncMutationDraft>,
        mutate: suspend () -> T,
    ): T {
        if (drafts.isEmpty()) return mutate()
        val accountId = accountService.getCurrentAccountId()
        var context: SyncWritableActorContext? = null

        suspend fun attempt(): T =
            coordinator.withLocalMutation(accountId) { currentContext ->
                context = currentContext
                if (currentContext == null) return@withLocalMutation mutate()
                chatDatabase.withTransaction {
                    val mappingDao = chatDatabase.syncIdentityMappingDao()
                    drafts.forEach { draft ->
                        val mapping = ensureMapping(currentContext, mappingDao, draft.entityType, draft.localId)
                        allocator.allocate(
                            dao = chatDatabase.syncOutboxDao(),
                            context = currentContext,
                            lane = SyncReplicationLane.AI_HISTORY,
                            additionalObservedFrontiers = readerDatabase.syncInboxDao()
                                .listCoverage(currentContext.syncSpaceId).map {
                                    SyncAppliedFrontierEntity(it.syncSpaceId, it.replicationLaneId,
                                        it.actorIncarnationId, it.appliedPrefix, it.updatedAt)
                                },
                            draft =
                                SyncOutboxDraft(
                                    entityType = draft.entityType.wireName,
                                    entitySyncId = mapping.syncId,
                                    entityGeneration = mapping.generation,
                                    mutationType = draft.mutationType,
                                    payloadJson = draft.payloadJson,
                                ),
                        )
                    }
                    mutate()
                }
            }

        return try {
            attempt()
        } catch (error: SyncActorRollbackDetectedException) {
            context = coordinator.rotateActor(accountId, "ai-history-outbox-witness-mismatch")
            attempt()
        }
    }

    private suspend fun ensureMapping(
        context: SyncWritableActorContext,
        mappingDao: me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingDao,
        entityType: SyncEntityType,
        localId: String,
    ): SyncIdentityMappingEntity {
        mappingDao.findByLocalId(context.syncSpaceId, entityType.wireName, localId)?.let { return it }
        val now = System.currentTimeMillis()
        val mapping =
            SyncIdentityMappingEntity(
                syncSpaceId = context.syncSpaceId,
                entityType = entityType.wireName,
                localId = localId,
                syncId = SyncCanonicalIdentity.adoptUuidOrNull(localId) ?: SyncCanonicalIdentity.newSyncId(),
                canonicalKey = null,
                generation = 0,
                createdAt = now,
                updatedAt = now,
            )
        mappingDao.insert(mapping)
        return mapping
    }
}
