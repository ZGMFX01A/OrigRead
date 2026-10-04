package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 类别晋升生成新固定身份，原 WORKING 页面和 root 永远不原地改写。 */
class SyncPagedSnapshotPromotion @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val keys: SyncDeviceSigningKeyStore,
) {
    @Inject lateinit var exporter: SyncPagedSnapshotExport
    data class Options(val source: SyncPagedSnapshotManifest, val snapshotClass: String, val checkpointId: String, val now: Long)

    /** 最终恢复 ID 在 OWNER 签发 acceptance 前计算，checkpoint ID 不参与这一身份。 */
    fun variantId(source: SyncPagedSnapshotManifest, snapshotClass: String): String {
        require(snapshotClass in setOf(SyncSnapshotClass.GC_BASELINE.name, SyncSnapshotClass.BOOTSTRAP_RECOVERY.name))
        val identity = buildJsonObject {
            put("sourceSnapshotBundleId", source.snapshotBundleId)
            put("policyHash", source.policyHash)
            put("snapshotClass", snapshotClass)
        }
        return "snapshot:variant:" + SyncGenesisCodec.hashCanonicalJson(identity.toString())
    }

    /** 页面在专用库逐行复制，Reader 只保存晋升后的真实逻辑清单。 */
    suspend fun promote(options: Options): SyncPagedSnapshotManifest {
        val source = options.source
        check(source.snapshotClass == SyncSnapshotClass.WORKING.name) { "REBASE_UNSAFE: promotion requires WORKING source" }
        check(exporter.manifest(SyncPagedSnapshotExport.Scope(source.snapshotBundleId)) == source) { "SNAPSHOT_CONFLICT: promotion source changed" }
        val id = variantId(source, options.snapshotClass)
        verifyCheckpoint(options, id)
        val unsigned = source.copy(snapshotBundleId = id, snapshotClass = options.snapshotClass,
            authStabilityCheckpoint = options.checkpointId,
            coverageCommitment = if (options.snapshotClass == SyncSnapshotClass.BOOTSTRAP_RECOVERY.name)
                SyncSnapshotWireCodec.coverageCommitment(source.coverage) else null, rootHash = "", authorSignature = "")
        val rooted = unsigned.copy(rootHash = SyncPagedSnapshotWire.rootHash(unsigned))
        val manifest = store.publishedManifest(rooted) ?: rooted.copy(authorSignature = keys.signBase64(rooted.authorDeviceId,
            SyncPagedSnapshotWire.signingMaterial(rooted).toByteArray(Charsets.UTF_8)))
        val origin = checkNotNull(database.syncGenesisDao().findBundle(source.sourceSnapshotBundleId))
        database.withTransaction {
            store.copyScope(SyncPagedSnapshotStore.Scope(source.snapshotBundleId, manifest, options.now))
            database.syncGenesisDao().upsertBundle(origin.copy(snapshotBundleId = id, snapshotClass = options.snapshotClass,
                authStabilityCheckpointId = options.checkpointId, rootHash = manifest.rootHash,
                replicationPolicyHash = manifest.policyHash, shardDescriptorsJson = Json.encodeToString(manifest.lanes), createdAt = options.now))
        }
        return manifest
    }

    /** 当前 verified AUTH 必须支配全部真实前缀，BOOTSTRAP acceptance 必须命名最终 ID。 */
    private suspend fun verifyCheckpoint(options: Options, bundleId: String) {
        val source = options.source
        val checkpoint = database.syncAuthLedgerDao().list(source.syncSpaceId).map { SyncAuthWireCodec.decode(it.authObjectJson) }
            .lastOrNull { it.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT }
        check(checkpoint?.authObjectId == options.checkpointId) { "REBASE_UNSAFE: promotion requires current verified checkpoint" }
        val payload = Json.parseToJsonElement(checkNotNull(checkpoint).payloadJson).jsonObject
        val accepted = checkNotNull(payload["acceptedPrefixByActorLane"]).jsonObject
        check(source.coverage.all { (lane, actors) -> actors.all { (actor, prefix) ->
            (accepted[lane]?.jsonObject?.get(actor)?.jsonPrimitive?.long ?: 0L) >= prefix
        } }) { "REBASE_UNSAFE: Snapshot exceeds stable authorized coverage" }
        if (options.snapshotClass == SyncSnapshotClass.BOOTSTRAP_RECOVERY.name) check(
            payload["acceptedSnapshotBundleId"]?.jsonPrimitive?.content == bundleId
        ) { "REBASE_UNSAFE: OWNER acceptance does not name final recovery identity" }
        requireStableEffects(source)
    }

    /** 稳定化不能只看快照类别；覆盖内仍为 provisional 的已应用 effect 必须先完成 AUTH 稳定化。 */
    private fun requireStableEffects(source: SyncPagedSnapshotManifest) {
        val sql = database.openHelper.writableDatabase
        for ((lane, actors) in source.coverage) for ((actor, prefix) in actors) {
            sql.query("SELECT 1 FROM sync_inbox_operation WHERE syncSpaceId=? AND replicationLaneId=? AND actorIncarnationId=? AND sequence<=? AND state='APPLIED' AND authorizationState='PROVISIONAL_AUTHORIZED' LIMIT 1",
                arrayOf(source.syncSpaceId, lane, actor, prefix)).use {
                check(!it.moveToFirst()) { "REBASE_UNSAFE: Snapshot contains provisional effects" }
            }
        }
    }
}
