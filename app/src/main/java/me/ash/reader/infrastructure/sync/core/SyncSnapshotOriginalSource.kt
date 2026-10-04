package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 固定操作通过轻 Dot 定位后分块读列，巨大的 payload 不作为 CursorWindow 单元格返回。 */
internal class SyncSnapshotOriginalSource @Inject constructor(
    private val liveDatabase: AndroidDatabase,
    private val rows: SyncSnapshotSqlRows,
) {
    /** 与正式 wire 编码保持相同默认值和 null 输出，不能改写原来源承诺。 */
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    /** 捕获转换只消费相同 cut 副本，原作者/空间/签名状态仍逐来源核对。 */
    fun read(space: String, token: String): JsonElement? {
        val dot = SyncVersionToken.parseOperationDot(token) ?: return null
        val database = SyncFrozenSourceContext.database(liveDatabase).openHelper.writableDatabase
        val id = database.query("SELECT rowid FROM sync_operation_log WHERE actorIncarnationId=? AND replicationLaneId=? AND sequence=? LIMIT 1",
            arrayOf(dot.actorIncarnationId, dot.replicationLaneId, dot.sequence)).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        val row = rows.readRow(SyncSnapshotSqlRows.Row(database, "sync_operation_log", id))
        check(row.getValue("syncSpaceId").jsonPrimitive.content == space) { "SNAPSHOT_CORRUPTED: original operation belongs to another space" }
        check(row.getValue("buildStatus").jsonPrimitive.content == "SIGNED" &&
            row["authorSignature"]?.jsonPrimitive?.content?.isNotBlank() == true && row["authorSignature"] != kotlinx.serialization.json.JsonNull) {
            "REBASE_UNSAFE: Snapshot original operation is not signed"
        }
        return project(row)
    }

    /** 正式序列化描述符只选择 wire 字段，排除本机时间、构建状态和公钥提示。 */
    @OptIn(ExperimentalSerializationApi::class)
    private fun project(row: JsonObject): JsonObject {
        val descriptor = SyncOperationEnvelope.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
        val source = JsonObject(row.filterKeys { it in names && it != "authorPublicKeySpkiBase64" } +
            ("protocolVersion" to JsonPrimitive(SYNC_PROTOCOL_VERSION)))
        // 同一正式 DTO 处理缺省/null，不调用额外规范编码或更改历史签名。
        val envelope = json.decodeFromJsonElement(SyncOperationEnvelope.serializer(), source)
        return json.encodeToJsonElement(SyncOperationEnvelope.serializer(), envelope) as JsonObject
    }
}
