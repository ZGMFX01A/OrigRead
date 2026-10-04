package me.ash.reader.infrastructure.sync.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 快照专用持久化区，页面不占普通 Blob 预约，也不由普通 Blob TTL 删除。 */
@Singleton
class SyncPagedSnapshotStore @Inject constructor(@ApplicationContext context: Context) : SyncSnapshotPageSink {
    internal val database = SyncPagedSnapshotDatabase(context).writableDatabase
    private val sources = SyncSnapshotSourcePool(database)
    internal val derived = SyncSnapshotDerivedIndex(database)
    private val persistence = SyncSnapshotRecordPersistence(SyncSnapshotRecordPersistence.Dependencies(
        database = database, sources = sources, readRecord = ::readRecordJson, derived = derived))
    private val validation = SyncSnapshotValidationCache(database)
    internal val lifecycle = SyncSnapshotLifecycle(database)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    init { SyncPagedSnapshotIndexes.create(database) }
    data class State(val manifestJson: String, val state: String, val syncSpaceId: String)
    data class RecordFilter(val bundleId: String, val lane: String? = null, val kind: String? = null,
        val entityType: String? = null, val entitySyncId: String? = null,
        val generation: Long? = null, val fieldId: String? = null, val blobHash: String? = null)
    data class Capture(val bundleId: String, val syncSpaceId: String, val now: Long)

    /** 仅完整记录复制完成后冻结，保存原始 cut 供进程重启继续分页。 */
    fun freezeCapture(bundle: String, frontiers: String) {
        database.execSQL("UPDATE sync_paged_snapshot SET state='FROZEN',manifest_json=? WHERE snapshot_bundle_id=? AND state='CAPTURING'",
            arrayOf(frontiers, bundle))
    }

    /** 只缓存已验证的不可变页面/索引，调用方仍需独立核验作者与当前 AUTH。 */
    fun reusable(manifest: SyncPagedSnapshotManifest): Boolean {
        val verified = database.rawQuery("SELECT state FROM sync_paged_snapshot WHERE snapshot_bundle_id=?",
            arrayOf(manifest.snapshotBundleId)).use { it.moveToFirst() && it.getString(0) == "VERIFIED" }
        return verified && validation.reusable(manifest.snapshotBundleId, manifest.rootHash)
    }

    /** 同一固定 cut 重入保留已提交前缀，已发布的不可变快照不得被重写。 */
    fun beginCapture(input: Capture) {
        lifecycle.budget.reserve(input.bundleId, 0L)
        val existing = find(input.bundleId)
        require(existing == null || existing.state == "CAPTURING") { "SNAPSHOT_CONFLICT: published Snapshot cannot be replaced" }
        if (existing != null) {
            require(existing.syncSpaceId == input.syncSpaceId) { "SNAPSHOT_CONFLICT: capture retry names another space" }
            return
        }
        database.execSQL("INSERT OR REPLACE INTO sync_paged_snapshot VALUES(?,?,?,?,?)",
            arrayOf(input.bundleId, input.syncSpaceId, "", "CAPTURING", input.now))
        SyncSnapshotContentCleanup.content(database, input.bundleId)
    }

    /** 同一 ID 必须继续对应同一个已验签清单，恢复不能替换 root 或固定视图。 */
    fun beginReceive(manifest: SyncPagedSnapshotManifest, now: Long) {
        val encoded = canonicalManifest(manifest)
        find(manifest.snapshotBundleId)?.let {
            require(it.manifestJson == encoded) { "SNAPSHOT_CONFLICT: Snapshot ID names another manifest" }
            return
        }
        database.execSQL("INSERT INTO sync_paged_snapshot VALUES(?,?,?,?,?)",
            arrayOf(manifest.snapshotBundleId, manifest.syncSpaceId, encoded, "RECEIVING", now))
    }

    /** 页面必须符合固定清单摘要，重复传输不能改变已持久化页面。 */
    fun receivePage(manifest: SyncPagedSnapshotManifest, page: SyncSnapshotBytePage) {
        require(find(manifest.snapshotBundleId)?.manifestJson == canonicalManifest(manifest)) { "SNAPSHOT_CONFLICT: page has no staged manifest" }
        writePage(SyncSnapshotPageWrite(manifest.snapshotBundleId, page.replicationLaneId, page.pageIndex,
            SyncPagedSnapshotWire.verifyPage(manifest, page)))
    }

    /** 捕获和接收共用页面落盘边界，已有页只允许相同字节摘要。 */
    override fun writePage(input: SyncSnapshotPageWrite): String {
        lifecycle.budget.requireRemaining(input.snapshotBundleId, input.bytes.size.toLong())
        val hash = SyncPagedSnapshotWire.hashBytes(input.bytes)
        val args = arrayOf(input.snapshotBundleId, input.lane, input.index.toString())
        database.rawQuery("SELECT content_hash FROM sync_paged_snapshot_page WHERE snapshot_bundle_id=? AND replication_lane_id=? AND page_index=?", args).use {
            require(!it.moveToFirst() || it.getString(0) == hash) { "SNAPSHOT_CONFLICT: immutable page content changed" }
        }
        database.execSQL("INSERT OR IGNORE INTO sync_paged_snapshot_page VALUES(?,?,?,?,?)",
            arrayOf(input.snapshotBundleId, input.lane, input.index, hash, input.bytes))
        return hash
    }

    /** 一次只读一个原始字节页，SQLite CursorWindow 不需要容纳完整 lane。 */
    fun readPage(bundleId: String, lane: String, index: Int): ByteArray =
        database.rawQuery("SELECT bytes FROM sync_paged_snapshot_page WHERE snapshot_bundle_id=? AND replication_lane_id=? AND page_index=?",
            arrayOf(bundleId, lane, index.toString())).use {
            check(it.moveToFirst()) { "SNAPSHOT_CORRUPTED: required Snapshot page is missing" }
            it.getBlob(0)
        }

    /** 从连续字节页恢复逐条记录，跨页 UTF-8 和超大单条记录不会触发 HTTP 整包上限。 */
    fun indexReceived(manifest: SyncPagedSnapshotManifest) {
        // 旧 VERIFIED 页升级派生索引也会扩张磁盘，不能依赖仅新接收路径创建的预约。
        lifecycle.budget.reserve(manifest.snapshotBundleId,
            manifest.lanes.sumOf { it.pageHashes.size.toLong() * SNAPSHOT_PAGE_BYTES })
        SyncSnapshotReceivedIndex(this).build(manifest)
        validateAssociations(manifest.snapshotBundleId)
    }

    /** 派生批次先完整落盘，再验证并发布；INDEXING 从不具备业务安装权限。 */
    fun verifyAndPublish(manifest: SyncPagedSnapshotManifest, now: Long) {
        if (reusable(manifest)) return
        if (find(manifest.snapshotBundleId)?.state != "VERIFIED" || !validation.indexed(manifest.snapshotBundleId, manifest.rootHash)) {
            indexReceived(manifest)
        }
        publish(manifest, now)
    }

    /** 私有合并保留同 token 的真实来源；冲突承诺仍拒绝。 */
    fun mergeFieldRecord(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord) =
        persistence.mergeFieldRecord(snapshotBundleId, lane, record)

    /** 冲突来源与因果证据在短写事务之前展开。 */
    internal fun prepareMergedField(bundle: String, lane: String, record: SyncSnapshotRecord) =
        persistence.prepareMergedField(bundle, lane, record)

    /** 完整记录摘要与来源链接一起写入派生表示。 */
    override fun writeRecord(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord): Boolean =
        persistence.writeRecord(snapshotBundleId, lane, record)

    /** 接收和合并先准备大 DTO，再由短批次提交原始 SQL 列。 */
    internal fun prepareRecord(bundle: String, lane: String, record: SyncSnapshotRecord): SyncSnapshotRecordPersistence.Prepared =
        persistence.prepare(bundle, lane, record)

    internal fun writePrepared(prepared: SyncSnapshotRecordPersistence.Prepared): Boolean = persistence.writePrepared(prepared)

    /** 固定来源只准备紧凑事实，完整承诺由随后来源分组一次生成。 */
    internal fun prepareCapture(input: SyncSnapshotRecordPersistence.Capture) = persistence.prepareCapture(input)
    /** 已落盘的私有重复键必须核对实际内容，不能仅比较空摘要。 */
    internal fun duplicateCapture(prepared: SyncSnapshotRecordPersistence.Prepared) = persistence.duplicate(prepared)
    /** 固定来源连接关闭前关联原签名，所有派生事实和恢复断点一起提交。 */
    internal fun associateCapturedSources(input: SyncSnapshotCapturedSources.Input) = SyncSnapshotCapturedSources.associate(
        SyncSnapshotCapturedSources.Dependencies(database = database, sources = sources, derived = derived, budget = lifecycle.budget), input)

    /** 捕获只保存私有原始记录，页面和记录摘要在释放 barrier 后生成。 */
    override fun captureRecord(snapshotBundleId: String, lane: String, record: SyncSnapshotRecord): Boolean =
        SyncFrozenSnapshotRecords.capture(SyncFrozenSnapshotRecords.Input(database = database, bundle = snapshotBundleId,
            lane = lane, record = record, readRecord = ::readRecordJson,
            sources = sources, derived = derived))

    /** 首次索引扫描及规范化均在 IO 线程执行；崩溃后从仍无摘要的冻结记录继续。 */
    internal suspend fun normalizeFrozenRecords(bundle: String) = withContext(Dispatchers.IO) {
        check(find(bundle)?.state == "FROZEN") { "SNAPSHOT_CONFLICT: normalization requires a private frozen cut" }
        SyncPagedSnapshotIndexes.createNormalization(database)
        SyncFrozenSnapshotRecords.normalize(SyncFrozenSnapshotRecords.Target(database = database, bundle = bundle,
            sources = sources, derived = derived))
    }

    /** 按固定主键逐条续读，等值 lane/类型不会导致反复扫描已经消费的记录。 */
    fun records(filter: RecordFilter): Sequence<SyncSnapshotRecord> =
        SyncPagedSnapshotRecords.read(SyncPagedSnapshotRecords.Read(database, filter, ::readRecordJson))

    /** 实际候选导入读取紧凑值，因果裁决和图校验使用独立轻索引。 */
    internal fun compactRecords(filter: RecordFilter): Sequence<SyncSnapshotRecord> =
        SyncPagedSnapshotRecords.read(SyncPagedSnapshotRecords.Read(database, filter) {
            SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.RECORD, it)
        })

    /** winner 通过精确逻辑键读取一次，不再扫描并展开同字段的全部来源。 */
    internal fun fieldRecord(filter: RecordFilter, key: String, includeSource: Boolean = true): SyncSnapshotRecord {
        val id = database.rawQuery("SELECT rowid FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=? AND kind='FIELD_VERSION' AND record_key=?",
            arrayOf(filter.bundleId, checkNotNull(filter.lane), key)).use {
            check(it.moveToFirst()) { "SNAPSHOT_CORRUPTED: winner record disappeared" }; it.getLong(0)
        }
        val raw = if (includeSource) readRecordJson(id) else SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.RECORD, id)
        return SyncSnapshotRecordCodec.decode(raw)
    }

    /** 来源、页或派生记录的任何写入都使绑定该修订的字段证明失效。 */
    internal fun derivedRevision(bundle: String): Long = validation.revision(bundle)

    /** 以来源为工作单位读取，每个字段仍保留其独立承诺验证。 */
    internal fun evidenceFields(filter: RecordFilter, sourceAfter: String? = null): Sequence<SyncSnapshotFieldEvidenceReader.Field> =
        SyncSnapshotFieldEvidenceReader(database, sources).read(filter, sourceAfter)

    /** 缺页可以续传；已存在的页损坏必须显式失败，不能当作缺页静默重传。 */
    fun hasVerifiedPage(manifest: SyncPagedSnapshotManifest, lane: String, index: Int): Boolean {
        val storedHash = database.rawQuery("SELECT content_hash FROM sync_paged_snapshot_page WHERE snapshot_bundle_id=? AND replication_lane_id=? AND page_index=?",
            arrayOf(manifest.snapshotBundleId, lane, index.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return false
        val expected = manifest.lanes.single { it.replicationLaneId == lane }.pageHashes[index]
        require(storedHash == expected && SyncPagedSnapshotWire.hashBytes(readPage(manifest.snapshotBundleId, lane, index)) == expected) {
            "SNAPSHOT_CORRUPTED: persisted Snapshot page differs from signed index"
        }
        return true
    }

    /** 续传响应只携带页号，逐页验证实际字节后才承认该页面已接收。 */
    fun pageStatus(manifest: SyncPagedSnapshotManifest): SyncSnapshotPageStatus = SyncSnapshotPageStatus(
        manifest.rootHash, manifest.lanes.associate { lane ->
            lane.replicationLaneId to if (reusable(manifest)) lane.pageHashes.indices.toList()
                else lane.pageHashes.indices.filter { hasVerifiedPage(manifest, lane.replicationLaneId, it) }
        },
    )

    /** 所有字节摘要与关联验证成功后发布，业务安装仍受独立 durable journal 控制。 */
    fun publish(manifest: SyncPagedSnapshotManifest, now: Long) {
        val bytes = database.rawQuery("SELECT COALESCE(SUM(length(bytes)),0) FROM sync_paged_snapshot_page WHERE snapshot_bundle_id=?", arrayOf(manifest.snapshotBundleId))
            .use { it.moveToFirst(); it.getLong(0) }
        lifecycle.budget.reserve(manifest.snapshotBundleId, bytes)
        val staged = requireNotNull(find(manifest.snapshotBundleId)) { "SNAPSHOT_CONFLICT: publication has no capture/stage" }
        require(staged.syncSpaceId == manifest.syncSpaceId &&
            (staged.state in setOf("CAPTURING", "FROZEN") || staged.manifestJson == canonicalManifest(manifest))) {
            "SNAPSHOT_CONFLICT: publication does not match immutable staged Snapshot"
        }
        manifest.lanes.forEach { lane ->
            database.rawQuery("SELECT COUNT(*) FROM sync_paged_snapshot_record WHERE snapshot_bundle_id=? AND replication_lane_id=?",
                arrayOf(manifest.snapshotBundleId, lane.replicationLaneId)).use {
                require(it.moveToFirst() && it.getLong(0) == lane.recordCount) { "SNAPSHOT_CORRUPTED: published Snapshot record count mismatch" }
            }
            lane.pageHashes.forEachIndexed { index, hash ->
                require(SyncPagedSnapshotWire.hashBytes(readPage(manifest.snapshotBundleId, lane.replicationLaneId, index)) == hash) {
                    "SNAPSHOT_CORRUPTED: Snapshot page changed before publication"
                }
            }
        }
        validateAssociations(manifest.snapshotBundleId)
        database.execSQL("UPDATE sync_paged_snapshot SET manifest_json=?,state=?,updated_at=? WHERE snapshot_bundle_id=?",
            arrayOf(canonicalManifest(manifest), "VERIFIED", now, manifest.snapshotBundleId))
        validation.mark(manifest.snapshotBundleId, manifest.rootHash)
        lifecycle.published(manifest.snapshotBundleId)
    }

    fun find(bundleId: String): State? =
        database.rawQuery("SELECT rowid,state,sync_space_id FROM sync_paged_snapshot WHERE snapshot_bundle_id=?", arrayOf(bundleId)).use {
            if (it.moveToFirst()) Triple(it.getLong(0), it.getString(1), it.getString(2)) else null
        }?.let { State(SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.MANIFEST, it.first), it.second, it.third) }

    /** 私有索引的首次创建时间固定输出承诺，恢复不随本轮 now 重新生成 root。 */
    internal fun captureTime(bundle: String): Long = database.rawQuery(
        "SELECT updated_at FROM sync_paged_snapshot WHERE snapshot_bundle_id=?", arrayOf(bundle)).use {
        check(it.moveToFirst()) { "SNAPSHOT_CAPTURE_IDENTITY_MISSING" }; it.getLong(0)
    }

    data class Scope(val sourceBundleId: String, val manifest: SyncPagedSnapshotManifest, val now: Long)

    /** 合并工作索引完成后释放私有内容，已发布快照禁止通过该入口删除。 */
    fun discardCapture(bundleId: String) {
        val current = find(bundleId) ?: return
        check(current.state == "CAPTURING") { "SNAPSHOT_CONFLICT: published Snapshot cannot be discarded" }
        SyncSnapshotContentCleanup.content(database, bundleId)
        database.delete("sync_paged_snapshot", "snapshot_bundle_id=?", arrayOf(bundleId))
        database.delete("sync_snapshot_batch_progress", "job_id=?", arrayOf(bundleId))
        lifecycle.budget.complete(bundleId)
    }

    /** P-256 合法重签的字节可能不同，同一固定视图复用持久签名而非重新生成。 */
    fun publishedManifest(rooted: SyncPagedSnapshotManifest): SyncPagedSnapshotManifest? {
        val saved = find(rooted.snapshotBundleId)?.takeIf { it.state == "VERIFIED" } ?: return null
        val published = json.decodeFromString<SyncPagedSnapshotManifest>(saved.manifestJson)
        check(published.copy(authorSignature = "") == rooted) { "SNAPSHOT_CONFLICT: Snapshot ID names another fixed view" }
        return published
    }

    /** 策略收窄按完整页和稳定记录键恢复，所有派生事实与原记录一起提交。 */
    fun copyScope(options: Scope) = SyncSnapshotScopeCopy(this).copy(options)

    /** 已提交工作索引原列复制到输出身份，页面编码在锁外完成。 */
    internal fun copyIndex(input: SyncSnapshotScopeCopy.Index) = SyncSnapshotScopeCopy(this).copyIndex(input)

    /** 只在实际 wire 输出时恢复完整语义，来源正文使用规范片段而不重复解码。 */
    internal fun canonicalRecordFragments(filter: RecordFilter): Sequence<Sequence<String>> = sequence {
        for (key in SyncPagedSnapshotRecordKeys.read(SyncPagedSnapshotRecordKeys.Read(database, filter))) {
            val raw = SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.RECORD, key.rowId)
            yield(sources.fragments(key.rowId, raw))
        }
    }

    /** 单次只读取少量 Unicode 码点，保证任何 UTF-8 字符不会被 SQL substr 截断。 */
    private fun readRecordJson(rowId: Long): String = sources.unpack(rowId,
        SyncPagedSnapshotText.read(database, SyncPagedSnapshotText.Source.RECORD, rowId))

    /** 独立字段和 Blob 引用必须在同快照中找到对应代次的实体和 manifest。 */
    private fun validateAssociations(bundleId: String) {
        derived.requireComplete(bundleId)
        SyncPagedEntityEvidence.requireComplete(this, bundleId)
        SyncPagedSnapshotRecords.requireAssociations(database, bundleId)
    }

    private fun canonicalManifest(manifest: SyncPagedSnapshotManifest): String =
        SyncOperationCanonicalizer.canonicalJson(json.encodeToString(manifest))

}
