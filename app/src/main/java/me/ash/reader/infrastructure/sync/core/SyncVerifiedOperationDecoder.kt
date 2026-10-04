package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile

/** 同一原操作在多个快照字段中重复出现，只复用完整 wire 与公钥相同的纯验签结果。 */
@Singleton
class SyncVerifiedOperationDecoder @Inject constructor(private val keys: SyncDeviceSigningKeyStore, private val store: SyncPagedSnapshotStore) {
    private data class Verified(val envelope: SyncOperationEnvelope, val publicKey: String, val operation: SyncOperationEntity)
    @Volatile private var previous: Verified? = null

    /** 不缓存成员授权或稳定 checkpoint；调用方仍须逐次核对当前 AUTH 和 actor 隔离状态。 */
    fun decode(envelope: SyncOperationEnvelope, publicKey: String, receivedAt: Long): SyncOperationEntity {
        val cached = previous
        if (cached?.envelope == envelope && cached.publicKey == publicKey) {
            return cached.operation.copy(createdAt = receivedAt, updatedAt = receivedAt)
        }
        val sourceKey = SyncOperationCanonicalizer.sha256Hex(SyncOperationWireCodec.encode(envelope))
        val keyDigest = SyncOperationCanonicalizer.sha256Hex(publicKey)
        val proved = store.database.rawQuery("""SELECT 1 FROM sync_snapshot_source_proof p JOIN sync_snapshot_source s ON s.source_key=p.source_key
            WHERE p.source_key=? AND p.public_key_digest=? AND p.validator_version=?""",
            arrayOf(sourceKey, keyDigest, SIGNATURE_VALIDATOR_VERSION.toString())).use { it.moveToFirst() }
        if (proved) return SyncOperationWireCodec.fromVerifiedWire(envelope, receivedAt)
        // fromWire 完整校验 canonical payload、hash、Dot 和 signingDigest，然后验证原作者签名。
        val operation = SyncOperationWireCodec.fromWire(envelope, receivedAt)
        check(keys.verifyBase64(publicKey, SyncOperationCanonicalizer.signingMaterial(operation).toByteArray(Charsets.UTF_8),
            envelope.authorSignature)) { "AUTH_FAILED: invalid author signature" }
        SyncSnapshotTrace.add(SyncSnapshotTrace.Work(signatureChecks = 1L))
        previous = Verified(envelope, publicKey, operation)
        // 证明仅随真实来源保存，来源回收或修改会由同库触发器使它失效。
        store.database.execSQL("""INSERT OR IGNORE INTO sync_snapshot_source_proof SELECT ?,?,?
            WHERE EXISTS(SELECT 1 FROM sync_snapshot_source WHERE source_key=?)""",
            arrayOf(sourceKey, keyDigest, SIGNATURE_VALIDATOR_VERSION, sourceKey))
        return operation
    }

    companion object {
        /** 完整格式、载荷承诺和验签版本；规则变化必须递增。 */
        private const val SIGNATURE_VALIDATOR_VERSION = 1
    }
}
