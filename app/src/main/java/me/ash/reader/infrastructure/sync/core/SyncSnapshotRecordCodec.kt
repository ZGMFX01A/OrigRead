package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 规范记录键与 Desktop 的数组身份完全一致，页边界不能改变记录身份。 */
object SyncSnapshotRecordCodec {
    /** 网络输入只允许完整快照定义的记录类别。 */
    private val kinds = setOf("ENTITY", "FIELD_VERSION", "TOMBSTONE", "ALIAS_EDGE", "BLOB_MANIFEST", "BLOB_REFERENCE", "AUTH_OBJECT", "GENESIS")
    private val json = Json { ignoreUnknownKeys = false }

    /** 字段候选和 Blob 引用使用独立复合键，避免不同代次/候选被误覆盖。 */
    fun key(kind: String, value: JsonObject): String {
        val entity = listOf(value["entityType"], value["entitySyncId"], value["entityGeneration"] ?: value["generation"])
        val parts = when (kind) {
            "ENTITY", "TOMBSTONE" -> entity
            "FIELD_VERSION" -> entity + listOf(value["fieldId"], value["versionToken"])
            "BLOB_MANIFEST" -> listOf(value["hash"])
            "BLOB_REFERENCE" -> listOf(value["ownerEntityType"], value["ownerEntitySyncId"], value["ownerEntityGeneration"], value["referenceKind"], value["hash"])
            "AUTH_OBJECT" -> listOf(value["authObjectId"])
            "GENESIS" -> listOf(value["genesisBaselineId"])
            "ALIAS_EDGE" -> aliasParts(value)
            else -> error("SNAPSHOT_CORRUPTED: unsupported Snapshot record kind")
        }
        return canonicalArray(parts)
    }

    /** 完整记录解码时核对身份；跨页实体与 Blob 关联由暂存索引统一验证。 */
    fun decode(line: String): SyncSnapshotRecord {
        val record = json.decodeFromString<SyncSnapshotRecord>(line)
        SyncSnapshotRecordValidation.validate(record)
        require(record.kind in kinds && record.key == key(record.kind, record.value)) {
            "SNAPSHOT_CORRUPTED: Snapshot record identity mismatch"
        }
        return record
    }

    /** Alias Edge 为无向关系，双方端点按相同字符串排序形成稳定唯一键。 */
    private fun aliasParts(value: JsonObject): List<JsonElement?> {
        val endpoints = listOf(
            canonicalArray(listOf(value["leftSyncId"], value["leftGeneration"])),
            canonicalArray(listOf(value["rightSyncId"], value["rightGeneration"])),
        ).sorted().map { JsonPrimitive(it) }
        return listOf(value["targetEntityType"]) + endpoints
    }

    /** 数组使用显式 null 和既有规范 JSON，防止两端缺失字段产生不同键。 */
    private fun canonicalArray(parts: List<JsonElement?>): String =
        SyncOperationCanonicalizer.canonicalJson(JsonArray(parts.map { it ?: JsonNull }).toString())
}
