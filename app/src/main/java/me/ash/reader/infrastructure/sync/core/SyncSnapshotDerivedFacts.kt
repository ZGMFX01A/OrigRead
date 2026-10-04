package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 值位置绑定原业务键，因果裁决阶段不携带字段实际值或来源正文。 */
internal data class SyncSnapshotFieldMetadata(val bundle: String, val lane: String, val key: String, val fieldId: String,
    val token: String, val clock: Long, val causalContext: String?, val valueDigest: String, val preferenceValue: String,
    val identity: SyncSnapshotFieldIdentity)

/** 字段证据只携带实体身份和代次，签名核对不读取该实体的大正文。 */
internal data class SyncSnapshotFieldIdentity(val type: String, val id: String, val generation: Long)

/** 图检查仅持有身份、代次、共享上下文与真实父关系。 */
internal data class SyncSnapshotEntityMetadata(val bundle: String, val lane: String, val key: String,
    val type: String, val id: String, val generation: Long, val context: JsonObject, val parents: List<SyncPagedEntityDependencies.Parent>)

/** 解码一条记录时提取轻量承诺，写批次只接收已经准备的 SQL 列。 */
internal object SyncSnapshotDerivedFacts {
    data class Input(val bundle: String, val lane: String, val record: SyncSnapshotRecord)
    data class Prepared(val field: List<Any?>? = null, val entity: List<Any?>? = null, val edges: List<List<Any?>> = emptyList())
    /** 复合外键只使用这些共享身份，正文不进入图检查元数据。 */
    private val CONTEXT_FIELDS = setOf("conversationSyncId", "assistantMessageSyncId", "contextRefSyncId")
    /** 旧 Genesis 未提供逻辑时钟时沿用协议的零时钟。 */
    private const val GENESIS_CLOCK = 0L

    /** 摘要、布尔偏好和父引用在事务外完成，保留既有字段值比较语义。 */
    fun prepare(input: Input): Prepared {
        val record = input.record
        val identity = listOf(input.bundle, input.lane, record.key)
        if (record.kind == "FIELD_VERSION") {
            val value = record.value
            val raw = value.getValue("valueJson").jsonPrimitive.content
            return Prepared(field = identity + listOf(value.getValue("versionToken").jsonPrimitive.content,
                value["logicalClock"]?.jsonPrimitive?.longOrNull ?: GENESIS_CLOCK,
                value["causalContextJson"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                SyncOperationCanonicalizer.sha256Hex(raw), raw.takeIf { it == "true" || it == "false" }.orEmpty()))
        }
        if (record.kind != "ENTITY") return Prepared()
        val fields = record.value.getValue("fields").jsonObject
        val context = JsonObject(fields.filter { (key, value) -> key in CONTEXT_FIELDS && value != JsonNull })
        val parents = SyncPagedEntityDependencies.parents(record.value.getValue("entityType").jsonPrimitive.content, fields)
        return Prepared(entity = identity + context.toString(), edges = parents.mapIndexed { ordinal, parent ->
            identity + listOf(ordinal, parent.type, parent.id, parent.generation)
        })
    }
}
