package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** 原始数据库行转回真实操作，保留作者签名及全部因果信息，不生成快照伪操作。 */
internal object SyncSnapshotOperationRow {
    /** 分段读取后只构造当前操作，避免 Room 为大 payload 分配整单元格 CursorWindow。 */
    fun decode(row: JsonObject): SyncOperationEntity = SyncOperationEntity(
        operationId = text(row, "operationId"), syncSpaceId = text(row, "syncSpaceId"),
        authorDeviceId = text(row, "authorDeviceId"), actorIncarnationId = text(row, "actorIncarnationId"),
        replicationLaneId = text(row, "replicationLaneId"), sequence = number(row, "sequence"),
        logicalClock = number(row, "logicalClock"), causalContextJson = text(row, "causalContextJson"),
        dependencyDotsJson = text(row, "dependencyDotsJson"), entityType = text(row, "entityType"),
        entitySyncId = text(row, "entitySyncId"), entityGeneration = number(row, "entityGeneration"),
        operationType = text(row, "operationType"), payloadSchemaVersion = number(row, "payloadSchemaVersion").toInt(),
        payloadJson = text(row, "payloadJson"), schemaVersion = number(row, "schemaVersion").toInt(),
        authGrantId = optionalText(row, "authGrantId"),
        authEpoch = row["authEpoch"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long,
        createdWallClock = number(row, "createdWallClock"), payloadHash = text(row, "payloadHash"),
        signingDigest = text(row, "signingDigest"), authorSignature = optionalText(row, "authorSignature"),
        buildStatus = text(row, "buildStatus"), createdAt = number(row, "createdAt"), updatedAt = number(row, "updatedAt"),
    )

    private fun text(row: JsonObject, field: String): String = row.getValue(field).jsonPrimitive.content
    private fun number(row: JsonObject, field: String): Long = row.getValue(field).jsonPrimitive.long
    private fun optionalText(row: JsonObject, field: String): String? =
        row[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
}
