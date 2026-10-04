package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 网络记录必须携带明确业务身份，禁止安装阶段推断缺失字段或代次。 */
object SyncSnapshotRecordValidation {
    /** 共享 wire 序号必须同时能由 Android Long 和 Desktop Number 精确表示。 */
    private const val MAX_WIRE_INTEGER = 9_007_199_254_740_991L
    private val json = Json

    /** 检查每类记录的标识、数值类型和 JSON 载荷，失败直接返回协议错误。 */
    fun validate(record: SyncSnapshotRecord) {
        val value = record.value
        when (record.kind) {
            "ENTITY" -> { entity(value); require(value["fields"] is JsonObject) { invalid() } }
            "FIELD_VERSION" -> {
                entity(value, "entityGeneration"); text(value["fieldId"]); text(value["versionToken"])
                jsonValue(value["valueJson"])
                value["causalContextJson"]?.takeUnless { it == JsonNull }?.let(::jsonValue)
                value["logicalClock"]?.takeUnless { it == JsonNull }?.let(::integer)
            }
            "TOMBSTONE" -> { entity(value); text(value["versionToken"]); integer(value["deletedAt"]) }
            "ALIAS_EDGE" -> {
                text(value["targetEntityType"]); text(value["leftSyncId"]); text(value["rightSyncId"])
                integer(value["leftGeneration"]); integer(value["rightGeneration"])
            }
            "BLOB_REFERENCE" -> {
                text(value["replicationLaneId"]); text(value["ownerEntityType"]); text(value["ownerEntitySyncId"])
                integer(value["ownerEntityGeneration"]); text(value["referenceKind"]); digest(value["hash"])
            }
            "BLOB_MANIFEST" -> {
                digest(value["hash"]); integer(value["totalBytes"]); integer(value["referenceCount"])
                require(text(value["durability"]) in setOf("SYNC_DURABLE", "CACHE", "REHYDRATABLE")) { invalid() }
            }
            "AUTH_OBJECT" -> {
                text(value["authObjectId"]); text(value["syncSpaceId"]); text(value["objectType"]); integer(value["authEpoch"])
                value["authSequence"]?.takeUnless { it == JsonNull }?.let(::integer)
                jsonValue(value["payloadJson"]); text(value["authorDeviceId"]); text(value["authorSignature"])
            }
            "GENESIS" -> text(value["genesisBaselineId"])
            else -> error(invalid())
        }
    }

    /** 身份与代次必须能形成唯一、类型严格的数据库索引。 */
    private fun entity(value: JsonObject, generation: String = "generation") {
        text(value["entityType"]); text(value["entitySyncId"]); integer(value[generation])
    }

    /** 标识必须是非空 JSON 字符串，布尔值或数字不得转换成设备/实体 ID。 */
    private fun text(value: JsonElement?): String {
        require(value is JsonPrimitive && value.isString && value.content.isNotBlank()) { invalid() }
        return value.content
    }

    /** 字符串数字不能通过跨语言数值类型校验。 */
    private fun integer(value: JsonElement?) {
        require(value is JsonPrimitive && !value.isString && value.longOrNull?.let { it in 0..MAX_WIRE_INTEGER } == true) { invalid() }
    }

    /** 只接受规范 SHA-256 摘要，避免不同文本编码产生不同记录身份。 */
    private fun digest(value: JsonElement?) { require(text(value).matches(Regex("[a-f0-9]{64}"))) { invalid() } }

    /** JSON 内嵌字符串也必须可解码，不延迟到安装事务中才发现格式损坏。 */
    private fun jsonValue(value: JsonElement?) { json.parseToJsonElement(text(value)) }

    /** 统一错误码以便同步历史呈现真实输入失败。 */
    private fun invalid(): String = "SNAPSHOT_CORRUPTED: paged Snapshot record has invalid business fields"
}
