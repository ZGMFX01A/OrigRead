package me.ash.reader.llm.chat.data

import androidx.room.withTransaction
import javax.inject.Inject
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.core.SyncBlobStateService
import me.ash.reader.infrastructure.sync.core.SyncOperationCanonicalizer
import me.ash.reader.infrastructure.sync.core.SyncOperationEntity
import me.ash.reader.infrastructure.sync.core.SyncOperationWireCodec
import me.ash.reader.infrastructure.sync.core.SyncPagedSnapshotStore
import me.ash.reader.infrastructure.sync.core.SyncTombstoneEntity
import me.ash.reader.infrastructure.sync.core.SyncVersionToken

/** 快照与真实删除操作共享业务删除，不伪造 Operation；跨库清理计划逐拥有者持久化。 */
internal class LlmPagedDeletion @Inject constructor(
    private val chat: LlmChatDatabase,
    private val reader: AndroidDatabase,
    private val store: SyncPagedSnapshotStore,
) {
    @Inject lateinit var rows: LlmCascadeRows
    @Inject lateinit var blobs: SyncBlobStateService
    data class Options(val subject: LlmProjectionSubject, val token: String, val deletedAt: Long, val operation: SyncOperationEntity? = null)

    /** Chat commit 后 Reader 回滚时，重试仍使用删除前保存的每个真实拥有者。 */
    suspend fun delete(options: Options) {
        val subject = options.subject
        val mapping = chat.syncIdentityMappingDao().findBySyncId(subject.syncSpaceId, subject.entityType, subject.entitySyncId)
        if (mapping != null && mapping.generation > subject.entityGeneration) return
        val receipt = options.operation?.operationId ?: "snapshot-delete:" + SyncOperationCanonicalizer.sha256Hex(
            "${subject.syncSpaceId}\n${subject.entityType}\n${subject.entitySyncId}\n${subject.entityGeneration}\n${options.token}")
        reader.syncInboxDao().upsertTombstone(SyncTombstoneEntity(subject.syncSpaceId, subject.entityType,
            subject.entitySyncId, subject.entityGeneration, options.token, options.operation?.operationId, options.deletedAt))
        blobs.removeOwnerReferences(subject.syncSpaceId, "AI_HISTORY", subject.entityType, subject.entitySyncId, subject.entityGeneration)
        if (mapping != null) {
            prepare(receipt, subject, mapping.localId)
            chat.withTransaction {
                rows.roots(subject.entityType, mapping.localId) { rows.delete(it) }
                val wire = options.operation?.let { SyncOperationWireCodec.encode(SyncOperationWireCodec.toWire(it)) } ?: "{}"
                chat.syncApplyJournalDao().insert(LlmSyncApplyJournalEntity(receipt, subject.syncSpaceId,
                    subject.entityType, subject.entitySyncId, subject.entityGeneration, wire))
            }
        }
        clean(receipt, subject.syncSpaceId)
    }

    /** 原始操作只提取真实 token 和身份，签名载荷仅用于完成 journal。 */
    suspend fun operation(operation: SyncOperationEntity) = delete(Options(LlmProjectionSubject.operation(operation),
        SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence),
        System.currentTimeMillis(), operation))

    /** 计划完整持久化后才删除 Chat，捕获失败保持真实业务数据以便重试。 */
    private suspend fun prepare(receipt: String, subject: LlmProjectionSubject, localId: String) {
        // 每个拥有者独立持久化；Room 挂起调用不能跨线程持有原生 SQLite transaction。
        rows.roots(subject.entityType, localId) { root -> rows.visit(root) { row ->
            val owner = chat.syncIdentityMappingDao().findByLocalId(subject.syncSpaceId, row.type, rows.localId(row))
            if (owner != null) store.database.execSQL("INSERT OR IGNORE INTO sync_snapshot_cleanup_owner VALUES(?,?,?,?)",
                arrayOf(receipt, row.type, owner.syncId, owner.generation))
        } }
    }

    /** 每次只处理一个轻量拥有者，计划保留到稳定化以覆盖 Reader 事务回滚。 */
    private suspend fun clean(receipt: String, space: String) {
        store.database.rawQuery("SELECT entity_type,entity_sync_id,generation FROM sync_snapshot_cleanup_owner WHERE receipt_id=?",
            arrayOf(receipt)).use { cursor ->
            while (cursor.moveToNext()) blobs.removeOwnerReferences(space, "AI_HISTORY", cursor.getString(0), cursor.getString(1), cursor.getLong(2))
        }
    }
}
