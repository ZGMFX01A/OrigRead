package me.ash.reader.llm.chat.data

import javax.inject.Inject
import kotlinx.coroutines.sync.withLock
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.core.SyncSnapshotCancellation

/** 从当前 Reader 引用重建义务，覆盖旧 receipt、尾部新拥有者和先到的文件。 */
internal class LlmSnapshotBodyInventory @Inject constructor(
    private val reader: AndroidDatabase,
    private val chat: LlmChatDatabase,
) {
    private data class Reference(val row: Long, val type: String, val id: String, val generation: Long,
        val kind: String, val hash: String, val bytes: Long)

    /** 每批游标在访问 Chat 前关闭；当前引用的代次必须有真实映射。 */
    suspend fun reconcile(space: String, bundle: String) {
        retireSuperseded(bundle)
        var after = 0L
        while (true) {
            SyncSnapshotCancellation.checkpoint()
            val batch = references(space, after)
            if (batch.isEmpty()) return
            for (ref in batch) reader.syncProjectionMutex.withLock { register(space, bundle, ref) }
            after = batch.last().row
        }
    }

    /** SQL 只读取正文引用元数据，不从 CursorWindow 读取正文或签名载荷。 */
    private fun references(space: String, after: Long): List<Reference> = reader.openHelper.readableDatabase.query(
        """SELECT r.rowid,r.ownerEntityType,r.ownerEntitySyncId,r.ownerEntityGeneration,r.referenceKind,r.hash,m.totalBytes
            FROM sync_blob_reference r LEFT JOIN sync_blob_manifest m ON m.hash=r.hash
            WHERE r.syncSpaceId=? AND r.replicationLaneId='AI_HISTORY' AND r.rowid>?
              AND r.referenceKind IN ('context_snapshot','context_prompt_snapshot','evidence_text','citation_quote')
            ORDER BY r.rowid LIMIT $BATCH_ROWS""", arrayOf(space, after)).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                check(!cursor.isNull(6)) { "SNAPSHOT_BODY_MANIFEST_MISSING" }
                add(Reference(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                    cursor.getString(4), cursor.getString(5), cursor.getLong(6)))
            }
        }
    }

    /** 旧代引用只有在更高代次映射已存在时才退出当前完成集合。 */
    private suspend fun register(space: String, bundle: String, ref: Reference) {
        val deleted = reader.syncInboxDao().findTombstone(space, ref.type, ref.id)
        if ((deleted?.entityGeneration ?: -1L) >= ref.generation) return
        val mapping = checkNotNull(chat.syncIdentityMappingDao().findBySyncId(space, ref.type, ref.id)) { "SNAPSHOT_BODY_OWNER_MISSING" }
        if (mapping.generation > ref.generation) return
        check(mapping.generation == ref.generation) { "SNAPSHOT_BODY_STALE_GENERATION" }
        val owner = me.ash.reader.infrastructure.sync.core.SyncOperationCanonicalizer.sha256Hex("$space\n${ref.type}\n${ref.id}")
        val field = field(ref.kind)
        val current = chat.snapshotBodyDao().owner(owner).firstOrNull { it.field == field }
        check(current == null || current.bundleId != bundle || current.hash == ref.hash || current.generation != ref.generation) {
            "SNAPSHOT_BODY_WINNER_CONFLICT"
        }
        val same = current?.let { it.generation == ref.generation && it.hash == ref.hash && it.totalBytes == ref.bytes } == true
        chat.snapshotBodyDao().save(LlmSnapshotBodyObligation(owner, field, bundle, space, ref.type, ref.id,
            ref.generation, ref.kind, ref.hash, ref.bytes, if (same) current!!.state else "MISSING"))
    }

    /** 删除与更高代次有明确证明时移除旧义务，缺失引用本身不是完成证明。 */
    private suspend fun retireSuperseded(bundle: String) {
        var after = ""
        while (true) {
            val batch = chat.snapshotBodyDao().batch(bundle, after, BATCH_ROWS)
            if (batch.isEmpty()) return
            for (body in batch) reader.syncProjectionMutex.withLock { retire(body) }
            after = batch.last().ownerKey
        }
    }

    /** 相同代次的新 winner 由当前引用重新登记；删除须由墓碑证明。 */
    private suspend fun retire(body: LlmSnapshotBodyObligation) {
        val current = chat.snapshotBodyDao().owner(body.ownerKey).firstOrNull { it.field == body.field }
        if (current != body) return
        val refs = reader.syncBlobDao().listReferencesForOwner(body.space, "AI_HISTORY", body.entityType, body.entitySyncId, body.generation)
        if (refs.any { it.referenceKind == body.referenceKind && it.hash == body.hash }) return
        val mapping = chat.syncIdentityMappingDao().findBySyncId(body.space, body.entityType, body.entitySyncId)
        val deleted = reader.syncInboxDao().findTombstone(body.space, body.entityType, body.entitySyncId)
        check((mapping?.generation ?: -1L) > body.generation ||
            (deleted?.entityGeneration ?: -1L) >= body.generation || refs.any { it.referenceKind == body.referenceKind }) {
            "SNAPSHOT_BODY_REFERENCE_MISSING"
        }
        chat.snapshotBodyDao().removeField(body.ownerKey, body.field)
    }

    /** 四种正文引用对应真实 Chat 列，未知引用显式拒绝。 */
    private fun field(kind: String): String = when (kind) {
        "context_snapshot" -> "contentSnapshot"
        "context_prompt_snapshot" -> "promptContentSnapshot"
        "evidence_text" -> "textSnapshot"
        "citation_quote" -> "quoteSnapshot"
        else -> error("SNAPSHOT_BODY_FIELD_UNSUPPORTED")
    }

    companion object {
        /** 义务扫描只保留一批轻量元数据。 */
        private const val BATCH_ROWS = 256
    }
}
