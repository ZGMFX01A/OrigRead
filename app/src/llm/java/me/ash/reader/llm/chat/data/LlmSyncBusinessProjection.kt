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
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
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
    readerDatabase: AndroidDatabase,
    chatDatabase: LlmChatDatabase,
    private val localBlobStore: SyncLocalBlobStore,
    private val identityBackfill: LlmGenesisIdentityBackfill,
    private val operationBuilder: LlmOperationBuilder,
) : SyncBusinessProjectionExtension {
    private val liveReaderDatabase = readerDatabase
    private val liveChatDatabase = chatDatabase
    private val readerDatabase get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveReaderDatabase)
    private val chatDatabase get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveChatDatabase, "chat")
    @Inject internal lateinit var frozenGenesis: LlmFrozenGenesis
    @Inject internal lateinit var pagedOperationMerge: LlmPagedOperationMerge
    @Inject internal lateinit var pagedDeletion: LlmPagedDeletion
    @Inject internal lateinit var smallFieldUpdate: LlmSmallFieldUpdate
    @Inject lateinit var pagedGenesisSource: LlmGenesisPayloadSource
    @Inject lateinit var pagedGenesisInclusion: LlmPagedGenesisInclusion
    @Inject internal lateinit var snapshotBodies: LlmSnapshotBodies
    @Inject lateinit var joinOutboxAllocator: me.ash.reader.infrastructure.sync.core.SyncOutboxAllocator
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

    override suspend fun prepareRawGenesis(space: String, now: Long) { identityBackfill.backfill(space, now) }
    override suspend fun freezeRawGenesis(cut: me.ash.reader.infrastructure.sync.core.SyncGenesisCut) = frozenGenesis.freeze(cut.crossDbCutId)
    override fun rawGenesisReady(cut: String): Boolean = frozenGenesis.ready(cut)

    /** 已有 Chat 写入完成后围栏才开始破坏性安装，Reader 事务不会跨库等待。 */
    override suspend fun drainSnapshotWriters() = chatDatabase.withTransaction { Unit }
    override suspend fun retireRawGenesis(cut: String) = frozenGenesis.retire(cut)
    override suspend fun openFrozenGenesis(cut: String, progress: me.ash.reader.infrastructure.sync.core.SyncSourceCopyProgress?): Pair<String, androidx.room.RoomDatabase> =
        "chat" to frozenGenesis.open(cut, progress)

    /** AI 入组基线在 Chat DB 原子分配，保持后续 Chat mutation 的序列 authority。 */
    override suspend fun captureJoinBaseline(input: me.ash.reader.infrastructure.sync.core.SyncJoinBaselineInput) {
        val (context, drafts, baselineId, observed) = input
        chatDatabase.withTransaction {
            // 与 AI Outbox 同库提交的完成记录：Reader 回滚/重试不能重复分配已提交的 AI 基线。
            // 使用独立命名空间，不与普通 Genesis 的 crossDbCutId 混用。
            val journalId = "join-baseline:$baselineId"
            val journal = chatDatabase.syncProjectionGenesisCutDao()
            if (journal.listForCut(context.syncSpaceId, journalId).isNotEmpty()) return@withTransaction
            drafts.forEach { draft ->
                check(owns(draft.entityType)) { "Join baseline entity is outside AI_HISTORY" }
                joinOutboxAllocator.allocate(dao = chatDatabase.syncOutboxDao(), context = context,
                    lane = me.ash.reader.infrastructure.sync.core.SyncReplicationLane.AI_HISTORY, draft = draft,
                    additionalObservedFrontiers = observed)
            }
            val writer = checkNotNull(chatDatabase.syncOutboxDao().findWriterState(
                context.syncSpaceId, context.actorIncarnationId, "AI_HISTORY"))
            journal.insertAll(listOf(SyncProjectionGenesisCutEntity(syncSpaceId = context.syncSpaceId,
                crossDbCutId = journalId, replicationLaneId = "AI_HISTORY", actorIncarnationId = context.actorIncarnationId,
                sequence = writer.lastSequence, capturedAt = System.currentTimeMillis())))
        }
    }

    override fun canApplyWithoutBlob(entityType: String, referenceKind: String): Boolean =
        isMetadataFirstAttachment(entityType, referenceKind)

    override fun readLocalBlob(hash: String): ByteArray? =
        localBlobStore.readVerified(hash)

    override fun localBlobFile(hash: String): java.io.File? =
        localBlobStore.getBlobFile(hash)?.takeIf { localBlobStore.verifyFile(hash, it) }

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
        check(bytes.size.toLong() == manifest.totalBytes) { "BLOB_LENGTH_MISMATCH" }
        if (!isMetadataFirstAttachment(entityType, referenceKind)) return
        // 文件准备不占用共同投影屏障；回填器在短临界区重新核对当前义务。
        withContext(me.ash.reader.infrastructure.sync.core.SyncSnapshotAccess.ownedSpace.asContextElement(syncSpaceId)) {
            snapshotBodies.downloaded(LlmSnapshotBodies.Arrival(syncSpaceId, entityType, entitySyncId,
                entityGeneration, referenceKind, manifest.hash, manifest.totalBytes))
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

    /** 分页捕获在既有 Genesis barrier 内逐条输出，不读取全库消息和引用数组。 */
    override suspend fun streamGenesis(options: me.ash.reader.infrastructure.sync.core.SyncProjectionGenesisStream) {
        identityBackfill.backfill(options.syncSpaceId, options.now)
        pagedGenesisSource.forEach { seed ->
            options.consume(prepareGenesisEntity(seed, options))
        }
    }

    /** 分页固定视图 inclusion 使用 Chat DB 的前缀更新，不加载旧全量 Outbox 集合。 */
    override suspend fun markPagedGenesisIncluded(options: me.ash.reader.infrastructure.sync.core.SyncProjectionGenesisInclusion) {
        pagedGenesisInclusion.mark(options)
    }

    /** 引用仍由正式 Sync ID 解析器处理；耐久 Blob 通过文件摘要验证，不复制全部字节。 */
    private suspend fun prepareGenesisEntity(seed: LlmGenesisPayloadSeed,
        options: me.ash.reader.infrastructure.sync.core.SyncProjectionGenesisStream): SyncGenesisProjectionEntity {
        val mapping = chatDatabase.syncIdentityMappingDao().findByLocalId(options.syncSpaceId, seed.type.wireName, seed.localId)
            ?: error("Missing Genesis AI_HISTORY mapping for ${seed.type.wireName}/${seed.localId}")
        val resolved = resolvePayloadReferences(options.syncSpaceId, seed.payloadJson)
        for (ref in SyncBlobPayloadCodec.references(resolved)) {
            // 生产固定捕获只登记引用，发布阶段再沿冻结索引验证全部必需文件。
            if (!options.deferBlobVerification) {
            val file = localBlobStore.getBlobFile(ref.manifest.hash)
                ?: error("Genesis AI_HISTORY Blob is missing: ${ref.manifest.hash}")
            require(file.length() == ref.manifest.totalBytes && localBlobStore.verifyFile(ref.manifest.hash, file)) {
                "Genesis AI_HISTORY Blob size or digest mismatch: ${ref.manifest.hash}"
            }
            }
            blobState.registerManifest(ref.manifest, SyncBlobAvailabilityState.READY, options.now)
            blobState.markReadyVerified(ref.manifest.hash, ref.manifest.totalBytes, options.now)
            blobState.replaceOwnerReference(syncSpaceId = options.syncSpaceId, lane = "AI_HISTORY",
                ownerEntityType = seed.type.wireName, ownerEntitySyncId = mapping.syncId,
                ownerEntityGeneration = mapping.generation, referenceKind = ref.referenceKind,
                hash = ref.manifest.hash, now = options.now)
        }
        return SyncGenesisProjectionEntity(seed.type.wireName, mapping.syncId, mapping.generation,
            SyncOperationCanonicalizer.canonicalJson(resolved))
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

    /** 分页实体使用真实业务身份和跨库完成记录，Reader 回滚后可重建 Blob 图而不重复覆盖 Chat。 */
    override suspend fun materializePagedEntity(options: me.ash.reader.infrastructure.sync.core.SyncPagedProjectionEntity): Boolean {
        val entity = options.entity
        if (!owns(entity.entityType)) return false
        validateSnapshotEntity(options.space, entity)
        val subject = LlmProjectionSubject.snapshot(options.space, entity)
        val payload = materializeSubjectPayload(subject, entity.fieldsJson)
        val receiptId = "snapshot-paged:" + SyncOperationCanonicalizer.sha256Hex(
            "${options.bundleId}\n${options.rootHash}\n${options.recordKey}")
        withContext(prepareReaderReferences(options.space, payload)) {
            chatDatabase.withTransaction {
                val journal = chatDatabase.syncApplyJournalDao()
                snapshotBodies.register(options)
                if (journal.find(receiptId) != null) return@withTransaction
                materializeSubject(subject, payload)
                journal.resetMaterialization(options.space, entity.entityType, entity.entitySyncId)
                journal.insert(LlmSyncApplyJournalEntity(receiptId, options.space, entity.entityType,
                    entity.entitySyncId, entity.generation, "{}"))
            }
        }
        // 实体批次只登记正文义务，屏障外的统一正文阶段负责读取文件并提交实际回填。
        return true
    }

    /** 旧实体 receipt 不代表正文完成，始终复核真实拥有者的字节承诺。 */
    override suspend fun requirePagedBodies(syncSpaceId: String, snapshotBundleId: String) = snapshotBodies.requireComplete(syncSpaceId, snapshotBundleId)

    /** 完整 ENTITY 直接进入正式类型投影，签名与字段候选由 Reader 的分页安装事务恢复。 */
    private suspend fun materializeSubject(subject: LlmProjectionSubject, payload: JsonObject) {
        when (SyncEntityType.fromWireName(subject.entityType)) {
            SyncEntityType.CONVERSATION -> applyConversation(subject, payload)
            SyncEntityType.CONVERSATION_ARTICLE -> applyConversationArticle(subject, payload)
            SyncEntityType.MESSAGE -> applyMessage(subject, payload)
            SyncEntityType.TOOL_CALL -> applyToolCall(subject, payload)
            SyncEntityType.CONTEXT_REF -> applyContextRef(subject, payload)
            SyncEntityType.EVIDENCE_BLOCK -> applyEvidenceBlock(subject, payload)
            SyncEntityType.CITATION_REF -> applyCitationRef(subject, payload)
            SyncEntityType.CITATION_ANNOTATION -> applyCitationAnnotation(subject, payload)
            SyncEntityType.CITATION_ANNOTATION_REF -> applyCitationAnnotationRef(subject, payload)
            else -> throw SyncApplyDeferredException("Unsupported AI_HISTORY Snapshot entity")
        }
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
        val subject = LlmProjectionSubject(syncSpaceId, entityType, entitySyncId, generation, SyncMutationType.GLOBAL_DELETE.name)
        pagedDeletion.delete(LlmPagedDeletion.Options(subject, versionToken, deletedAt))
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
        val previous = if (isSnapshot) null else pagedOperationMerge.journal(operation.operationId)
        val encoded = if (isSnapshot) null else SyncOperationWireCodec.encode(SyncOperationWireCodec.toWire(operation))
        check(previous == null || previous.operationJson == encoded) { "DOT_COLLISION in Chat apply journal" }
        if (operation.operationType == SyncMutationType.GLOBAL_DELETE.name) {
            // The Reader tombstone must be recreated even when Chat already committed before a crash.
            pagedDeletion.operation(operation)
            return
        }

        val winners = if (isSnapshot) emptyMap() else pagedOperationMerge.resolve(operation)
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
                    SyncEntityType.CONVERSATION -> applyConversation(LlmProjectionSubject.operation(operation), payload)
                    SyncEntityType.CONVERSATION_ARTICLE -> applyConversationArticle(LlmProjectionSubject.operation(operation), payload)
                    SyncEntityType.MESSAGE -> applyMessage(LlmProjectionSubject.operation(operation), payload)
                    SyncEntityType.TOOL_CALL -> applyToolCall(LlmProjectionSubject.operation(operation), payload)
                    SyncEntityType.CONTEXT_REF -> applyContextRef(LlmProjectionSubject.operation(operation), payload)
                    SyncEntityType.EVIDENCE_BLOCK -> applyEvidenceBlock(LlmProjectionSubject.operation(operation), payload)
                    SyncEntityType.CITATION_REF -> applyCitationRef(LlmProjectionSubject.operation(operation), payload)
                    SyncEntityType.CITATION_ANNOTATION -> applyCitationAnnotation(LlmProjectionSubject.operation(operation), payload)
                    SyncEntityType.CITATION_ANNOTATION_REF -> applyCitationAnnotationRef(LlmProjectionSubject.operation(operation), payload)
                    else -> throw SyncApplyDeferredException("Unsupported AI_HISTORY entity")
                }
                if (!isSnapshot) journal.insert(LlmSyncApplyJournalEntity(operation.operationId, operation.syncSpaceId,
                    operation.entityType, operation.entitySyncId, operation.entityGeneration, checkNotNull(encoded)))
                else journal.resetMaterialization(operation.syncSpaceId, operation.entityType, operation.entitySyncId)
            }
        }
    }

    private suspend fun applyConversation(
        operation: LlmProjectionSubject,
        payload: JsonObject,
    ) {
        val mapping = ensureOwnerMapping(operation)
        val dao = chatDatabase.chatDao()
        if (operation.operationType == SyncMutationType.FIELD_SET.name) {
            smallFieldUpdate.apply(LlmSmallFieldUpdate.Options("conversation", mapping.localId, payload))
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
        if (!snapshotRowExists("llm_conversations", mapping.localId)) {
            dao.insertConversation(incoming)
        } else {
            dao.updateConversation(incoming)
        }
    }

    private suspend fun applyMessage(
        operation: LlmProjectionSubject,
        payload: JsonObject,
    ) {
        val mapping = ensureOwnerMapping(operation)
        val dao = chatDatabase.chatDao()
        if (operation.operationType == SyncMutationType.FIELD_SET.name) {
            smallFieldUpdate.apply(LlmSmallFieldUpdate.Options("message", mapping.localId, payload))
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
        if (!snapshotRowExists("llm_messages", mapping.localId)) {
            dao.insertMessage(incoming)
        } else {
            dao.updateMessage(incoming)
        }
    }

    private suspend fun applyToolCall(
        operation: LlmProjectionSubject,
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
        if (!snapshotRowExists("llm_tool_calls", mapping.localId)) {
            dao.insertToolCalls(listOf(incoming))
        } else {
            dao.updateToolCall(incoming)
        }
    }

    private suspend fun applyContextRef(
        operation: LlmProjectionSubject,
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
        if (!snapshotRowExists("llm_context_refs", mapping.localId)) {
            dao.insertContextRefs(listOf(incoming))
        } else {
            dao.updateContextRef(incoming)
        }
    }

    private suspend fun applyEvidenceBlock(
        operation: LlmProjectionSubject,
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
        if (!snapshotRowExists("llm_evidence_blocks", mapping.localId)) {
            dao.insertEvidenceBlocks(listOf(incoming))
        } else {
            dao.updateEvidenceBlock(incoming)
        }
    }

    private suspend fun applyCitationRef(
        operation: LlmProjectionSubject,
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
        if (!snapshotRowExists("llm_citation_refs", mapping.localId)) {
            dao.insertCitationRefs(listOf(incoming))
        } else {
            dao.updateCitationRef(incoming)
        }
    }

    private suspend fun applyCitationAnnotation(
        operation: LlmProjectionSubject,
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
        if (!snapshotRowExists("llm_citation_annotations", mapping.localId)) {
            dao.insertCitationAnnotations(listOf(incoming))
        } else {
            dao.updateCitationAnnotation(incoming)
        }
    }

    private suspend fun applyConversationArticle(
        operation: LlmProjectionSubject,
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
        operation: LlmProjectionSubject,
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

    private suspend fun materializePayload(operation: SyncOperationEntity): JsonObject =
        materializeSubjectPayload(LlmProjectionSubject.operation(operation), operation.payloadJson)

    /** Blob 物化只需要真实 owner 身份，Snapshot 不补造任何操作因果信息。 */
    private suspend fun materializeSubjectPayload(operation: LlmProjectionSubject, payloadJson: String): JsonObject {
        val payload = json.parseToJsonElement(payloadJson).jsonObject.toMutableMap()
        SyncBlobPayloadCodec.references(payloadJson).forEach { ref ->
            val bytes = localBlobStore.readVerified(ref.manifest.hash)
            blobState.registerManifest(
                ref.manifest,
                if (bytes == null) SyncBlobAvailabilityState.BLOB_MISSING else SyncBlobAvailabilityState.READY,
            )
            blobState.replaceOwnerReference(
                syncSpaceId = operation.syncSpaceId,
                lane = "AI_HISTORY",
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
        operation: LlmProjectionSubject,
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

    private fun requireMutation(
        operation: LlmProjectionSubject,
        expected: SyncMutationType,
    ) {
        if (operation.operationType != expected.name) {
            throw SyncApplyDeferredException(
                operation.entityType + " requires " + expected.name
            )
        }
    }

    /** 表名全部来自本类静态调用；存在性检查不读回可能巨大的消息、工具结果或 Evidence 文本。 */
    private fun snapshotRowExists(table: String, id: String): Boolean =
        chatDatabase.openHelper.writableDatabase.query("SELECT 1 FROM $table WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
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
