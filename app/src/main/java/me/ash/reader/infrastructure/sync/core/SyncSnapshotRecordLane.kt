package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.jsonPrimitive

/** 类型所属 lane 是业务协议的一部分，未知类型不能在安装后被静默忽略。 */
object SyncSnapshotRecordLane {
    /** 与 Desktop 共用的六个逻辑域；页面编号不产生新的 lane。 */
    private val entityTypes = mapOf(
        "CORE_META" to setOf("sync_core"), "LIBRARY" to setOf("group", "feed"), "ARTICLE_STATE" to setOf("article"),
        "CONFIG" to setOf("filter_rule", "website_rule", "json_rule", "rsshub_settings", "website_parse_preference", "rsshub_subscription_source"),
        "AI_HISTORY" to setOf("conversation", "conversation_article", "message", "tool_call", "context_ref", "evidence_block",
            "citation_ref", "citation_annotation", "citation_annotation_ref"), "AUTH" to emptySet(),
    )

    /** 元数据沿用同一业务类型归属，别名删除属于 CORE_META。 */
    fun entityLane(type: String): String = if (type == "alias_edge") "CORE_META" else
        entityTypes.entries.singleOrNull { type in it.value }?.key ?: error(invalid())

    /** 记录进入索引前检查域和实体类型，阻止跨域覆盖以及无法物化的未知实体。 */
    fun validate(lane: String, record: SyncSnapshotRecord) {
        val types = requireNotNull(entityTypes[lane]) { invalid() }
        val value = record.value
        if (record.kind in setOf("ENTITY", "FIELD_VERSION", "TOMBSTONE")) {
            val type = value.getValue("entityType").jsonPrimitive.content
            val aliasDelete = lane == "CORE_META" && record.kind == "TOMBSTONE" && type == "alias_edge"
            require(aliasDelete || type in types) { invalid() }
        }
        if (record.kind == "BLOB_REFERENCE") require(value.getValue("replicationLaneId").jsonPrimitive.content == lane &&
            value.getValue("ownerEntityType").jsonPrimitive.content in types) { invalid() }
        if (record.kind == "AUTH_OBJECT") require(lane == "AUTH") { invalid() }
        if (record.kind == "ALIAS_EDGE") require(lane == "CORE_META" &&
            entityTypes.values.any { value.getValue("targetEntityType").jsonPrimitive.content in it }) { invalid() }
        if (record.kind == "FIELD_VERSION") SyncPagedFieldMetadata.validate(lane, record)
    }

    /** 结构错误在安装事务之前报告，不补默认类型或跳过记录。 */
    private fun invalid(): String = "SNAPSHOT_CORRUPTED: Snapshot record type does not belong to its lane"
}
