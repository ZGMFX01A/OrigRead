package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 先完成签名、账本和稳定性证明核对，Snapshot 永远不能自己授予权限。 */
class SyncPagedInstallValidation @Inject constructor(
    private val database: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
    private val keys: SyncDeviceSigningKeyStore,
) {
    @Inject lateinit var auth: AndroidSyncAuthLedgerService
    @Inject lateinit var remote: SyncRemoteApplyCoordinator
    @Inject lateinit var operationEvidence: SyncPagedOperationEvidence
    private val json = Json { encodeDefaults = true }
    private val proofs = mutableMapOf<String, String>()

    /** 作者必须仍拥有当前空间 grant，持久页清单必须与验签清单完全相同。 */
    suspend fun verify(manifest: SyncPagedSnapshotManifest) = verifyWithEvidence(manifest, operationEvidence::verify)

    /** 调用方已持有当前数据库的投影屏障，AUTH 写入不会穿插同一次字段验证。 */
    internal suspend fun verifyWithinProjectionBarrier(manifest: SyncPagedSnapshotManifest, forceEvidence: Boolean = false) =
        verifyWithEvidence(manifest, operationEvidence::verifyWithinAuthorityBarrier, forceEvidence)
    /** 发布事务只比较已有证明；失效必须退出，不允许回退到全字段/全图验证。 */
    internal suspend fun verifyWithinDatabaseTransaction(manifest: SyncPagedSnapshotManifest, forceEvidence: Boolean = false) {
        check(!forceEvidence) { "REVALIDATION_REQUIRED: evidence cannot run inside publication transaction" }
        val proof = proof(manifest)
        check(store.reusable(manifest) && synchronized(proofs) { proofs[manifest.snapshotBundleId] } == proof) {
            "REVALIDATION_REQUIRED: Snapshot authority or derived index changed before publication"
        }
    }

    /** proof 仍绑定完整当前 authority；每个字段的身份、值及前驱检查保持独立执行。 */
    private suspend fun verifyWithEvidence(manifest: SyncPagedSnapshotManifest,
        evidence: suspend (SyncPagedSnapshotManifest) -> Unit, forceEvidence: Boolean = false) {
        check(!database.inTransaction()) { "Snapshot full verification cannot inherit Reader transaction" }
        repeat(SNAPSHOT_PUBLICATION_ATTEMPTS) {
            SyncSnapshotCancellation.checkpoint()
            try { prepareProof(manifest, evidence, forceEvidence); return }
            catch (error: IllegalStateException) {
                // 只有修订竞争重做事务外证明，签名、撤销和持久化错误照常暴露。
                if (!snapshotRevisionConflict(error)) throw error
            }
        }
        snapshotNeedsStableInput()
    }

    /** 完整证明涵盖作者检查至字段验证的同一次修订，而非只覆盖末尾扫描。 */
    private suspend fun prepareProof(manifest: SyncPagedSnapshotManifest,
        evidence: suspend (SyncPagedSnapshotManifest) -> Unit, forceEvidence: Boolean) {
        remote.prepareSnapshotAuthority(manifest.syncSpaceId)
        verifyAuthor(manifest)
        val stage = store.find(manifest.snapshotBundleId)
        check(stage?.state == "VERIFIED" && stage.manifestJson ==
            SyncOperationCanonicalizer.canonicalJson(json.encodeToString(manifest))) { "SNAPSHOT_CORRUPTED: durable Snapshot index differs" }
        verifyStability(manifest)
        val proof = proof(manifest)
        if (!forceEvidence && store.reusable(manifest) && synchronized(proofs) { proofs[manifest.snapshotBundleId] } == proof) return
        synchronized(proofs) { proofs.remove(manifest.snapshotBundleId) }
        // 重启不沿用内存证明，重新核对固定页字节及派生索引，再登记当前修订。
        store.verifyAndPublish(manifest, System.currentTimeMillis())
        val prepared = proof(manifest)
        verifyAuthor(manifest)
        verifyStability(manifest)
        SyncSnapshotTrace.suspendPhase("verify.auth", manifest.snapshotBundleId) { verifyAuth(manifest) }
        SyncSnapshotTrace.phase("verify.graph", manifest.snapshotBundleId) { SyncPagedEntityGraph.requireComplete(store, manifest.snapshotBundleId) }
        SyncSnapshotTrace.suspendPhase("verify.field_evidence", manifest.snapshotBundleId) { evidence(manifest) }
        check(proof(manifest) == prepared) { "REVALIDATION_REQUIRED: authority or index changed during evidence preparation" }
        synchronized(proofs) { proofs[manifest.snapshotBundleId] = prepared }
    }

    /** 当前权限、actor 归属和绑定都参与证明；不把普通 UI 日志变动当作失效。 */
    private suspend fun proof(manifest: SyncPagedSnapshotManifest): String {
        val revision = database.snapshotAuthorityRevisionDao().revision(manifest.syncSpaceId) ?: 0L
        return manifest.rootHash + ":" + revision + ":" + remote.authorityRevision(manifest.syncSpaceId) + ":" + store.derivedRevision(manifest.snapshotBundleId)
    }

    /** 收页及导出逐请求重新检查作者权限，接收初始清单时还没有完整页索引。 */
    suspend fun verifyAuthor(manifest: SyncPagedSnapshotManifest) {
        val peer = remote.trustedPeer(manifest.syncSpaceId, manifest.authorDeviceId)
            ?: error("REBASE_UNSAFE: Snapshot author is not trusted")
        check(auth.findActiveGrant(manifest.syncSpaceId, manifest.authorDeviceId) != null) {
            "REBASE_UNSAFE: Snapshot author has no active grant"
        }
        SyncPagedSnapshotWire.verifyManifest(manifest, peer, keys)
    }

    /** 单条 AUTH 对象与已验签账本精确相等，缺失对象在安装前失败。 */
    private suspend fun verifyAuth(manifest: SyncPagedSnapshotManifest) {
        var rootFound = false
        for (record in store.records(SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, "AUTH", "AUTH_OBJECT"))) {
            val value = record.value
            val local = database.syncAuthLedgerDao().find(manifest.syncSpaceId, value.getValue("authObjectId").jsonPrimitive.content)
                ?: error("REBASE_UNSAFE: Snapshot AUTH object is absent from verified ledger")
            // 正式 codec 统一省略字段与显式 null，同时保留原签名和完整授权内容比较。
            check(SyncAuthWireCodec.encode(SyncAuthWireCodec.decode(local.authObjectJson)) ==
                SyncAuthWireCodec.encode(SyncAuthWireCodec.decode(value.toString()))) {
                "REBASE_UNSAFE: Snapshot AUTH differs from verified ledger"
            }
            rootFound = rootFound || value.getValue("objectType").jsonPrimitive.content == "SPACE_ROOT"
        }
        check(rootFound) { "SNAPSHOT_CORRUPTED: Snapshot has no SPACE_ROOT" }
    }

    /** 稳定快照及 Recovery 的接受对象只能引用最终不可变 bundle 身份。 */
    private suspend fun verifyStability(manifest: SyncPagedSnapshotManifest) {
        if (manifest.snapshotClass == "WORKING") return
        val checkpointId = checkNotNull(manifest.authStabilityCheckpoint) { "REBASE_UNSAFE: stable Snapshot lacks checkpoint" }
        val row = database.syncAuthLedgerDao().find(manifest.syncSpaceId, checkpointId)
            ?: error("REBASE_UNSAFE: stable Snapshot checkpoint is not verified locally")
        val checkpoint = SyncAuthWireCodec.decode(row.authObjectJson)
        check(checkpoint.objectType == SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT) { "REBASE_UNSAFE: wrong checkpoint object type" }
        val payload = json.parseToJsonElement(checkpoint.payloadJson).jsonObject
        val accepted = payload.getValue("acceptedPrefixByActorLane").jsonObject
        check(manifest.coverage.all { (lane, actors) -> actors.all { (actor, prefix) ->
            (accepted[lane]?.jsonObject?.get(actor)?.jsonPrimitive?.long ?: 0L) >= prefix } }) {
            "REBASE_UNSAFE: Snapshot exceeds accepted stable history"
        }
        if (manifest.snapshotClass != "BOOTSTRAP_RECOVERY") return
        check(payload.getValue("acceptedSnapshotBundleId").jsonPrimitive.content == manifest.snapshotBundleId &&
            manifest.coverageCommitment == SyncSnapshotWireCodec.coverageCommitment(manifest.coverage)) {
            "REBASE_UNSAFE: OWNER acceptance differs from immutable Recovery Snapshot"
        }
    }
}
