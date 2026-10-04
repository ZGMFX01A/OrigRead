package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.infrastructure.db.AndroidDatabase

/** Reader journal 是跨库发布 authority；已验证页面先持久化，再提交可发送的快照身份。 */
class SyncPagedGenesisPublication @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val join: SyncSpaceJoinBaselineCapture,
) {
    @Inject lateinit var extensions: Set<@JvmSuppressWildcards SyncBusinessProjectionExtension>
    data class Options(val accountId: Int, val session: SyncGenesisSessionEntity,
        val cut: SyncGenesisCut, val manifest: SyncPagedSnapshotManifest)
    private val json = Json { encodeDefaults = true }

    /** 扩展 inclusion 可幂等重试；Reader 的 bundle、入组尾部和完成标志在同一事务发布。 */
    suspend fun publish(options: Options) {
        val manifest = options.manifest
        check(store.find(manifest.snapshotBundleId)?.state == "VERIFIED") { "Snapshot pages are not verified for publication" }
        extensions.forEach { it.markPagedGenesisIncluded(SyncProjectionGenesisInclusion(manifest.syncSpaceId,
            options.cut.laneFrontiers, manifest.capturedAt)) }
        if (options.session.genesisSessionId.startsWith("join-")) captureJoin(options)
        database.withTransaction {
            database.syncGenesisDao().upsertBundle(SyncSnapshotBundleEntity(snapshotBundleId = manifest.snapshotBundleId,
                syncSpaceId = manifest.syncSpaceId, snapshotClass = manifest.snapshotClass, schemaVersion = manifest.formatVersion,
                snapshotEpoch = INITIAL_SNAPSHOT_EPOCH, crossDbCutId = manifest.crossDbCutId, replicationPolicyHash = manifest.policyHash,
                requiredCoreShardIdsJson = json.encodeToString(manifest.requiredCoreShardIds), shardDescriptorsJson = json.encodeToString(manifest.lanes),
                authStabilityCheckpointId = manifest.authStabilityCheckpoint, rootHash = manifest.rootHash,
                createdByDeviceId = manifest.authorDeviceId, createdAt = manifest.capturedAt))
            markReaderIncluded(options)
            database.syncGenesisDao().upsertSession(options.session.copy(state = SyncGenesisStage.SNAPSHOT_BUILT.name,
                crossDbCutId = manifest.crossDbCutId, cutFrontierJson = SyncGenesisCodec.encodeFrontiers(options.cut.laneFrontiers),
                snapshotBundleId = manifest.snapshotBundleId, updatedAt = manifest.capturedAt))
        }
    }

    /** inclusion 在数据库内按真实 cut 前缀更新，不能加载所有 Outbox 或给洞外操作补覆盖度。 */
    private fun markReaderIncluded(options: Options) {
        val sql = database.openHelper.writableDatabase
        for ((lane, actors) in options.cut.laneFrontiers) for ((actor, prefix) in actors) {
            sql.execSQL("""INSERT OR REPLACE INTO sync_genesis_operation_coverage(operationId,genesisSessionId,includedAt)
                SELECT o.operationId,?,? FROM sync_operation_log o JOIN sync_outbox b
                  ON b.syncSpaceId=o.syncSpaceId AND b.actorIncarnationId=o.actorIncarnationId
                  AND b.replicationLaneId=o.replicationLaneId AND b.sequence=o.sequence
                WHERE o.syncSpaceId=? AND o.replicationLaneId=? AND o.actorIncarnationId=? AND o.sequence<=?
                  AND b.status IN ('PENDING_BUILD','BUILT') AND b.genesisIncludedAt IS NULL""",
                arrayOf(options.cut.genesisSessionId, options.manifest.capturedAt, options.cut.syncSpaceId, lane, actor, prefix))
            sql.execSQL("""UPDATE sync_outbox SET status='BUILT',genesisIncludedAt=?,updatedAt=?
                WHERE syncSpaceId=? AND replicationLaneId=? AND actorIncarnationId=? AND sequence<=?
                  AND status IN ('PENDING_BUILD','BUILT') AND genesisIncludedAt IS NULL""",
                arrayOf(options.manifest.capturedAt, options.manifest.capturedAt, options.cut.syncSpaceId, lane, actor, prefix))
        }
    }

    /** 按实体生成新空间操作；AI 完成 journal 使用稳定实体 key，Reader 回滚不会重复分配 Chat dot。 */
    private suspend fun captureJoin(options: Options) {
        val manifest = options.manifest
        val actor = requireNotNull(database.syncRuntimeDao().findActiveActor(manifest.syncSpaceId))
        val context = SyncWritableActorContext(localAccountId = options.accountId, syncSpaceId = manifest.syncSpaceId,
            deviceId = manifest.authorDeviceId, actorIncarnationId = actor.actorIncarnationId,
            lifecycleState = SyncSpaceLifecycleState.GENESIS_CAPTURING)
        for (record in store.records(SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, kind = "ENTITY"))) {
            val type = record.value.getValue("entityType").jsonPrimitive.content
            if (type == "sync_core") continue
            val lane = SyncReplicationLane.entries.single { it.wireName == SyncSnapshotRecordLane.entityLane(type) }
            val id = record.value.getValue("entitySyncId").jsonPrimitive.content
            val generation = record.value.getValue("generation").jsonPrimitive.long
            val versions = record.value.getValue("fields").jsonObject.mapNotNull { (field, value) ->
                if (type == "article" && field == "fullContentHash" && value == JsonNull) return@mapNotNull null
                val register = if (type == "article" && field == "fullContentHash") SYNC_ARTICLE_FULL_CONTENT_FIELD else field
                GenesisFieldVersionSnapshot(entitySyncId = id, fieldId = register, valueJson = value.toString(),
                    versionToken = SyncVersionToken.genesis(manifest.genesisBaselineId, lane.wireName, id, register),
                    entityType = type, entityGeneration = generation)
            }
            join.capture(SyncSpaceJoinBaselineCapture.JoinBaselineLane(context = context, lane = lane,
                baselineId = "${options.session.genesisSessionId}:${SyncOperationCanonicalizer.sha256Hex(record.key)}", winners = versions))
        }
    }

    companion object {
        /** 初次快照 epoch 与历史协议一致，分页 formatVersion 另行声明。 */
        private const val INITIAL_SNAPSHOT_EPOCH = 1L
    }
}
