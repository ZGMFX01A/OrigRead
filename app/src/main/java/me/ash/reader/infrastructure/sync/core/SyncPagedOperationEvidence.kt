package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 快照保留原作者签名、effect 来源及 predecessor，不把转发签名当成业务授权。 */
class SyncPagedOperationEvidence @Inject constructor(
    database: AndroidDatabase,
    private val remote: SyncRemoteApplyCoordinator,
    private val store: SyncPagedSnapshotStore,
) {
    private val liveDatabase = database
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    data class Field(val space: String, val record: SyncSnapshotRecord, val now: Long)
    private val json = Json

    /** 只附带原作者的真实签名及已有 baseline，不生成替代签名或虚构旧值。 */
    suspend fun capture(space: String, value: JsonObject): JsonObject {
        val dot = SyncVersionToken.parseOperationDot(value.getValue("versionToken").jsonPrimitive.content) ?: return JsonObject(emptyMap())
        val operation = database.syncOperationDao().findByDot(dot.actorIncarnationId, dot.replicationLaneId, dot.sequence)
            ?: return JsonObject(emptyMap())
        check(operation.syncSpaceId == space) { "SNAPSHOT_CORRUPTED: original operation belongs to another space" }
        check(operation.buildStatus == "SIGNED" && operation.authorSignature != null) { "REBASE_UNSAFE: Snapshot original operation is not signed" }
        return JsonObject(mapOf("sourceOperation" to json.parseToJsonElement(SyncOperationWireCodec.encode(SyncOperationWireCodec.toWire(operation)))))
    }

    /** 完整页面通过后、业务安装前逐字段重新核对当前 cutoff 和原始载荷。 */
    suspend fun verify(manifest: SyncPagedSnapshotManifest) = verifyFields(manifest) { remote.verifySnapshotOperation(it) }

    /** AUTH 稳定屏障内仅复用紧邻操作的冻结授权，当前 actor 归属及 Dot 冲突仍逐字段检查。 */
    internal suspend fun verifyWithinAuthorityBarrier(manifest: SyncPagedSnapshotManifest) {
        var previous: SyncRemoteApplyCoordinator.SnapshotOperationProof? = null
        verifyFields(manifest) { operation ->
            previous = remote.verifySnapshotOperationWithinAuthorityBarrier(operation, previous)
        }
    }

    private data class Verification(val manifest: SyncPagedSnapshotManifest, val stable: SyncCoverage,
        val authorize: suspend (SyncOperationEnvelope) -> Unit, val payload: (SyncOperationEnvelope) -> JsonObject)

    /** 普通接收逐字段读取当前授权；安装屏障内只减少完全相同签名操作的重复授权查询。 */
    private suspend fun verifyFields(manifest: SyncPagedSnapshotManifest, authorize: suspend (SyncOperationEnvelope) -> Unit) {
        var previous: SyncOperationEnvelope? = null
        var payloadSource: SyncOperationEnvelope? = null
        var payload: JsonObject? = null
        val verification = Verification(manifest, stableCoverage(manifest.syncSpaceId), authorize = { operation ->
            if (previous != operation) {
                authorize(operation); previous = operation
                SyncSnapshotTrace.add(SyncSnapshotTrace.Work(bytes = operation.payloadJson.toByteArray(Charsets.UTF_8).size.toLong(), uniqueSources = 1L))
            }
        }, payload = { operation ->
            if (payloadSource != operation) { payload = json.parseToJsonElement(operation.payloadJson).jsonObject; payloadSource = operation }
            checkNotNull(payload)
        })
        SyncSnapshotTrace.suspendPhase("verify.fields", manifest.snapshotBundleId) {
            for (lane in manifest.lanes) {
                val filter = SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, lane.replicationLaneId, "FIELD_VERSION")
                for (field in store.evidenceFields(filter)) {
                    verifyField(verification, field)
                    SyncSnapshotTrace.add(SyncSnapshotTrace.Work(rows = 1L))
                }
            }
        }
    }

    /** 原始 Dot 必须属于签名 coverage；已稳定且日志被 GC 的候选仍可由 OWNER 证明。 */
    private suspend fun verifyField(verification: Verification, evidence: SyncSnapshotFieldEvidenceReader.Field) {
        val (manifest, stable, authorize) = verification
        val field = evidence.metadata
        val dot = SyncVersionToken.parseOperationDot(field.token) ?: return
        check(database.syncIntegrityDao().isolation(manifest.syncSpaceId, dot.actorIncarnationId) == null) { "DOT_COLLISION: Snapshot carries isolated actor history" }
        check(dot.sequence <= (manifest.coverage[dot.replicationLaneId]?.get(dot.actorIncarnationId) ?: 0L)) {
            "SNAPSHOT_CORRUPTED: field operation exceeds signed coverage"
        }
        val stableDot = dot.sequence <= (stable[dot.replicationLaneId]?.get(dot.actorIncarnationId) ?: 0L)
        val operation = evidence.source
        if (operation == null) {
            check(stableDot) { "REBASE_UNSAFE: provisional Snapshot field lacks original signed operation" }
            return
        }
        authorize(operation)
        requireMatchingField(verification, field, operation)
        if (!stableDot) requirePredecessor(manifest, field, stable)
    }


    /** Reader baseline 的同一事务恢复真实 Inbox，未来撤销可通过来源找到这些 effect。 */
    suspend fun restore(input: Field): String? {
        val operation = source(input.record) ?: return null
        remote.restoreSnapshotOperation(operation, input.now)
        return operation.operationId
    }

    /** 按完整来源分组恢复，每个来源只 ingest 一次；字段因果和载荷仍在验证阶段分别核对。 */
    suspend fun stageSources(manifest: SyncPagedSnapshotManifest, now: Long) {
        for (lane in manifest.lanes) {
            var previous: SyncOperationEnvelope? = null
            val filter = SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, lane.replicationLaneId, "FIELD_VERSION")
            for (field in store.evidenceFields(filter)) {
                val operation = field.source ?: continue
                if (previous == operation) continue
                SyncSnapshotCancellation.checkpoint()
                val phase = "source-ingest:" + operation.operationId
                val receipt = database.snapshotInstallProgressDao().find(manifest.snapshotBundleId, phase)
                check(receipt == null || receipt.rootHash == manifest.rootHash) { "SNAPSHOT_CONFLICT: source receipt belongs to another root" }
                if (receipt == null) {
                    remote.stageSnapshotOperation(operation, now)
                    database.withTransaction {
                        database.snapshotInstallProgressDao().save(SyncSnapshotInstallProgress(manifest.snapshotBundleId, phase, manifest.rootHash, "IMPORTED"))
                    }
                }
                previous = operation
            }
        }
    }

    /** 原始操作对象完整解码，非法对象不退化为无来源候选。 */
    private fun source(record: SyncSnapshotRecord): SyncOperationEnvelope? =
        record.value["sourceOperation"]?.takeUnless { it == JsonNull }?.let { SyncOperationWireCodec.decode(it.toString()) }

    /** Dot、实体、代次、时钟和字段值都必须保持原作者签名中的真实含义。 */
    private fun requireMatchingField(verification: Verification, field: SyncSnapshotFieldMetadata, operation: SyncOperationEnvelope) {
        val token = SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence)
        check(verification.manifest.syncSpaceId == operation.syncSpaceId && field.token == token &&
            field.identity.type == operation.entityType && field.identity.id == operation.entitySyncId &&
            field.identity.generation == operation.entityGeneration) {
            "SNAPSHOT_CORRUPTED: field identity differs from original signed operation"
        }
        check(field.clock == operation.logicalClock && field.causalContext == operation.causalContextJson) {
            "SNAPSHOT_CORRUPTED: field causal metadata differs from original signed operation"
        }
        // 旧轻索引把缺省时钟记为零；真实零时钟仍须精确确认，缺省/null 不能代替签名值。
        if (field.clock == 0L) {
            val filter = SyncPagedSnapshotStore.RecordFilter(field.bundle, field.lane, "FIELD_VERSION")
            val record = store.fieldRecord(filter, field.key, includeSource = false)
            check(record.value["logicalClock"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long == operation.logicalClock) {
                "SNAPSHOT_CORRUPTED: field causal metadata differs from original signed operation"
            }
        }
        requireMatchingValue(field, operation, verification.payload(operation))
    }

    /** 只匹配当前字段，不能用同实体的其他已签名字段给伪造值背书。 */
    private fun requireMatchingValue(metadata: SyncSnapshotFieldMetadata, operation: SyncOperationEnvelope, payload: JsonObject) {
        val field = metadata.fieldId
        val actual = if (operation.operationType == "FIELD_SET") {
            check(payload.getValue("field").jsonPrimitive.content == field) { "SNAPSHOT_CORRUPTED: signed field differs" }
            payload.getValue("value")
        } else ((payload["fields"] as? JsonObject) ?: payload).getValue(field)
        val expected = SyncOperationCanonicalizer.canonicalValue(actual)
        if (SyncOperationCanonicalizer.sha256Hex(expected) == metadata.valueDigest) return
        // 同义 JSON 允许不同属性顺序/空白；字节承诺不同才精确读取此字段，仍须完整语义相等。
        val filter = SyncPagedSnapshotStore.RecordFilter(metadata.bundle, metadata.lane, "FIELD_VERSION")
        val record = store.fieldRecord(filter, metadata.key, includeSource = false)
        check(SyncOperationCanonicalizer.canonicalJson(record.value.getValue("valueJson").jsonPrimitive.content) == expected) {
            "SNAPSHOT_CORRUPTED: field value differs from original signed operation"
        }
    }

    /** verified OWNER checkpoint 允许保留已经不可撤销、原日志可被 GC 的候选。 */
    private suspend fun stableCoverage(space: String): SyncCoverage {
        var result: SyncCoverage = emptyMap()
        for (row in database.syncAuthLedgerDao().list(space)) {
            val objectValue = SyncAuthWireCodec.decode(row.authObjectJson)
            if (objectValue.objectType != SyncAuthObjectType.AUTH_STABILITY_CHECKPOINT) continue
            val coverage = json.parseToJsonElement(objectValue.payloadJson).jsonObject.getValue("acceptedPrefixByActorLane").jsonObject
            result = mergeStable(result, coverage)
        }
        return result
    }

    /** 合并轻量前缀；页面编号或 snapshot 作者不能代替 OWNER 的稳定授权。 */
    private fun mergeStable(result: SyncCoverage, coverage: JsonObject): SyncCoverage =
        (result.keys + coverage.keys).associateWith { lane ->
            val existing = result[lane].orEmpty()
            val incoming = coverage[lane]?.jsonObject.orEmpty()
            (existing.keys + incoming.keys).associateWith { actor ->
                maxOf(existing[actor] ?: 0L, incoming[actor]?.jsonPrimitive?.long ?: 0L)
            }
        }

    /** 保留真实 baseline 或稳定 predecessor，不能将当前 provisional winner 当成被撤销前值。 */
    private fun requirePredecessor(manifest: SyncPagedSnapshotManifest, field: SyncSnapshotFieldMetadata, stable: SyncCoverage) {
        val lane = checkNotNull(SyncVersionToken.parseOperationDot(field.token)).replicationLaneId
        val filter = SyncPagedSnapshotStore.RecordFilter(manifest.snapshotBundleId, lane, "FIELD_VERSION",
            field.identity.type, field.identity.id, field.identity.generation, field.fieldId)
        check(store.derived.fields(filter).any { candidate ->
            val token = candidate.token
            val dot = SyncVersionToken.parseOperationDot(token)
            token.startsWith("GENESIS_V1|") || (dot != null && dot.sequence <= (stable[dot.replicationLaneId]?.get(dot.actorIncarnationId) ?: 0L))
        }) { "REBASE_UNSAFE: provisional Snapshot field lacks predecessor evidence" }
    }

}
