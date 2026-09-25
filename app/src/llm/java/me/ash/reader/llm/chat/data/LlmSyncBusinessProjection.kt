package me.ash.reader.llm.chat.data

import androidx.room.withTransaction
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.core.SyncApplyDeferredException
import me.ash.reader.infrastructure.sync.core.SyncBlobAvailabilityState
import me.ash.reader.infrastructure.sync.core.SyncBlobPayloadCodec
import me.ash.reader.infrastructure.sync.core.SyncBlobStateService
import me.ash.reader.infrastructure.sync.core.SyncBusinessProjectionExtension
import me.ash.reader.infrastructure.sync.core.SyncGenesisProjectionEntity
import me.ash.reader.infrastructure.sync.core.SyncLocalBlobStore
import me.ash.reader.infrastructure.sync.core.SyncMutationType
import me.ash.reader.infrastructure.sync.core.SyncOperationBuildStatus
import me.ash.reader.infrastructure.sync.core.SyncOperationCanonicalizer
import me.ash.reader.infrastructure.sync.core.SyncOperationEntity
import me.ash.reader.infrastructure.sync.core.SyncOperationWireCodec
import me.ash.reader.infrastructure.sync.core.SyncPayloadMerge
import me.ash.reader.infrastructure.sync.core.SyncFieldVersionEntity
import me.ash.reader.infrastructure.sync.core.SyncLocalRecoverySnapshotRequiredException
import me.ash.reader.infrastructure.sync.core.SyncProjectionGenesisCutEntity
import me.ash.reader.infrastructure.sync.core.SyncTombstoneEntity
import me.ash.reader.infrastructure.sync.core.SyncVersionToken
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingDao
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity
import me.ash.reader.llm.runtime.LlmContextType
import me.ash.reader.llm.runtime.LlmExecutionTask
import me.ash.reader.llm.search.WebSearchRequestStatus

@Singleton
class LlmSyncBusinessProjection @Inject constructor(
    private val readerDatabase: AndroidDatabase,
    private val chatDatabase: LlmChatDatabase,
    private val localBlobStore: SyncLocalBlobStore,
    private val identityBackfill: LlmGenesisIdentityBackfill,
    private val operationBuilder: LlmOperationBuilder,
) : SyncBusinessProjectionExtension {
    private val json = Json { ignoreUnknownKeys = true }
    private val blobState = SyncBlobStateService(readerDatabase)
    private val ownedTypes =
        setOf(
            SyncEntityType.CONVERSATION,
            SyncEntityType.CONVERSATION_ARTICLE,
            SyncEntityType.MESSAGE,
            SyncEntityType.TOOL_CALL,
            SyncEntityType.CONTEXT_REF,
            SyncEntityType.EVIDENCE_BLOCK,
            SyncEntityType.CITATION_REF,
            SyncEntityType.CITATION_ANNOTATION,
            SyncEntityType.CITATION_ANNOTATION_REF,
        )

    override fun owns(entityType: String): Boolean =
        SyncEntityType.fromWireName(entityType) in ownedTypes

    override fun canApplyWithoutBlob(entityType: String, referenceKind: String): Boolean =
        isMetadataFirstAttachment(entityType, referenceKind)

    override fun readLocalBlob(hash: String): ByteArray? =
        localBlobStore.readVerified(hash)

    override suspend fun persistFetchedBlob(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        entityGeneration: Long,
        referenceKind: String,
        manifest: me.ash.reader.infrastructure.sync.core.SyncBlobManifestWire,
        bytes: ByteArray,
    ) {
        localBlobStore.putVerified(manifest.hash, bytes)
        if (!isMetadataFirstAttachment(entityType, referenceKind)) return
        if (readerDatabase.syncBlobDao().listReferencesForOwner(syncSpaceId, "AI_HISTORY", entityType,
                entitySyncId, entityGeneration).none { it.referenceKind == referenceKind && it.hash == manifest.hash }) return
        val type = SyncEntityType.fromWireName(entityType) ?: return
        val mapping =
            chatDatabase.syncIdentityMappingDao()
                .findBySyncId(syncSpaceId, entityType, entitySyncId)
                ?.takeIf { it.generation == entityGeneration }
                ?: return
        val text = bytes.toString(Charsets.UTF_8)
        val dao = chatDatabase.chatDao()
        chatDatabase.withTransaction {
            when (type) {
                SyncEntityType.CONTEXT_REF -> {
                    val row = dao.getContextRefById(mapping.localId) ?: return@withTransaction
                    when (referenceKind) {
                        "context_snapshot" -> dao.updateContextRef(row.copy(contentSnapshot = text))
                        "context_prompt_snapshot" -> dao.updateContextRef(row.copy(promptContentSnapshot = text))
                    }
                }
                SyncEntityType.EVIDENCE_BLOCK -> {
                    if (referenceKind == "evidence_text") {
                        dao.getEvidenceBlockById(mapping.localId)
                            ?.let { dao.updateEvidenceBlock(it.copy(textSnapshot = text)) }
                    }
                }
                SyncEntityType.CITATION_REF -> {
                    if (referenceKind == "citation_quote") {
                        dao.getCitationRefById(mapping.localId)
                            ?.let { dao.updateCitationRef(it.copy(quoteSnapshot = text)) }
                    }
                }
                else -> Unit
            }
        }
    }

    override suspend fun prepareGenesis(
        syncSpaceId: String,
        genesisBaselineId: String,
        now: Long,
    ): List<SyncGenesisProjectionEntity> {
        identityBackfill.backfill(syncSpaceId, now)
        val source = chatDatabase.syncIdentitySourceDao()
        val entities = mutableListOf<SyncGenesisProjectionEntity>()

        suspend fun add(
            type: SyncEntityType,
            localId: String,
            payloadJson: String,
        ) {
            val mapping =
                chatDatabase.syncIdentityMappingDao()
                    .findByLocalId(syncSpaceId, type.wireName, localId)
                    ?: throw IllegalStateException(
                        "Missing Genesis AI_HISTORY mapping for ${type.wireName}/$localId"
                    )
            val resolved = resolvePayloadReferences(syncSpaceId, payloadJson)
            SyncBlobPayloadCodec.references(resolved).forEach { ref ->
                val bytes =
                    localBlobStore.readVerified(ref.manifest.hash)
                        ?: throw IllegalStateException(
                            "Genesis AI_HISTORY Blob is missing: ${ref.manifest.hash}"
                        )
                require(bytes.size.toLong() == ref.manifest.totalBytes) {
                    "Genesis AI_HISTORY Blob size mismatch: ${ref.manifest.hash}"
                }
                blobState.registerManifest(
                    ref.manifest,
                    SyncBlobAvailabilityState.READY,
                    now,
                )
                blobState.markReadyVerified(ref.manifest.hash, ref.manifest.totalBytes, now)
                blobState.replaceOwnerReference(
                    syncSpaceId = syncSpaceId,
                    lane = "AI_HISTORY",
                    ownerEntityType = type.wireName,
                    ownerEntitySyncId = mapping.syncId,
                    ownerEntityGeneration = mapping.generation,
                    referenceKind = ref.referenceKind,
                    hash = ref.manifest.hash,
                    now = now,
                )
            }
            entities +=
                SyncGenesisProjectionEntity(
                    entityType = type.wireName,
                    entitySyncId = mapping.syncId,
                    generation = mapping.generation,
                    fieldsJson = SyncOperationCanonicalizer.canonicalJson(resolved),
                )
        }

        source.allConversations().forEach {
            add(SyncEntityType.CONVERSATION, it.id, it.toSyncPayloadJson())
        }
        val stableMessages =
            source.allMessages().filter { it.status != LlmMessageStatus.STREAMING }
        val stableMessageIds = stableMessages.mapTo(mutableSetOf()) { it.id }
        val stableAssistantIds =
            stableMessages.filter { it.role == LlmChatRole.ASSISTANT }
                .mapTo(mutableSetOf()) { it.id }
        stableMessages.forEach {
            add(SyncEntityType.MESSAGE, it.id, it.toSyncPayloadJson())
        }
        source.allToolCalls()
            .filter {
                it.assistantMessageId in stableAssistantIds &&
                    it.status in
                        setOf(
                            LlmToolCallStatus.COMPLETE,
                            LlmToolCallStatus.DENIED,
                            LlmToolCallStatus.ERROR,
                        )
            }.forEach {
            add(SyncEntityType.TOOL_CALL, it.id, it.toSyncPayloadJson(localBlobStore))
        }
        val stableContextRefs =
            source.allContextRefs().filter { it.assistantMessageId in stableAssistantIds }
        val stableContextIds = stableContextRefs.mapTo(mutableSetOf()) { it.id }
        stableContextRefs.forEach {
            add(SyncEntityType.CONTEXT_REF, it.id, it.toSyncPayloadJson(localBlobStore))
        }
        val stableEvidenceBlocks =
            source.allEvidenceBlocks().filter { it.contextRefId in stableContextIds }
        val stableEvidenceIds = stableEvidenceBlocks.mapTo(mutableSetOf()) { it.id }
        stableEvidenceBlocks.forEach {
            add(SyncEntityType.EVIDENCE_BLOCK, it.id, it.toSyncPayloadJson(localBlobStore))
        }
        val stableCitationRefs =
            source.allCitationRefs().filter { citation ->
                citation.assistantMessageId in stableAssistantIds &&
                    citation.contextRefId in stableContextIds &&
                    (citation.evidenceBlockId == null || citation.evidenceBlockId in stableEvidenceIds)
            }
        val stableCitationIds = stableCitationRefs.mapTo(mutableSetOf()) { it.id }
        stableCitationRefs.forEach {
            add(SyncEntityType.CITATION_REF, it.id, it.toSyncPayloadJson(localBlobStore))
        }
        val stableAnnotations =
            source.allCitationAnnotations().filter { it.assistantMessageId in stableAssistantIds }
        val stableAnnotationIds = stableAnnotations.mapTo(mutableSetOf()) { it.id }
        stableAnnotations.forEach {
            add(SyncEntityType.CITATION_ANNOTATION, it.id, it.toSyncPayloadJson())
        }
        source.allConversationArticles().forEach { row ->
            add(
                SyncEntityType.CONVERSATION_ARTICLE,
                SyncCanonicalIdentity.relationLocalId(
                    SyncEntityType.CONVERSATION_ARTICLE,
                    row.conversationId,
                    row.articleId,
                ),
                row.toSyncPayloadJson(localBlobStore),
            )
        }
        source.allCitationAnnotationRefs()
            .filter {
                it.annotationId in stableAnnotationIds &&
                    it.citationRefId in stableCitationIds
            }.forEach { row ->
            add(
                SyncEntityType.CITATION_ANNOTATION_REF,
                SyncCanonicalIdentity.relationLocalId(
                    SyncEntityType.CITATION_ANNOTATION_REF,
                    row.annotationId,
                    row.citationRefId,
                ),
                row.toSyncPayloadJson(),
            )
        }
        check(stableMessageIds.containsAll(stableAssistantIds))
        return entities.sortedWith(
            compareBy(
                SyncGenesisProjectionEntity::entityType,
                SyncGenesisProjectionEntity::entitySyncId,
            )
        )
    }

    override suspend fun genesisLaneFrontiers(
        syncSpaceId: String,
        crossDbCutId: String,
        actorIncarnationIds: Set<String>,
        capturedAt: Long,
    ): Map<String, Map<String, Long>> =
        chatDatabase.withTransaction {
            val cutDao = chatDatabase.syncProjectionGenesisCutDao()
            val existing = cutDao.listForCut(syncSpaceId, crossDbCutId)
            if (existing.isNotEmpty()) {
                return@withTransaction existing
                    .groupBy { it.replicationLaneId }
                    .mapValues { (_, rows) ->
                        rows.associate { it.actorIncarnationId to it.sequence }
                    }
            }

            val rows =
                actorIncarnationIds.sorted().map { actorId ->
                    SyncProjectionGenesisCutEntity(
                        syncSpaceId = syncSpaceId,
                        crossDbCutId = crossDbCutId,
                        replicationLaneId = "AI_HISTORY",
                        actorIncarnationId = actorId,
                        sequence =
                            chatDatabase.syncOutboxDao()
                                .findWriterState(syncSpaceId, actorId, "AI_HISTORY")
                                ?.lastSequence ?: 0L,
                        capturedAt = capturedAt,
                    )
                }
            cutDao.insertAll(rows)
            mapOf(
                "AI_HISTORY" to
                    rows.associate { it.actorIncarnationId to it.sequence }
            )
        }

    override suspend fun markGenesisIncluded(
        syncSpaceId: String,
        laneFrontiers: Map<String, Map<String, Long>>,
        now: Long,
    ) {
        val frontier = laneFrontiers["AI_HISTORY"] ?: return
        chatDatabase.withTransaction {
            chatDatabase.syncOutboxDao().listGenesisCandidates(syncSpaceId)
                .filter { outbox ->
                    outbox.replicationLaneId == "AI_HISTORY" &&
                        outbox.sequence <= (frontier[outbox.actorIncarnationId] ?: 0L)
                }
                .forEach { outbox ->
                    chatDatabase.syncOutboxDao().markGenesisIncluded(outbox.outboxId, now)
                }
        }
    }

    override suspend fun buildPendingOperations(
        syncSpaceId: String,
        limit: Int,
        now: Long,
    ): Int = operationBuilder.buildPending(syncSpaceId, limit, now)

    override suspend fun hasPendingOutbox(syncSpaceId: String): Boolean =
        chatDatabase.syncOutboxDao().listPending(syncSpaceId, 1).isNotEmpty()

    override suspend fun compactStableCoverage(
        syncSpaceId: String,
        stableCoverage: me.ash.reader.infrastructure.sync.core.SyncCoverage,
    ) {
        val aiCoverage = stableCoverage["AI_HISTORY"] ?: return
        chatDatabase.withTransaction {
            aiCoverage.forEach { (actor, prefix) ->
                chatDatabase.syncOutboxDao().deleteBuiltThroughPrefix(
                    syncSpaceId = syncSpaceId,
                    lane = "AI_HISTORY",
                    actor = actor,
                    prefix = prefix,
                )
            }
        }
    }

    override suspend fun materializeSnapshotEntity(
        syncSpaceId: String,
        entity: SyncGenesisProjectionEntity,
        now: Long,
    ): Boolean {
        if (!owns(entity.entityType)) return false
        validateSnapshotEntity(syncSpaceId, entity)
        val operationType =
            when (entity.entityType) {
                SyncEntityType.CONVERSATION_ARTICLE.wireName,
                SyncEntityType.CITATION_ANNOTATION_REF.wireName,
                -> SyncMutationType.RELATION_SET.name
                else -> SyncMutationType.UPSERT.name
            }
        val payloadHash = SyncOperationCanonicalizer.sha256Hex(entity.fieldsJson)
        apply(
            SyncOperationEntity(
                operationId =
                    "snapshot-materialize:" +
                        SyncOperationCanonicalizer.sha256Hex(
                            "$syncSpaceId\n${entity.entityType}\n${entity.entitySyncId}\n${entity.generation}"
                        ),
                syncSpaceId = syncSpaceId,
                authorDeviceId = "snapshot",
                actorIncarnationId = "snapshot",
                replicationLaneId = "AI_HISTORY",
                sequence = 1L,
                logicalClock = 1L,
                causalContextJson = "{}",
                dependencyDotsJson = "[]",
                entityType = entity.entityType,
                entitySyncId = entity.entitySyncId,
                entityGeneration = entity.generation,
                operationType = operationType,
                payloadSchemaVersion = 1,
                payloadJson = entity.fieldsJson,
                schemaVersion = 1,
                createdWallClock = now,
                payloadHash = payloadHash,
                signingDigest = payloadHash,
                authorSignature = null,
                buildStatus = SyncOperationBuildStatus.SIGNED.name,
                createdAt = now,
                updatedAt = now,
            )
        )
        return true
    }

    override suspend fun validateSnapshotEntity(
        syncSpaceId: String,
        entity: SyncGenesisProjectionEntity,
    ) {
        if (!owns(entity.entityType)) return
        val existingMapping =
            chatDatabase.syncIdentityMappingDao()
                .findBySyncId(syncSpaceId, entity.entityType, entity.entitySyncId)
        if (existingMapping != null && entity.generation < existingMapping.generation) {
            throw SyncLocalRecoverySnapshotRequiredException(
                "LOCAL_RECOVERY_REQUIRED: Snapshot ${entity.entityType}/${entity.entitySyncId} generation " +
                    "${entity.generation} is behind local generation ${existingMapping.generation}"
            )
        }
    }

    override suspend fun materializeSnapshotTombstone(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        generation: Long,
        versionToken: String,
        deletedAt: Long,
    ): Boolean {
        if (!owns(entityType)) return false
        validateSnapshotTombstone(syncSpaceId, entityType, entitySyncId, generation)
        val type =
            SyncEntityType.fromWireName(entityType)
                ?: throw SyncApplyDeferredException("Unsupported AI_HISTORY tombstone entity")
        val payloadHash = SyncOperationCanonicalizer.sha256Hex("{}")
        applyDelete(
            operation =
                SyncOperationEntity(
                    operationId =
                        "snapshot-delete:" +
                            SyncOperationCanonicalizer.sha256Hex(
                                "$syncSpaceId\n$entityType\n$entitySyncId\n$generation"
                            ),
                    syncSpaceId = syncSpaceId,
                    authorDeviceId = "snapshot",
                    actorIncarnationId = "snapshot",
                    replicationLaneId = "AI_HISTORY",
                    sequence = 1L,
                    logicalClock = 1L,
                    causalContextJson = "{}",
                    dependencyDotsJson = "[]",
                    entityType = entityType,
                    entitySyncId = entitySyncId,
                    entityGeneration = generation,
                    operationType = SyncMutationType.GLOBAL_DELETE.name,
                    payloadSchemaVersion = 1,
                    payloadJson = "{}",
                    schemaVersion = 1,
                    createdWallClock = deletedAt,
                    payloadHash = payloadHash,
                    signingDigest = payloadHash,
                    authorSignature = null,
                    buildStatus = SyncOperationBuildStatus.SIGNED.name,
                    createdAt = deletedAt,
                    updatedAt = deletedAt,
                ),
            type = type,
            versionTokenOverride = versionToken,
            deletedAtOverride = deletedAt,
        )
        return true
    }

    override suspend fun validateSnapshotTombstone(
        syncSpaceId: String,
        entityType: String,
        entitySyncId: String,
        generation: Long,
    ) {
        if (!owns(entityType)) return
        val existingMapping =
            chatDatabase.syncIdentityMappingDao()
                .findBySyncId(syncSpaceId, entityType, entitySyncId)
        if (existingMapping != null && generation < existingMapping.generation) {
            throw SyncLocalRecoverySnapshotRequiredException(
                "LOCAL_RECOVERY_REQUIRED: Snapshot tombstone $entityType/$entitySyncId generation " +
                    "$generation is behind local generation ${existingMapping.generation}"
            )
        }
    }

    override suspend fun apply(operation: SyncOperationEntity) {
        val type =
            SyncEntityType.fromWireName(operation.entityType)
                ?.takeIf { it in ownedTypes }
                ?: throw SyncApplyDeferredException("Unsupported AI_HISTORY entity")
        val tombstone =
            readerDatabase.syncInboxDao()
                .findTombstone(operation.syncSpaceId, operation.entityType, operation.entitySyncId)
        if (
            operation.operationType != SyncMutationType.GLOBAL_DELETE.name &&
            tombstone != null &&
            operation.entityGeneration <= tombstone.entityGeneration
        ) {
            return
        }

        val isSnapshot = operation.operationId.startsWith("snapshot-")
        val journal = chatDatabase.syncApplyJournalDao()
        val previous = if (isSnapshot) null else journal.find(operation.operationId)
        val encoded = if (isSnapshot) null else SyncOperationWireCodec.encode(SyncOperationWireCodec.toWire(operation))
        check(previous == null || previous.operationJson == encoded) { "DOT_COLLISION in Chat apply journal" }
        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            // The Reader tombstone must be recreated even when Chat already committed before a crash.
            applyDelete(operation, type)
            return
        }

        val retained = if (isSnapshot) emptyList() else journal.listEntity(operation.syncSpaceId,
            operation.entityType, operation.entitySyncId, operation.entityGeneration).filterNot {
                it.operationId.startsWith("snapshot-")
            }.map {
                SyncOperationWireCodec.fromWire(SyncOperationWireCodec.decode(it.operationJson))
            }
        val local = if (isSnapshot) emptyList() else chatDatabase.syncOutboxDao()
            .listGenesisCandidates(operation.syncSpaceId).filter {
                it.entityType == operation.entityType && it.entitySyncId == operation.entitySyncId &&
                    it.entityGeneration == operation.entityGeneration
            }.map { outbox -> operation.copy(
                actorIncarnationId = outbox.actorIncarnationId, replicationLaneId = outbox.replicationLaneId,
                sequence = outbox.sequence, logicalClock = outbox.sequence, operationType = outbox.mutationType,
                causalContextJson = outbox.causalContextJson,
                payloadJson = operationBuilder.resolvePayloadReferences(outbox.syncSpaceId, outbox.payloadJson),
            ) }
        val storedCandidates = if (isSnapshot) emptyList() else readerDatabase.syncInboxDao()
            .listFieldCandidates(operation.syncSpaceId).filter {
                it.entityType == operation.entityType && it.entitySyncId == operation.entitySyncId &&
                    it.entityGeneration == operation.entityGeneration
            }.map { me.ash.reader.infrastructure.sync.core.SyncFieldCandidate(
                it.fieldId, it.valueJson, it.versionToken, SyncVersionToken.source(it.versionToken),
                causalContextJson = it.causalContextJson, logicalClock = it.logicalClock ?: 0L,
            ) }
        if (!isSnapshot) SyncPayloadMerge.candidates(retained + local + operation).forEach { (field, candidates) ->
            candidates.forEach { candidate ->
                val dot = checkNotNull(SyncVersionToken.parseOperationDot(candidate.token))
                readerDatabase.syncInboxDao().upsertFieldCandidate(me.ash.reader.infrastructure.sync.core.SyncFieldCandidateEntity(
                    SyncFieldVersionEntity(operation.syncSpaceId, operation.entityType, operation.entitySyncId, field,
                        operation.entityGeneration, candidate.token,
                        SyncOperationCanonicalizer.operationId(operation.syncSpaceId, dot.actorIncarnationId, dot.replicationLaneId, dot.sequence),
                        candidate.valueJson, System.currentTimeMillis(), candidate.causalContextJson, candidate.logicalClock)))
            }
        }
        val winners = if (isSnapshot) emptyMap() else SyncPayloadMerge.resolve(retained + local + operation, storedCandidates)
        winners.forEach { (field, winner) ->
            val dot = SyncVersionToken.parseOperationDot(winner.token)
            readerDatabase.syncInboxDao().upsertFieldVersion(SyncFieldVersionEntity(
                operation.syncSpaceId, operation.entityType, operation.entitySyncId, field,
                operation.entityGeneration, winner.token,
                dot?.let { SyncOperationCanonicalizer.operationId(operation.syncSpaceId, it.actorIncarnationId, it.replicationLaneId, it.sequence) },
                winner.valueJson, System.currentTimeMillis(),
                winner.causalContextJson, winner.logicalClock,
            ))
        }
        val incomingPayload = json.parseToJsonElement(operation.payloadJson).jsonObject
        val mergedPayload = if (isSnapshot) operation.payloadJson else if (operation.operationType == SyncMutationType.FIELD_SET.name) {
            val field = incomingPayload.getValue("field").jsonPrimitive.content
            JsonObject(incomingPayload + ("value" to json.parseToJsonElement(winners.getValue(field).valueJson))).toString()
        } else JsonObject(winners.mapValues { json.parseToJsonElement(it.value.valueJson) }).toString()
        if (previous?.materialized == true) {
            // Chat and Reader live in separate Room databases. Chat may have committed this
            // operation while the outer Reader apply transaction was lost to a process crash.
            // Replaying the operation must therefore rebuild the Reader-owned Blob graph before
            // treating the already-materialized Chat mutation as complete.
            materializePayload(operation.copy(payloadJson = mergedPayload))
            return
        }
        val payload = materializePayload(operation.copy(payloadJson = mergedPayload))
        withContext(prepareReaderReferences(operation.syncSpaceId, payload)) {
            chatDatabase.withTransaction {
                when (type) {
                    SyncEntityType.CONVERSATION -> applyConversation(operation, payload)
                    SyncEntityType.CONVERSATION_ARTICLE -> applyConversationArticle(operation, payload)
                    SyncEntityType.MESSAGE -> applyMessage(operation, payload)
                    SyncEntityType.TOOL_CALL -> applyToolCall(operation, payload)
                    SyncEntityType.CONTEXT_REF -> applyContextRef(operation, payload)
                    SyncEntityType.EVIDENCE_BLOCK -> applyEvidenceBlock(operation, payload)
                    SyncEntityType.CITATION_REF -> applyCitationRef(operation, payload)
                    SyncEntityType.CITATION_ANNOTATION -> applyCitationAnnotation(operation, payload)
                    SyncEntityType.CITATION_ANNOTATION_REF -> applyCitationAnnotationRef(operation, payload)
                    else -> throw SyncApplyDeferredException("Unsupported AI_HISTORY entity")
                }
                if (!isSnapshot) journal.insert(LlmSyncApplyJournalEntity(operation.operationId, operation.syncSpaceId,
                    operation.entityType, operation.entitySyncId, operation.entityGeneration, checkNotNull(encoded)))
                else journal.resetMaterialization(operation.syncSpaceId, operation.entityType, operation.entitySyncId)
            }
        }
    }

    private suspend fun applyConversation(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        val mapping = ensureOwnerMapping(operation)
        val dao = chatDatabase.chatDao()
        if (operation.operationType == SyncMutationType.FIELD_SET.name) {
            val existing = dao.getConversation(mapping.localId)
                ?: throw SyncApplyDeferredException("Missing Conversation row for FIELD_SET")
            val value = payload.string("value")
            val updated = when (payload.string("field")) {
                "title" -> existing.copy(title = value ?: "New chat")
                "providerId" -> existing.copy(providerId = value)
                "model" -> existing.copy(model = value)
                "skillId" -> existing.copy(skillId = value)
                else -> throw SyncApplyDeferredException("Unsupported Conversation FIELD_SET")
            }
            dao.updateConversation(updated)
            return
        }
        requireMutation(operation, SyncMutationType.UPSERT)
        val incoming =
            LlmConversationEntity(
                id = mapping.localId,
                title = payload.string("title") ?: "New chat",
                providerId = payload.string("providerId"),
                model = payload.string("model"),
                skillId = payload.string("skillId"),
                articleId = optionalLocalId(operation.syncSpaceId, SyncEntityType.ARTICLE, payload.string("articleSyncId")),
                articleTitle = payload.string("articleTitle"),
                articleLink = payload.string("articleLink"),
                createdAt = payload.long("createdAt") ?: System.currentTimeMillis(),
                updatedAt = payload.long("updatedAt") ?: System.currentTimeMillis(),
            )
        if (dao.getConversation(mapping.localId) == null) {
            dao.insertConversation(incoming)
        } else {
            dao.updateConversation(incoming)
        }
    }

    private suspend fun applyMessage(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        val mapping = ensureOwnerMapping(operation)
        val dao = chatDatabase.chatDao()
        if (operation.operationType == SyncMutationType.FIELD_SET.name) {
            require(payload.string("field") == "historyActive") { "Unsupported Message FIELD_SET" }
            val existing =
                dao.getMessageById(mapping.localId)
                    ?: throw SyncApplyDeferredException("Missing Message row for FIELD_SET")
            dao.updateMessage(
                existing.copy(
                    historyActive = payload.boolean("value") ?: existing.historyActive,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return
        }
        requireMutation(operation, SyncMutationType.UPSERT)
        val conversationId =
            requireLocalId(operation.syncSpaceId, SyncEntityType.CONVERSATION, payload.string("conversationSyncId"))
        val incoming =
            LlmMessageEntity(
                id = mapping.localId,
                conversationId = conversationId,
                role = enumValue(payload.string("role"), LlmChatRole.ASSISTANT),
                content = payload.string("content").orEmpty(),
                requestTask = enumValueOrNull<LlmExecutionTask>(payload.string("requestTask")),
                reasoning = payload.string("reasoning"),
                status = enumValue(payload.string("status"), LlmMessageStatus.COMPLETE),
                errorMessage = payload.string("errorMessage"),
                historyActive = payload.boolean("historyActive") ?: true,
                webSearchStatus = enumValueOrNull<WebSearchRequestStatus>(payload.string("webSearchStatus")),
                webSearchQuery = payload.string("webSearchQuery"),
                webSearchProviderName = payload.string("webSearchProviderName"),
                webSearchErrorMessage = payload.string("webSearchErrorMessage"),
                promptTokens = payload.int("promptTokens"),
                completionTokens = payload.int("completionTokens"),
                durationMs = payload.long("durationMs"),
                tokenUsageEstimated = payload.boolean("tokenUsageEstimated") ?: false,
                createdAt = payload.long("createdAt") ?: System.currentTimeMillis(),
                updatedAt = payload.long("updatedAt") ?: System.currentTimeMillis(),
            )
        if (dao.getMessageById(mapping.localId) == null) {
            dao.insertMessage(incoming)
        } else {
            dao.updateMessage(incoming)
        }
    }

    private suspend fun applyToolCall(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        requireMutation(operation, SyncMutationType.UPSERT)
        val mapping = ensureOwnerMapping(operation)
        val dao = chatDatabase.chatDao()
        val incoming =
            LlmToolCallEntity(
                id = mapping.localId,
                conversationId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.CONVERSATION,
                        payload.string("conversationSyncId"),
                    ),
                assistantMessageId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.MESSAGE,
                        payload.string("assistantMessageSyncId"),
                    ),
                providerCallId = payload.string("providerCallId") ?: mapping.localId,
                toolId = payload.string("toolId").orEmpty(),
                toolName = payload.string("toolName"),
                toolSourceId = payload.string("toolSourceId"),
                apiName = payload.string("apiName").orEmpty(),
                argumentsJson = payload.string("argumentsJson") ?: "{}",
                status = enumValue(payload.string("status"), LlmToolCallStatus.ERROR),
                resultContent = payload.string("resultContent"),
                errorMessage = payload.string("errorMessage"),
                createdAt = payload.long("createdAt") ?: System.currentTimeMillis(),
                updatedAt = payload.long("updatedAt") ?: System.currentTimeMillis(),
            )
        if (dao.getToolCallById(mapping.localId) == null) {
            dao.insertToolCalls(listOf(incoming))
        } else {
            dao.updateToolCall(incoming)
        }
    }

    private suspend fun applyContextRef(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        requireMutation(operation, SyncMutationType.UPSERT)
        val mapping = ensureOwnerMapping(operation)
        val dao = chatDatabase.chatDao()
        val incoming =
            LlmContextRefEntity(
                id = mapping.localId,
                conversationId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.CONVERSATION,
                        payload.string("conversationSyncId"),
                    ),
                assistantMessageId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.MESSAGE,
                        payload.string("assistantMessageSyncId"),
                    ),
                contextId = payload.string("contextId") ?: mapping.localId,
                type = enumValue(payload.string("type"), LlmContextType.MANUAL),
                title = payload.string("title"),
                sourceId = payload.string("sourceId"),
                articleId = optionalLocalId(operation.syncSpaceId, SyncEntityType.ARTICLE, payload.string("articleSyncId")),
                sourceUrl = payload.string("sourceUrl"),
                contentSnapshot = payload.string("contentSnapshot").orEmpty(),
                promptContentSnapshot = payload.string("promptContentSnapshot"),
                contentSha256 = payload.string("contentSha256").orEmpty(),
                priority = payload.int("priority") ?: 0,
                includedInPrompt = payload.boolean("includedInPrompt") ?: false,
                truncatedInPrompt = payload.boolean("truncatedInPrompt") ?: false,
                citationIndex = payload.int("citationIndex"),
                createdAt = payload.long("createdAt") ?: System.currentTimeMillis(),
            )
        if (dao.getContextRefById(mapping.localId) == null) {
            dao.insertContextRefs(listOf(incoming))
        } else {
            dao.updateContextRef(incoming)
        }
    }

    private suspend fun applyEvidenceBlock(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        requireMutation(operation, SyncMutationType.UPSERT)
        val mapping = ensureOwnerMapping(operation)
        val dao = chatDatabase.chatDao()
        val incoming =
            LlmEvidenceBlockEntity(
                id = mapping.localId,
                contextRefId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.CONTEXT_REF,
                        payload.string("contextRefSyncId"),
                    ),
                stableLocatorKey = payload.string("stableLocatorKey") ?: mapping.localId,
                kind = enumValue(payload.string("kind"), LlmEvidenceBlockKind.PARAGRAPH),
                ordinal = payload.int("ordinal") ?: 0,
                textSnapshot = payload.string("textSnapshot").orEmpty(),
                normalizedSha256 = payload.string("normalizedSha256").orEmpty(),
                locator = materializeLocator(operation.syncSpaceId, payload["locator"] as? JsonObject ?: JsonObject(emptyMap())),
                schemaVersion = payload.int("schemaVersion") ?: LLM_EVIDENCE_SCHEMA_VERSION,
                createdAt = payload.long("createdAt") ?: System.currentTimeMillis(),
            )
        if (dao.getEvidenceBlockById(mapping.localId) == null) {
            dao.insertEvidenceBlocks(listOf(incoming))
        } else {
            dao.updateEvidenceBlock(incoming)
        }
    }

    private suspend fun applyCitationRef(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        requireMutation(operation, SyncMutationType.UPSERT)
        val mapping = ensureOwnerMapping(operation)
        val dao = chatDatabase.chatDao()
        val targetKind = enumValue(payload.string("targetKind"), LlmCitationTargetKind.CONTEXT_REF)
        val evidenceBlockId =
            if (targetKind == LlmCitationTargetKind.EVIDENCE_BLOCK) {
                requireLocalId(
                    operation.syncSpaceId,
                    SyncEntityType.EVIDENCE_BLOCK,
                    payload.string("evidenceBlockSyncId"),
                )
            } else {
                null
            }
        val incoming =
            LlmCitationRefEntity(
                id = mapping.localId,
                conversationId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.CONVERSATION,
                        payload.string("conversationSyncId"),
                    ),
                assistantMessageId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.MESSAGE,
                        payload.string("assistantMessageSyncId"),
                    ),
                contextRefId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.CONTEXT_REF,
                        payload.string("contextRefSyncId"),
                    ),
                evidenceBlockId = evidenceBlockId,
                targetKind = targetKind,
                protocolId = payload.string("protocolId") ?: mapping.localId,
                displayOrder = payload.int("displayOrder"),
                quoteSnapshot = payload.string("quoteSnapshot").orEmpty(),
                sourceUrl = payload.string("sourceUrl"),
                locatorSnapshot =
                    (payload["locator"] as? JsonObject)?.let {
                        materializeLocator(operation.syncSpaceId, it)
                    },
                schemaVersion = payload.int("schemaVersion") ?: LLM_CITATION_SCHEMA_VERSION,
                createdAt = payload.long("createdAt") ?: System.currentTimeMillis(),
            )
        if (dao.getCitationRefById(mapping.localId) == null) {
            dao.insertCitationRefs(listOf(incoming))
        } else {
            dao.updateCitationRef(incoming)
        }
    }

    private suspend fun applyCitationAnnotation(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        requireMutation(operation, SyncMutationType.UPSERT)
        val mapping = ensureOwnerMapping(operation)
        val dao = chatDatabase.chatDao()
        val incoming =
            LlmCitationAnnotationEntity(
                id = mapping.localId,
                conversationId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.CONVERSATION,
                        payload.string("conversationSyncId"),
                    ),
                assistantMessageId =
                    requireLocalId(
                        operation.syncSpaceId,
                        SyncEntityType.MESSAGE,
                        payload.string("assistantMessageSyncId"),
                    ),
                canonicalInsertionOffset = payload.int("canonicalInsertionOffset") ?: 0,
                occurrenceOrdinal = payload.int("occurrenceOrdinal") ?: 0,
                schemaVersion = payload.int("schemaVersion") ?: LLM_CITATION_ANNOTATION_SCHEMA_VERSION,
                createdAt = payload.long("createdAt") ?: System.currentTimeMillis(),
            )
        if (dao.getCitationAnnotationById(mapping.localId) == null) {
            dao.insertCitationAnnotations(listOf(incoming))
        } else {
            dao.updateCitationAnnotation(incoming)
        }
    }

    private suspend fun applyConversationArticle(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        requireMutation(operation, SyncMutationType.RELATION_SET)
        val conversationId =
            requireLocalId(operation.syncSpaceId, SyncEntityType.CONVERSATION, payload.string("conversationSyncId"))
        val articleId =
            requireLocalId(operation.syncSpaceId, SyncEntityType.ARTICLE, payload.string("articleSyncId"))
        ensureOwnerMapping(
            operation,
            SyncCanonicalIdentity.relationLocalId(
                SyncEntityType.CONVERSATION_ARTICLE,
                conversationId,
                articleId,
            ),
        )
        chatDatabase.chatDao().insertConversationArticles(
            listOf(
                LlmConversationArticleEntity(
                    conversationId = conversationId,
                    articleId = articleId,
                    title = payload.string("title").orEmpty(),
                    link = payload.string("link"),
                    originalContent = payload.string("originalContent").orEmpty(),
                    summary = payload.string("summary"),
                    position = payload.int("position") ?: 0,
                    createdAt = payload.long("createdAt") ?: System.currentTimeMillis(),
                )
            )
        )
    }

    private suspend fun applyCitationAnnotationRef(
        operation: SyncOperationEntity,
        payload: JsonObject,
    ) {
        requireMutation(operation, SyncMutationType.RELATION_SET)
        val annotationId =
            requireLocalId(
                operation.syncSpaceId,
                SyncEntityType.CITATION_ANNOTATION,
                payload.string("annotationSyncId"),
            )
        val citationRefId =
            requireLocalId(
                operation.syncSpaceId,
                SyncEntityType.CITATION_REF,
                payload.string("citationRefSyncId"),
            )
        ensureOwnerMapping(
            operation,
            SyncCanonicalIdentity.relationLocalId(
                SyncEntityType.CITATION_ANNOTATION_REF,
                annotationId,
                citationRefId,
            ),
        )
        chatDatabase.chatDao().insertCitationAnnotationRefReplace(
            LlmCitationAnnotationRefEntity(
                annotationId = annotationId,
                citationRefId = citationRefId,
                refOrdinal = payload.int("refOrdinal") ?: 0,
            )
        )
    }

    private suspend fun applyDelete(
        operation: SyncOperationEntity,
        type: SyncEntityType,
        versionTokenOverride: String? = null,
        deletedAtOverride: Long? = null,
    ) {
        val mapping =
            chatDatabase.syncIdentityMappingDao()
                .findBySyncId(operation.syncSpaceId, type.wireName, operation.entitySyncId)
        if (mapping != null && mapping.generation > operation.entityGeneration) return
        readerDatabase.syncInboxDao().upsertTombstone(
            SyncTombstoneEntity(
                syncSpaceId = operation.syncSpaceId,
                entityType = operation.entityType,
                entitySyncId = operation.entitySyncId,
                entityGeneration = operation.entityGeneration,
                versionToken =
                    versionTokenOverride
                        ?: SyncVersionToken.operation(
                            operation.actorIncarnationId,
                            operation.replicationLaneId,
                            operation.sequence,
                        ),
                sourceOperationId = operation.operationId,
                updatedAt = deletedAtOverride ?: System.currentTimeMillis(),
            )
        )
        blobState.removeOwnerReferences(
            syncSpaceId = operation.syncSpaceId,
            lane = operation.replicationLaneId,
            ownerEntityType = operation.entityType,
            ownerEntitySyncId = operation.entitySyncId,
            ownerEntityGeneration = operation.entityGeneration,
        )
        if (mapping == null) return
        val cleanup = BlobCleanupPlan()
        withContext(cleanup) {
            chatDatabase.withTransaction {
                val receipt = chatDatabase.syncApplyJournalDao().find(operation.operationId)
                if (receipt != null) cleanup.owners += json.decodeFromString<List<BlobCleanupOwner>>(receipt.readerBlobCleanupJson)
                when (type) {
                    SyncEntityType.CONVERSATION -> removeConversationBlobReferences(operation.syncSpaceId, mapping.localId)
                    SyncEntityType.MESSAGE -> removeMessageBlobReferences(operation.syncSpaceId, mapping.localId)
                    SyncEntityType.CONTEXT_REF -> {
                        chatDatabase.chatDao().getEvidenceBlocksForContextRef(mapping.localId).forEach {
                            removeMappedBlobReferences(operation.syncSpaceId, SyncEntityType.EVIDENCE_BLOCK, it.id)
                        }
                        chatDatabase.chatDao().getCitationRefsForContext(mapping.localId).forEach {
                            removeMappedBlobReferences(operation.syncSpaceId, SyncEntityType.CITATION_REF, it.id)
                        }
                    }
                    SyncEntityType.EVIDENCE_BLOCK -> chatDatabase.chatDao().getCitationRefsForEvidence(mapping.localId).forEach {
                        removeMappedBlobReferences(operation.syncSpaceId, SyncEntityType.CITATION_REF, it.id)
                    }
                    else -> Unit
                }
                val dao = chatDatabase.chatDao()
                when (type) {
                    SyncEntityType.CONVERSATION -> {
                        dao.getConversation(mapping.localId)?.let { dao.deleteConversation(it) }
                    }
                    SyncEntityType.MESSAGE -> {
                        dao.deleteMessage(mapping.localId)
                    }
                    SyncEntityType.TOOL_CALL -> dao.deleteToolCallById(mapping.localId)
                    SyncEntityType.CONTEXT_REF -> dao.deleteContextRefById(mapping.localId)
                    SyncEntityType.EVIDENCE_BLOCK -> dao.deleteEvidenceBlockById(mapping.localId)
                    SyncEntityType.CITATION_REF -> dao.deleteCitationRefById(mapping.localId)
                    SyncEntityType.CITATION_ANNOTATION -> dao.deleteCitationAnnotationById(mapping.localId)
                    SyncEntityType.CONVERSATION_ARTICLE -> deleteConversationArticle(mapping.localId)
                    SyncEntityType.CITATION_ANNOTATION_REF -> deleteAnnotationRef(mapping.localId)
                    else -> Unit
                }
                val wire = if (operation.operationId.startsWith("snapshot-")) "{}"
                    else SyncOperationWireCodec.encode(SyncOperationWireCodec.toWire(operation))
                chatDatabase.syncApplyJournalDao().insert(LlmSyncApplyJournalEntity(operation.operationId,
                    operation.syncSpaceId, operation.entityType, operation.entitySyncId, operation.entityGeneration,
                    wire, readerBlobCleanupJson = json.encodeToString(cleanup.owners.distinct())))
            }
        }
        // The receipt survives Chat commit. If Reader rolls back, retry repeats this exact plan
        // even though cascading deletes have already removed the rows used to discover it.
        cleanup.owners.distinct().forEach { owner ->
            blobState.removeOwnerReferences(operation.syncSpaceId, "AI_HISTORY", owner.type, owner.syncId, owner.generation)
        }
    }

    @Serializable
    private data class BlobCleanupOwner(val type: String, val syncId: String, val generation: Long)

    private class BlobCleanupPlan : AbstractCoroutineContextElement(Key) {
        val owners = mutableListOf<BlobCleanupOwner>()
        companion object Key : CoroutineContext.Key<BlobCleanupPlan>
    }

    private suspend fun materializePayload(operation: SyncOperationEntity): JsonObject {
        val payload = json.parseToJsonElement(operation.payloadJson).jsonObject.toMutableMap()
        SyncBlobPayloadCodec.references(operation.payloadJson).forEach { ref ->
            val bytes = localBlobStore.readVerified(ref.manifest.hash)
            blobState.registerManifest(
                ref.manifest,
                if (bytes == null) SyncBlobAvailabilityState.BLOB_MISSING else SyncBlobAvailabilityState.READY,
            )
            blobState.replaceOwnerReference(
                syncSpaceId = operation.syncSpaceId,
                lane = operation.replicationLaneId,
                ownerEntityType = operation.entityType,
                ownerEntitySyncId = operation.entitySyncId,
                ownerEntityGeneration = operation.entityGeneration,
                referenceKind = ref.referenceKind,
                hash = ref.manifest.hash,
            )
            if (bytes == null || bytes.size.toLong() != ref.manifest.totalBytes) {
                blobState.markMissing(ref.manifest.hash)
                if (isMetadataFirstAttachment(operation.entityType, ref.referenceKind)) {
                    // Keep the graph/materialized metadata visible. The Blob owner reference above
                    // is the durable refill pointer used when the attachment arrives later.
                    return@forEach
                }
                throw SyncApplyDeferredException("BLOB_MISSING:" + ref.manifest.hash)
            }
            blobState.markReadyVerified(ref.manifest.hash, bytes.size.toLong())
            payload[ref.field] = JsonPrimitive(bytes.toString(Charsets.UTF_8))
        }
        return JsonObject(payload)
    }

    private fun isMetadataFirstAttachment(entityType: String, referenceKind: String): Boolean =
        when (entityType) {
            SyncEntityType.CONTEXT_REF.wireName ->
                referenceKind == "context_snapshot" || referenceKind == "context_prompt_snapshot"
            SyncEntityType.EVIDENCE_BLOCK.wireName -> referenceKind == "evidence_text"
            SyncEntityType.CITATION_REF.wireName -> referenceKind == "citation_quote"
            else -> false
        }

    private suspend fun ensureOwnerMapping(
        operation: SyncOperationEntity,
        preferredLocalId: String? = null,
    ): SyncIdentityMappingEntity {
        val dao = chatDatabase.syncIdentityMappingDao()
        val existing =
            dao.findBySyncId(operation.syncSpaceId, operation.entityType, operation.entitySyncId)
        if (existing != null) {
            if (operation.entityGeneration < existing.generation) {
                throw SyncApplyDeferredException("Stale AI_HISTORY generation")
            }
            if (operation.entityGeneration > existing.generation) {
                val updated =
                    existing.copy(
                        generation = operation.entityGeneration,
                        updatedAt = System.currentTimeMillis(),
                    )
                dao.update(updated)
                return updated
            }
            return existing
        }
        val now = System.currentTimeMillis()
        return SyncIdentityMappingEntity(
            syncSpaceId = operation.syncSpaceId,
            entityType = operation.entityType,
            localId = preferredLocalId ?: UUID.randomUUID().toString(),
            syncId = operation.entitySyncId,
            generation = operation.entityGeneration,
            createdAt = now,
            updatedAt = now,
        ).also { dao.insert(it) }
    }

    private suspend fun requireLocalId(
        syncSpaceId: String,
        entityType: SyncEntityType,
        syncId: String?,
    ): String {
        if (syncId.isNullOrBlank()) {
            throw SyncApplyDeferredException("Missing " + entityType.wireName + " Sync ID dependency")
        }
        return optionalLocalId(syncSpaceId, entityType, syncId)
                ?: throw SyncApplyDeferredException(
                    "Missing " + entityType.wireName + " mapping " + syncId
                )
    }

    private suspend fun optionalLocalId(
        syncSpaceId: String,
        entityType: SyncEntityType,
        syncId: String?,
    ): String? {
        if (syncId.isNullOrBlank()) return null
        if (entityType == SyncEntityType.ARTICLE) {
            currentCoroutineContext()[PreparedReaderReferences]?.let { prepared ->
                check(prepared.syncSpaceId == syncSpaceId)
                return prepared.articleIds[syncId]
            }
        }
        return mappingDao(entityType)
            .findBySyncId(syncSpaceId, entityType.wireName, syncId)
            ?.localId
    }

    private class PreparedReaderReferences(
        val syncSpaceId: String,
        val articleIds: Map<String, String?>,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<PreparedReaderReferences>
    }

    private suspend fun prepareReaderReferences(space: String, payload: JsonObject): PreparedReaderReferences {
        val ids = linkedSetOf<String>()
        fun collect(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if (key == "articleSyncId") (child as? JsonPrimitive)?.contentOrNull?.let(ids::add)
                    collect(child)
                }
                is JsonArray -> value.forEach(::collect)
                else -> Unit
            }
        }
        collect(payload)
        val mappings = ids.associateWith { id ->
            readerDatabase.syncIdentityMappingDao().findBySyncId(space, SyncEntityType.ARTICLE.wireName, id)?.localId
        }
        return PreparedReaderReferences(space, mappings)
    }

    private fun mappingDao(entityType: SyncEntityType): SyncIdentityMappingDao =
        if (entityType == SyncEntityType.ARTICLE) {
            readerDatabase.syncIdentityMappingDao()
        } else {
            chatDatabase.syncIdentityMappingDao()
        }

    private suspend fun resolvePayloadReferences(
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
                JsonObject(
                    buildMap {
                        element.forEach { (key, value) ->
                            if (root && key == "id") return@forEach
                            val entityType = localReferenceType(key)
                            if (entityType != null && value is JsonPrimitive && value.isString) {
                                val mapping =
                                    mappingDao(entityType)
                                        .findByLocalId(syncSpaceId, entityType.wireName, value.content)
                                checkNotNull(mapping) {
                                    "Missing " + entityType.wireName + " Sync ID for local reference " + value.content
                                }
                                put(syncReferenceKey(key), JsonPrimitive(mapping.syncId))
                            } else {
                                put(key, resolveElement(syncSpaceId, value))
                            }
                        }
                    }
                )
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

    private suspend fun materializeLocator(
        syncSpaceId: String,
        value: JsonObject,
    ): LlmEvidenceLocatorV1 =
        LlmEvidenceLocatorV1(
            version = value.int("version") ?: 1,
            sourceKind = enumValue(value.string("sourceKind"), LlmEvidenceSourceKind.ARTICLE),
            stableLocatorKey = value.string("stableLocatorKey"),
            blockIndex = value.int("blockIndex"),
            headingPath =
                (value["headingPath"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            articleId = optionalLocalId(syncSpaceId, SyncEntityType.ARTICLE, value.string("articleSyncId")),
            sourceUrl = value.string("sourceUrl"),
            toolCallId = optionalLocalId(syncSpaceId, SyncEntityType.TOOL_CALL, value.string("toolCallSyncId")),
            toolId = value.string("toolId"),
            toolName = value.string("toolName"),
            toolSourceId = value.string("toolSourceId"),
            normalizedHash = value.string("normalizedHash").orEmpty(),
        )

    private suspend fun removeConversationBlobReferences(
        syncSpaceId: String,
        conversationId: String,
    ) {
        chatDatabase.chatDao().getMessages(conversationId).forEach { message ->
            removeMessageBlobReferences(syncSpaceId, message.id)
        }
        chatDatabase.chatDao().getConversationArticles(conversationId).forEach { relation ->
            removeMappedBlobReferences(
                syncSpaceId,
                SyncEntityType.CONVERSATION_ARTICLE,
                SyncCanonicalIdentity.relationLocalId(
                    SyncEntityType.CONVERSATION_ARTICLE,
                    relation.conversationId,
                    relation.articleId,
                ),
            )
        }
    }

    private suspend fun removeMessageBlobReferences(
        syncSpaceId: String,
        messageId: String,
    ) {
        val dao = chatDatabase.chatDao()
        removeMappedBlobReferences(syncSpaceId, SyncEntityType.MESSAGE, messageId)
        dao.getCitationRefsForAssistant(messageId).forEach {
            removeMappedBlobReferences(syncSpaceId, SyncEntityType.CITATION_REF, it.id)
        }
        dao.getContextRefsForAssistant(messageId).forEach { context ->
            removeMappedBlobReferences(syncSpaceId, SyncEntityType.CONTEXT_REF, context.id)
            dao.getEvidenceBlocksForContextRef(context.id).forEach { evidence ->
                removeMappedBlobReferences(syncSpaceId, SyncEntityType.EVIDENCE_BLOCK, evidence.id)
            }
        }
        val conversationId = dao.getMessageById(messageId)?.conversationId
        if (conversationId != null) {
            dao.getToolCalls(conversationId)
                .filter { it.assistantMessageId == messageId }
                .forEach { removeMappedBlobReferences(syncSpaceId, SyncEntityType.TOOL_CALL, it.id) }
        }
    }

    private suspend fun removeMappedBlobReferences(
        syncSpaceId: String,
        type: SyncEntityType,
        localId: String,
    ) {
        val mapping =
            chatDatabase.syncIdentityMappingDao()
                .findByLocalId(syncSpaceId, type.wireName, localId)
                ?: return
        checkNotNull(currentCoroutineContext()[BlobCleanupPlan]).owners +=
            BlobCleanupOwner(type.wireName, mapping.syncId, mapping.generation)
    }

    private suspend fun deleteConversationArticle(relationLocalId: String) {
        chatDatabase.chatDao().getAllConversationArticles().firstOrNull { relation ->
            SyncCanonicalIdentity.relationLocalId(
                SyncEntityType.CONVERSATION_ARTICLE,
                relation.conversationId,
                relation.articleId,
            ) == relationLocalId
        }?.let { chatDatabase.chatDao().deleteConversationArticle(it.conversationId, it.articleId) }
    }

    private suspend fun deleteAnnotationRef(relationLocalId: String) {
        chatDatabase.chatDao().getAllCitationAnnotationRefs().firstOrNull { relation ->
            SyncCanonicalIdentity.relationLocalId(
                SyncEntityType.CITATION_ANNOTATION_REF,
                relation.annotationId,
                relation.citationRefId,
            ) == relationLocalId
        }?.let {
            chatDatabase.chatDao().deleteCitationAnnotationRef(it.annotationId, it.citationRefId)
        }
    }

    private fun requireMutation(
        operation: SyncOperationEntity,
        expected: SyncMutationType,
    ) {
        if (operation.operationType != expected.name) {
            throw SyncApplyDeferredException(
                operation.entityType + " requires " + expected.name
            )
        }
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private inline fun <reified T : Enum<T>> enumValue(
    value: String?,
    fallback: T,
): T =
    value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
    value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmSyncBusinessProjectionModule {
    @Binds
    @IntoSet
    abstract fun bindProjection(
        implementation: LlmSyncBusinessProjection,
    ): SyncBusinessProjectionExtension
}
