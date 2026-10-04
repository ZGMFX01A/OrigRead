package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 入组后的固定 Genesis 视图转为目标空间的新操作，不发送旧空间的历史或身份。 */
class SyncSpaceJoinBaselineCapture @Inject constructor(
    private val database: AndroidDatabase,
    private val allocator: SyncOutboxAllocator,
    private val extensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension> = emptySet(),
) {
    private val json = Json { encodeDefaults = true }
    // 协议引用种类对应的业务字段；未知种类明确报错，不能丢弃附件后宣称入组完成。
    private val referenceFields = mapOf(
        "article_full_content" to SYNC_ARTICLE_FULL_CONTENT_FIELD,
        "context_snapshot" to "contentSnapshot", "context_prompt_snapshot" to "promptContentSnapshot",
        "evidence_text" to "textSnapshot", "citation_quote" to "quoteSnapshot",
    )

    /** 固定视图在事务外准备，每个实体在其所属数据库独立提交，不能跨库持有写锁。 */
    internal suspend fun capture(options: JoinBaselineLane) {
        check(!database.inTransaction()) { "JOIN_BASELINE_REQUIRES_TRANSACTION_FREE_CALLER" }
        val groups = options.winners.groupBy { Triple(checkNotNull(it.entityType), it.entitySyncId, it.entityGeneration ?: 0L) }
        val extensionDrafts = mutableListOf<SyncOutboxDraft>()
        val observed = database.syncInboxDao().listCoverage(options.context.syncSpaceId).map {
            SyncAppliedFrontierEntity(syncSpaceId = it.syncSpaceId, replicationLaneId = it.replicationLaneId,
                actorIncarnationId = it.actorIncarnationId, appliedPrefix = it.appliedPrefix, updatedAt = it.updatedAt)
        }
        for ((identity, versions) in groups) {
            val fields = JsonObject(versions.associate { it.fieldId to json.parseToJsonElement(it.valueJson) })
            val refs = blobReferences(options.context.syncSpaceId, options.lane, identity)
            val payload = buildJsonObject {
                put("fields", fields)
                if (refs.isNotEmpty()) put("blobRefs", json.encodeToJsonElement(ListSerializer(SyncPayloadBlobRefWire.serializer()), refs))
            }
            val draft = SyncOutboxDraft(entityType = identity.first, entitySyncId = identity.second, entityGeneration = identity.third,
                mutationType = SyncMutationType.UPSERT, payloadJson = payload.toString())
            if (options.lane == SyncReplicationLane.AI_HISTORY) { extensionDrafts += draft; continue }
            persistReader(ReaderBaseline(options, draft, fields, observed))
        }
        if (extensionDrafts.isNotEmpty()) {
            val owner = extensions.single { extension -> extensionDrafts.all { extension.owns(it.entityType) } }
            owner.captureJoinBaseline(SyncJoinBaselineInput(options.context, extensionDrafts, options.baselineId, observed))
        }
    }

    private data class ReaderBaseline(val options: JoinBaselineLane, val draft: SyncOutboxDraft,
        val fields: JsonObject, val observed: List<SyncAppliedFrontierEntity>)

    /** 实体基线与回执同库提交；发布回滚后的重试复用原 Dot，不重新分配序列。 */
    private suspend fun persistReader(input: ReaderBaseline) {
        val (options, draft, fields, observed) = input
        val phase = "join-baseline:${draft.entityType}:${draft.entitySyncId}:${draft.entityGeneration}"
        val root = SyncOperationCanonicalizer.sha256Hex(draft.payloadJson)
        database.withTransaction {
            val receipts = database.snapshotInstallProgressDao()
            val receipt = receipts.find(options.baselineId, phase)
            if (receipt != null) {
                check(receipt.rootHash == root) { "JOIN_BASELINE_INPUT_CHANGED" }; return@withTransaction
            }
            val outbox = allocator.allocate(dao = database.syncOutboxDao(), context = options.context, lane = options.lane,
                draft = draft, additionalObservedFrontiers = observed)
            persistFieldVersions(outbox, fields)
            receipts.save(SyncSnapshotInstallProgress(options.baselineId, phase, root, outbox.outboxId))
        }
    }

    /** 新基线操作也是本机已观察的字段版本，后续编辑必须引用这些 Dot。 */
    private suspend fun persistFieldVersions(outbox: SyncOutboxEntity, fields: JsonObject) {
        val token = SyncVersionToken.operation(outbox.actorIncarnationId, outbox.replicationLaneId, outbox.sequence)
        val id = SyncOperationCanonicalizer.operationId(outbox.syncSpaceId, outbox.actorIncarnationId, outbox.replicationLaneId, outbox.sequence)
        fields.forEach { (field, value) ->
            database.syncInboxDao().upsertFieldVersion(SyncFieldVersionEntity(syncSpaceId = outbox.syncSpaceId,
                entityType = outbox.entityType, entitySyncId = outbox.entitySyncId, fieldId = field,
                entityGeneration = outbox.entityGeneration, versionToken = token, sourceOperationId = id,
                valueJson = value.toString(), updatedAt = outbox.createdAt, causalContextJson = outbox.causalContextJson,
                logicalClock = outbox.sequence))
        }
    }

    /** 附件依赖转为正式 blobRefs，保证首次正文和 AI 附件走预约上传而非仅传 hash。 */
    private suspend fun blobReferences(space: String, lane: SyncReplicationLane, identity: Triple<String, String, Long>): List<SyncPayloadBlobRefWire> =
        database.syncBlobDao().listReferencesForOwner(space, lane.wireName, identity.first, identity.second, identity.third).map { ref ->
            val field = checkNotNull(referenceFields[ref.referenceKind]) { "Unknown join baseline Blob reference ${ref.referenceKind}" }
            val manifest = checkNotNull(database.syncBlobDao().findManifest(ref.hash)) { "Join baseline Blob manifest is missing" }
            SyncPayloadBlobRefWire(field, ref.referenceKind, SyncBlobManifestWire(
                hash = manifest.hash, totalBytes = manifest.totalBytes, mediaType = manifest.mediaType,
                compression = manifest.compression, encryptionInfoJson = manifest.encryptionInfoJson,
                availabilityPolicy = manifest.availabilityPolicy, durability = SyncBlobDurability.valueOf(manifest.durability),
            ))
        }

    internal data class JoinBaselineLane(val context: SyncWritableActorContext, val lane: SyncReplicationLane, val baselineId: String,
        val winners: List<GenesisFieldVersionSnapshot>)
}
