package me.ash.reader.llm.chat.data

import androidx.room.withTransaction
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.core.SyncBlobPayloadCodec
import me.ash.reader.infrastructure.sync.core.SyncLocalBlobStore
import me.ash.reader.infrastructure.sync.core.SyncOperationCanonicalizer
import me.ash.reader.infrastructure.sync.core.SyncPagedProjectionEntity
import kotlinx.coroutines.sync.withLock

/** 正文义务与 Chat 业务数据同库提交，文件可以先到，拥有者可以后到。 */
internal class LlmSnapshotBodies @Inject constructor(
    private val chat: LlmChatDatabase,
    private val reader: AndroidDatabase,
    private val blobs: SyncLocalBlobStore,
) {
    @Inject lateinit var materializer: LlmSnapshotBodyMaterializer
    @Inject lateinit var inventory: LlmSnapshotBodyInventory
    @Inject lateinit var policy: me.ash.reader.infrastructure.sync.core.SyncLocalLanePolicy

    data class Arrival(val space: String, val type: String, val id: String, val generation: Long,
        val referenceKind: String, val hash: String, val totalBytes: Long)

    /** 文件先落盘，再在投影屏障内重新检查 owner、winner 和代次；尚未出现 owner 时保留文件。 */
    suspend fun downloaded(input: Arrival) {
        val owner = ownerKey(input.space, input.type, input.id)
        if (reader.syncProjectionMutex.withLock { registerArrival(input, owner) }) fillOwner(owner)
    }

    /** 当前策略和引用在短屏障内读取，文件准备由调用方在屏障外执行。 */
    private suspend fun registerArrival(input: Arrival, owner: String): Boolean {
        if (policy.read(input.space)["AI_HISTORY"] in setOf("PAUSED", "UNSUPPORTED", "LOCAL_PURGE")) return false
        val deleted = reader.syncInboxDao().findTombstone(input.space, input.type, input.id)
        if ((deleted?.entityGeneration ?: -1L) >= input.generation) return false
        val refs = reader.syncBlobDao().listReferencesForOwner(input.space, "AI_HISTORY", input.type, input.id, input.generation)
        if (refs.none { it.referenceKind == input.referenceKind && it.hash == input.hash }) return false
        val mapping = chat.syncIdentityMappingDao().findBySyncId(input.space, input.type, input.id) ?: return false
        if (mapping.generation != input.generation) return false
        val field = when (input.referenceKind) {
            "context_snapshot" -> "contentSnapshot"
            "context_prompt_snapshot" -> "promptContentSnapshot"
            "evidence_text" -> "textSnapshot"
            "citation_quote" -> "quoteSnapshot"
            else -> return false
        }
        val existing = chat.snapshotBodyDao().owner(owner).firstOrNull { it.field == field }
        val body = LlmSnapshotBodyObligation(owner, field, existing?.bundleId ?: "", input.space, input.type, input.id,
            input.generation, input.referenceKind, input.hash, input.totalBytes, "VERIFIED_FILE")
        chat.snapshotBodyDao().save(body)
        return true
    }

    /** 即使跨库 receipt 已存在，也从固定实体载荷重建当前正文义务。 */
    suspend fun register(options: SyncPagedProjectionEntity) {
        val entity = options.entity
        for (ref in SyncBlobPayloadCodec.references(entity.fieldsJson).filter { it.referenceKind in BODY_KINDS }) {
            chat.snapshotBodyDao().save(LlmSnapshotBodyObligation(ownerKey(options.space, entity.entityType, entity.entitySyncId),
                ref.field, options.bundleId, options.space, entity.entityType, entity.entitySyncId, entity.generation,
                ref.referenceKind, ref.manifest.hash, ref.manifest.totalBytes, "MISSING"))
        }
    }

    /** 文件验证在屏障外执行；每条实际提交单独取得短投影屏障。 */
    suspend fun fillOwner(owner: String) {
        check(!reader.inTransaction()) { "Snapshot body materialization cannot inherit Reader transaction" }
        for (body in chat.snapshotBodyDao().owner(owner)) {
            try {
                fillBody(body)
            } catch (error: Exception) {
                // 当前义务失败显式落盘；取消继续传播，已经完成的 Chat 批次保持可恢复。
                if (error is kotlinx.coroutines.CancellationException) throw error
                recordFailure(body)
                throw error
            }
        }
    }

    /** 旧任务只能更新仍与自己相同的义务，不能用 FAILED 回写覆盖新 winner。 */
    private suspend fun recordFailure(body: LlmSnapshotBodyObligation) = reader.syncProjectionMutex.withLock {
        val current = chat.snapshotBodyDao().owner(body.ownerKey).firstOrNull { it.field == body.field }
        if (current?.generation == body.generation && current.hash == body.hash && current.totalBytes == body.totalBytes &&
            current.bundleId == body.bundleId) chat.snapshotBodyDao().save(current.copy(state = "FAILED"))
    }

    /** 字节、严格 UTF-8 解码和 Reader 引用检查完成后，只提交 Chat 正文与同库回执。 */
    private suspend fun fillBody(body: LlmSnapshotBodyObligation) {
        val bytes = blobs.readVerified(body.hash) ?: return
        check(bytes.size.toLong() == body.totalBytes) { "SNAPSHOT_BODY_LENGTH_MISMATCH" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        reader.syncProjectionMutex.withLock {
            requireCurrent(body)
            chat.withTransaction {
                materializer.write(body, text)
                materializer.requireText(body, text)
                chat.snapshotBodyDao().save(body.copy(state = "MATERIALIZED"))
            }
        }
        materializer.requireBody(body)
    }

    /** 策略、删除、代次和 winner 与实际正文提交在同一短屏障内核对。 */
    private suspend fun requireCurrent(body: LlmSnapshotBodyObligation) {
        check(policy.read(body.space)["AI_HISTORY"] !in setOf("PAUSED", "UNSUPPORTED", "LOCAL_PURGE")) { "SNAPSHOT_BODY_POLICY_PAUSED" }
        val deleted = reader.syncInboxDao().findTombstone(body.space, body.entityType, body.entitySyncId)
        check((deleted?.entityGeneration ?: -1L) < body.generation) { "SNAPSHOT_BODY_OWNER_DELETED" }
        val mapping = chat.syncIdentityMappingDao().findBySyncId(body.space, body.entityType, body.entitySyncId)
        check(mapping?.generation == body.generation) { "SNAPSHOT_BODY_STALE_GENERATION" }
        val refs = reader.syncBlobDao().listReferencesForOwner(body.space, "AI_HISTORY", body.entityType, body.entitySyncId, body.generation)
        check(refs.count { it.referenceKind == body.referenceKind } == 1 &&
            refs.any { it.referenceKind == body.referenceKind && it.hash == body.hash }) { "SNAPSHOT_BODY_WINNER_CHANGED" }
        val obligation = chat.snapshotBodyDao().owner(body.ownerKey).firstOrNull { it.field == body.field }
        check(obligation?.generation == body.generation && obligation?.hash == body.hash) { "SNAPSHOT_BODY_OBLIGATION_CHANGED" }
    }

    /** 完成前逐拥有者补齐并校验真实正文，合法零字节正文仍按其摘要判断。 */
    suspend fun requireComplete(space: String, bundle: String) {
        inventory.reconcile(space, bundle)
        var after = ""
        while (true) {
            val batch = chat.snapshotBodyDao().batch(bundle, after, OWNER_BATCH_ROWS)
            if (batch.isEmpty()) return
            for (owner in batch.map { it.ownerKey }.distinct()) fillOwner(owner)
            for (body in batch) {
                val actual = chat.snapshotBodyDao().owner(body.ownerKey).first { it.field == body.field }
                check(actual.state == "MATERIALIZED") { "SNAPSHOT_BODY_PENDING: ${actual.entityType}/${actual.entitySyncId}/${actual.field}" }
                materializer.requireBody(actual)
            }
            after = batch.last().ownerKey
        }
    }

    /** 拥有者键不依赖本机行号，代次和 winner 仍在每条义务中单独核对。 */
    fun ownerKey(space: String, type: String, id: String): String = SyncOperationCanonicalizer.sha256Hex("$space\n$type\n$id")

    companion object {
        /** AI 正文允许 metadata 先落盘，但这些引用不能用空文本满足完成条件。 */
        private val BODY_KINDS = setOf("context_snapshot", "context_prompt_snapshot", "evidence_text", "citation_quote")
        /** 控制验收查询的轻量义务批次，不汇总全部 AI 正文。 */
        private const val OWNER_BATCH_ROWS = 256
    }
}
