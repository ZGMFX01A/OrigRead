package me.ash.reader.llm.chat.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.core.ReaderOperationBuilder
import me.ash.reader.infrastructure.sync.core.SyncBlobAvailabilityState
import me.ash.reader.infrastructure.sync.core.SyncBlobPayloadCodec
import me.ash.reader.infrastructure.sync.core.SyncBlobStateService
import me.ash.reader.infrastructure.sync.core.SyncLocalBlobStore
import me.ash.reader.infrastructure.sync.core.SyncMutationType
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

/** Bridges the independent Chat DB outbox into the Reader DB global operation log idempotently. */
@Singleton
class LlmOperationBuilder @Inject constructor(
    private val readerDatabase: AndroidDatabase,
    private val chatDatabase: LlmChatDatabase,
    private val readerOperationBuilder: ReaderOperationBuilder,
    private val localBlobStore: SyncLocalBlobStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val blobState = SyncBlobStateService(readerDatabase)

    suspend fun buildPending(
        syncSpaceId: String,
        limit: Int = 100,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val pending = chatDatabase.syncOutboxDao().listPending(syncSpaceId, limit)
        pending.forEach { outbox ->
            val actor = checkNotNull(readerDatabase.syncRuntimeDao().findActor(outbox.actorIncarnationId)) {
                "Missing actor ${outbox.actorIncarnationId} for Chat outbox ${outbox.outboxId}"
            }
            val resolvedPayload =
                if (outbox.mutationType == SyncMutationType.GLOBAL_DELETE.name) {
                    """{"deleted":true}"""
                } else {
                    resolvePayloadReferences(syncSpaceId, outbox.payloadJson)
                }
            val resolvedOutbox = outbox.copy(payloadJson = resolvedPayload)
            val authHistory = readerDatabase.syncAuthLedgerDao().list(syncSpaceId).map {
                me.ash.reader.infrastructure.sync.core.SyncAuthWireCodec.decode(it.authObjectJson)
            }
            val grant = me.ash.reader.infrastructure.sync.core.AndroidSyncAuthLedgerService
                .computeActiveGrant(authHistory, actor.deviceId)
            val operation = readerOperationBuilder.buildOperation(resolvedOutbox, actor.deviceId, now,
                activeGrant = grant, strictAuth = readerOperationBuilder.strictAuth)
            val blobRefs = SyncBlobPayloadCodec.references(operation.payloadJson)
            blobRefs.forEach { ref ->
                val bytes =
                    checkNotNull(localBlobStore.readVerified(ref.manifest.hash)) {
                        "Local Blob ${ref.manifest.hash} is missing for Chat outbox ${outbox.outboxId}"
                    }
                check(bytes.size.toLong() == ref.manifest.totalBytes) {
                    "Local Blob ${ref.manifest.hash} size does not match manifest"
                }
            }

            // Reader log and Chat outbox live in different SQLite files. The bridge is intentionally
            // idempotent instead of pretending there is a cross-database ACID transaction.
            readerDatabase.withTransaction {
                if (outbox.mutationType == SyncMutationType.GLOBAL_DELETE.name) {
                    blobState.removeOwnerReferences(
                        syncSpaceId = syncSpaceId,
                        lane = outbox.replicationLaneId,
                        ownerEntityType = outbox.entityType,
                        ownerEntitySyncId = outbox.entitySyncId,
                        ownerEntityGeneration = outbox.entityGeneration,
                    )
                } else {
                    blobRefs.forEach { ref ->
                        blobState.registerManifest(
                            manifest = ref.manifest,
                            initialState = SyncBlobAvailabilityState.READY,
                            now = now,
                        )
                        blobState.replaceOwnerReference(
                            syncSpaceId = syncSpaceId,
                            lane = outbox.replicationLaneId,
                            ownerEntityType = outbox.entityType,
                            ownerEntitySyncId = outbox.entitySyncId,
                            ownerEntityGeneration = outbox.entityGeneration,
                            referenceKind = ref.referenceKind,
                            hash = ref.manifest.hash,
                            now = now,
                        )
                        blobState.addReference(
                            syncSpaceId = syncSpaceId,
                            lane = outbox.replicationLaneId,
                            ownerEntityType = "__operation__",
                            ownerEntitySyncId = operation.operationId,
                            ownerEntityGeneration = 0,
                            referenceKind = "payload:${ref.referenceKind}",
                            hash = ref.manifest.hash,
                            now = now,
                        )
                    }
                }
                readerOperationBuilder.persistIdempotently(operation)
            }
            chatDatabase.withTransaction {
                chatDatabase.syncOutboxDao().markBuilt(outbox.outboxId, now)
            }
        }
        return pending.size
    }

    internal suspend fun resolvePayloadReferences(
        syncSpaceId: String,
        payloadJson: String,
    ): String {
        val root = json.parseToJsonElement(payloadJson)
        return resolveElement(syncSpaceId, root, root = true).toString()
    }

    private suspend fun resolveElement(
        syncSpaceId: String,
        element: JsonElement,
        root: Boolean = false,
    ): JsonElement =
        when (element) {
            is JsonObject ->
                buildJsonObject {
                    element.forEach { (key, value) ->
                        if (root && key == "id") return@forEach
                        val entityType = localReferenceType(key)
                        if (entityType != null && value is JsonPrimitive && value.isString) {
                            val mapping =
                                if (entityType == SyncEntityType.ARTICLE) {
                                    readerDatabase.syncIdentityMappingDao()
                                        .findByLocalId(syncSpaceId, entityType.wireName, value.content)
                                } else {
                                    chatDatabase.syncIdentityMappingDao()
                                        .findByLocalId(syncSpaceId, entityType.wireName, value.content)
                                }
                            checkNotNull(mapping) {
                                "Missing ${entityType.wireName} Sync ID for local reference ${value.content}"
                            }
                            put(syncReferenceKey(key), mapping.syncId)
                        } else {
                            put(key, resolveElement(syncSpaceId, value))
                        }
                    }
                }
            is JsonArray -> JsonArray(element.map { resolveElement(syncSpaceId, it) })
            else -> element
        }

    private fun localReferenceType(key: String): SyncEntityType? =
        when (key) {
            "conversationLocalId" -> SyncEntityType.CONVERSATION
            "assistantMessageLocalId" -> SyncEntityType.MESSAGE
            "contextRefLocalId" -> SyncEntityType.CONTEXT_REF
            "evidenceBlockLocalId" -> SyncEntityType.EVIDENCE_BLOCK
            "annotationLocalId" -> SyncEntityType.CITATION_ANNOTATION
            "citationRefLocalId" -> SyncEntityType.CITATION_REF
            "articleLocalId" -> SyncEntityType.ARTICLE
            "toolCallLocalId" -> SyncEntityType.TOOL_CALL
            else -> null
        }

    private fun syncReferenceKey(localKey: String): String =
        localKey.removeSuffix("LocalId") + "SyncId"
}
