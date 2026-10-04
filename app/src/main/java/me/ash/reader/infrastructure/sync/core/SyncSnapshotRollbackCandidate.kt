package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.*
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 真实回滚旧值单独保留为 Genesis 候选；不同本机 predecessor 不改写原 Operation token。 */
class SyncSnapshotRollbackCandidate @Inject constructor(database: AndroidDatabase) {
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val liveDatabase = database
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    data class Input(val space: String, val lane: String, val value: JsonObject)

    /** 读取已有基线；基线身份由字段与真实旧值确定，不生成替代 Operation 或作者签名。 */
    suspend fun capture(input: Input): JsonObject? {
        val value = input.value
        val row = database.syncInboxDao().findRollbackBaseline(input.space, value.getValue("entityType").jsonPrimitive.content,
            value.getValue("entitySyncId").jsonPrimitive.content, value.getValue("entityGeneration").jsonPrimitive.long,
            value.getValue("fieldId").jsonPrimitive.content) ?: return null
        val parsed = Json.parseToJsonElement(row.valueJson)
        val oldValue = if ((parsed as? JsonObject)?.get("__syncRollbackBaselineV2")?.jsonPrimitive?.booleanOrNull == true)
            parsed.getValue("valueJson").jsonPrimitive.content else SyncOperationCanonicalizer.canonicalJson(row.valueJson)
        val identity = JsonArray(listOf(JsonPrimitive(input.space), value.getValue("entityType"), value.getValue("entitySyncId"),
            value.getValue("entityGeneration"), value.getValue("fieldId"), JsonPrimitive(oldValue)))
        val baseline = "rollback-" + SyncOperationCanonicalizer.sha256Hex(SyncOperationCanonicalizer.canonicalJson(identity.toString()))
        return JsonObject(value.filterKeys { it in listOf("entityType", "entitySyncId", "entityGeneration", "fieldId") } + mapOf(
            "valueJson" to JsonPrimitive(oldValue), "versionToken" to JsonPrimitive(SyncVersionToken.genesis(baseline, input.lane,
                value.getValue("entitySyncId").jsonPrimitive.content, value.getValue("fieldId").jsonPrimitive.content)),
            "causalContextJson" to JsonNull, "logicalClock" to JsonNull))
    }

    companion object {
        /** 同 token 业务内容必须相等，已 GC 的一端可以从另一端补齐原始签名证据。 */
        fun merge(left: JsonObject, right: JsonObject): JsonObject {
            val fields = left.filterKeys { it != "sourceOperation" }
            check(SyncOperationCanonicalizer.canonicalJson(JsonObject(fields).toString()) ==
                SyncOperationCanonicalizer.canonicalJson(JsonObject(right.filterKeys { it != "sourceOperation" }).toString())) {
                "SNAPSHOT_CORRUPTED: conflicting field commitment"
            }
            val a = normalizedSource(left)
            val b = normalizedSource(right)
            check(a == null || b == null || SyncOperationCanonicalizer.canonicalJson(a.toString()) ==
                SyncOperationCanonicalizer.canonicalJson(b.toString())) { "SNAPSHOT_CORRUPTED: conflicting original operation" }
            val source = a ?: b
            return JsonObject(fields + (source?.let { mapOf("sourceOperation" to it) } ?: emptyMap()))
        }

        /** 公钥提示不属于签名承诺；正式 codec 验证并保留完整签名操作字段。 */
        private fun normalizedSource(value: JsonObject): JsonElement? {
            val source = value["sourceOperation"]?.takeUnless { it == JsonNull } ?: return null
            val operation = SyncOperationWireCodec.decode(source.toString())
            SyncOperationWireCodec.validate(operation)
            return Json.parseToJsonElement(SyncOperationWireCodec.encode(operation))
        }
    }
}
