package me.ash.reader.infrastructure.sync.core

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** 私有字段按轻 token 分组关联来源，完整操作只在当前分组中存在一份。 */
internal object SyncSnapshotCapturedSources {
    data class Dependencies(val database: SQLiteDatabase, val sources: SyncSnapshotSourcePool,
        val derived: SyncSnapshotDerivedIndex, val budget: SyncSnapshotResourceBudget)
    data class Input(val bundle: String, val space: String, val original: (String) -> JsonElement?)
    private data class Key(val id: Long, val lane: String, val token: String, val key: String, val sourceKey: String?, val bytes: Long)
    private data class Prepared(val id: Long, val hash: String, val stored: String?,
        val source: SyncSnapshotSourcePool.Prepared, val facts: SyncSnapshotDerivedFacts.Prepared?, val cursor: String, val bytes: Long)
    /** 逻辑断点绑定来源分组算法和固定 bundle，不复用原始 rowid 作为恢复身份。 */
    private const val PHASE = "capture-sources:v1"
    /** 索引批次只预取轻键，符合文档 P3 的初始工作单位。 */
    private const val BATCH_ROWS = 256
    /** 来源和记录的实际 UTF-8 准备字节预算，合法大单条独占批次。 */
    private const val BATCH_BYTES = 2L * 1024L * 1024L

    /** 原始固定来源连接关闭前关联完整签名，并在同一个页库事务写入断点。 */
    fun associate(deps: Dependencies, input: Input) {
        val progress = SyncSnapshotBatchProgress(deps.database)
        var after = progress.cursor(input.bundle, PHASE)?.let(::decodeCursor) ?: listOf("", "", "")
        val grouping = Grouping(input)
        var persistedSource: String? = null
        while (true) {
            SyncSnapshotCancellation.checkpoint()
            val keys = keys(deps.database, input.bundle, after)
            if (keys.isEmpty()) return
            val batch = prepareBatch(keys, { key -> key.bytes + (grouping.material(key, key.sourceKey?.let {
                { deps.sources.readCanonical(it) }
            })?.encoded?.toByteArray(Charsets.UTF_8)?.size ?: 0) }) { key -> prepareField(deps, input, grouping to key) }
            val bytes = batch.sumOf { it.bytes }
            deps.budget.requireRemaining(input.bundle, bytes)
            var committedSource = persistedSource
            progress.commit(SyncSnapshotBatchProgress.Batch(input.bundle, PHASE, batch.last().cursor, batch.size.toLong(), bytes)) {
                for (row in batch) {
                    SyncSnapshotCancellation.checkpoint()
                    if (row.source.key != null && row.source.key != committedSource) deps.sources.persist(row.source)
                    else deps.sources.link(row.source)
                    committedSource = row.source.key
                    if (row.stored == null) deps.database.execSQL("UPDATE sync_paged_snapshot_record SET content_hash=? WHERE rowid=?",
                        arrayOf(row.hash, row.id))
                    else {
                        deps.database.execSQL("UPDATE sync_paged_snapshot_record SET record_json=?,content_hash=? WHERE rowid=?",
                            arrayOf(row.stored, row.hash, row.id))
                        deps.derived.write(checkNotNull(row.facts))
                    }
                }
            }
            persistedSource = committedSource
            after = decodeCursor(batch.last().cursor)
        }
    }

    /** 索引游标在准备/写事务开始前关闭，只保留有界的轻业务键。 */
    private fun keys(database: SQLiteDatabase, bundle: String, after: List<String>): List<Key> = database.rawQuery("""
        SELECT r.rowid,f.replication_lane_id,f.version_token,f.record_key,l.source_key,length(CAST(r.record_json AS BLOB))
        FROM sync_snapshot_field_index f JOIN sync_paged_snapshot_record r ON r.snapshot_bundle_id=f.snapshot_bundle_id
          AND r.replication_lane_id=f.replication_lane_id AND r.record_key=f.record_key AND r.kind='FIELD_VERSION'
        LEFT JOIN sync_snapshot_source_link l ON l.snapshot_bundle_id=f.snapshot_bundle_id
          AND l.replication_lane_id=f.replication_lane_id AND l.record_key=f.record_key
        WHERE f.snapshot_bundle_id=? AND (f.replication_lane_id,f.version_token,f.record_key)>(?,?,?)
        ORDER BY f.replication_lane_id,f.version_token,f.record_key LIMIT $BATCH_ROWS""", (listOf(bundle) + after).toTypedArray()).use {
        buildList { while (it.moveToNext()) add(Key(it.getLong(0), it.getString(1), it.getString(2), it.getString(3),
            if (it.isNull(4)) null else it.getString(4), it.getLong(5))) }
    }

    /** 准备队列达到实际字节预算后立即提交，不保留后续正文 DTO。 */
    private fun prepareBatch(keys: List<Key>, size: (Key) -> Long, prepare: (Key) -> Prepared): List<Prepared> {
        val result = mutableListOf<Prepared>()
        var bytes = 0L
        for (key in keys) {
            SyncSnapshotCancellation.checkpoint()
            val planned = size(key)
            if (result.isNotEmpty() && bytes + planned > BATCH_BYTES) break
            val row = prepare(key)
            result.add(row); bytes += row.bytes
            if (bytes >= BATCH_BYTES) break
        }
        return result
    }

    /** 每字段只解码紧凑事实；旧池/内嵌证据冲突直接失败，不能覆盖已知签名。 */
    private fun prepareField(deps: Dependencies, input: Input, position: Pair<Grouping, Key>): Prepared {
        val (grouping, key) = position
        val raw = SyncPagedSnapshotText.read(deps.database, SyncPagedSnapshotText.Source.RECORD, key.id)
        val record = SyncSnapshotRecordCodec.decode(raw)
        SyncSnapshotRecordLane.validate(key.lane, record)
        check(record.key == key.key && record.value.getValue("versionToken").jsonPrimitive.content == key.token) {
            "SNAPSHOT_CORRUPTED: field index differs from captured fact"
        }
        val entry = SyncSnapshotSourcePool.Entry(input.bundle, key.lane, record)
        val inline = deps.sources.prepare(entry)
        check(key.sourceKey == null || inline.key == null || key.sourceKey == inline.key) { "SNAPSHOT_CORRUPTED: retained field sources differ" }
        val retained = key.sourceKey ?: inline.key
        val material = grouping.material(key, retained?.let { { if (inline.key == it && inline.encoded != null)
            SyncSnapshotSourcePool.Canonical(it, inline.encoded) else deps.sources.readCanonical(it) } })
        check(retained == null || material?.key == retained) { "DOT_COLLISION: fixed source differs from retained field source" }
        val source = deps.sources.prepareCanonical(entry, material)
        val encoding = SyncSnapshotRecordFragments.Input(source.compact, source.encoded)
        // 原捕获批次已提交轻事实，只有旧内嵌来源转换需要改正文并重建派生列。
        val inlineSource = record.value["sourceOperation"]
        val encoded = if (inlineSource == null || inlineSource == JsonNull) null else SyncSnapshotRecordFragments.prepare(encoding)
        val facts = encoded?.let { SyncSnapshotDerivedFacts.prepare(SyncSnapshotDerivedFacts.Input(input.bundle, key.lane, source.compact)) }
        return Prepared(id = key.id, hash = encoded?.hash ?: SyncSnapshotRecordFragments.hash(encoding), stored = encoded?.stored,
            source = source, facts = facts,
            cursor = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), listOf(key.lane, key.token, key.key)),
            bytes = (encoded?.stored ?: raw).toByteArray(Charsets.UTF_8).size.toLong() + (source.encoded?.toByteArray(Charsets.UTF_8)?.size ?: 0))
    }

    /** 一个 token 仅保留一个规范来源材料，跨批次也不会反向重复读取原操作。 */
    private class Grouping(private val input: Input) {
        private var identity: Pair<String, String>? = null
        private var source: SyncSnapshotSourcePool.Canonical? = null

        /** 旧来源重入仍与当前字段 Dot/空间绑定，缺失来源由既有 coverage 校验显式裁决。 */
        fun material(key: Key, retained: (() -> SyncSnapshotSourcePool.Canonical)?): SyncSnapshotSourcePool.Canonical? {
            val identity = key.lane to key.token
            if (this.identity != identity) {
                this.identity = identity
                source = input.original(key.token)?.let { original ->
                    val encoded = SyncOperationCanonicalizer.canonicalValue(original)
                    SyncSnapshotTrace.add(SyncSnapshotTrace.Work(bytes = encoded.toByteArray(Charsets.UTF_8).size.toLong(), uniqueSources = 1))
                    SyncSnapshotSourcePool.Canonical(SyncOperationCanonicalizer.sha256Hex(encoded), encoded)
                }
            }
            if (source == null && retained != null) {
                val saved = retained()
                requireBinding(key, Json.parseToJsonElement(saved.encoded) as JsonObject)
                source = saved
            }
            return source
        }

        /** 缓存只减少不可变来源展开，不能绕过字段 token 和原作者所属空间。 */
        private fun requireBinding(key: Key, source: JsonObject) {
            val dot = checkNotNull(SyncVersionToken.parseOperationDot(key.token)) { "SNAPSHOT_CORRUPTED: retained source has no field Dot" }
            check(source.getValue("syncSpaceId").jsonPrimitive.content == input.space &&
                source.getValue("actorIncarnationId").jsonPrimitive.content == dot.actorIncarnationId &&
                source.getValue("replicationLaneId").jsonPrimitive.content == dot.replicationLaneId &&
                source.getValue("sequence").jsonPrimitive.long == dot.sequence &&
                source.getValue("authorSignature").jsonPrimitive.content.isNotBlank()) { "SNAPSHOT_CORRUPTED: retained source does not match field Dot" }
        }
    }

    /** 恢复游标只使用 lane/token/业务键，SQLite 重建后仍指向同一逻辑位置。 */
    private fun decodeCursor(raw: String): List<String> =
        Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), raw)
}
