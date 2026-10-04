package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 图标允许显式清空，缺少字段与 JSON null 必须保留不同的同步语义。 */
class SyncNullableFeedIcon @Inject constructor(
    private val fields: SyncFieldStateRows,
    private val retained: SyncRetainedFieldMerge,
) {
    data class Input(val operation: SyncOperationEntity, val incoming: JsonElement, val existing: String?,
        val captureBaseline: suspend (SyncFieldVersionEntity?) -> Unit)

    /** 用原始 JSON 值裁决，避免把空图标编码成字符串 "null" 并污染签名候选。 */
    suspend fun resolve(input: Input): String? {
        val incoming = input.incoming
        require(incoming == JsonNull || (incoming is JsonPrimitive && incoming.isString)) {
            "Feed icon must be a string or null"
        }
        val operation = input.operation
        val current = fields.find(SyncFieldStateRows.Field(operation.syncSpaceId, operation.entityType,
            operation.entitySyncId, ICON_FIELD))
        input.captureBaseline(current)
        val winner = retained.resolve(SyncRetainedFieldMerge.Options(operation, ICON_FIELD,
            SyncOperationCanonicalizer.canonicalJson(incoming.toString()), current, SyncGenesisMergePolicy.DETERMINISTIC))
        return Json.parseToJsonElement(winner.valueJson).jsonPrimitive.contentOrNull
    }

    companion object {
        /** Feed 可空图标字段在增量和快照中使用同一寄存器身份。 */
        private const val ICON_FIELD = "icon"
    }
}
