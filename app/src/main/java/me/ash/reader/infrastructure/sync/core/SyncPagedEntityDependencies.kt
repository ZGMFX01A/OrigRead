package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long

/** 强依赖来自实际产品外键，可选 article/locator 链接不会被当作级联父实体。 */
internal object SyncPagedEntityDependencies {
    data class Parent(val type: String, val id: String, val generation: Long?)
    /** 按父优先顺序传播真实删除见证，覆盖 Reader、CONFIG 和 Chat 的直接外键。 */
    val order = listOf("group", "feed", "article", "filter_rule", "website_parse_preference", "rsshub_subscription_source", "conversation", "message",
        "tool_call", "context_ref", "evidence_block", "citation_ref", "citation_annotation", "conversation_article", "citation_annotation_ref")
    /** 父类型、共享身份字段及可选的显式父代次字段，使用同一跨端契约。 */
    private val rules = mapOf(
        "feed" to listOf(Triple("group", "groupSyncId", "groupGeneration")), "article" to listOf(Triple("feed", "feedSyncId", "feedGeneration")),
        "filter_rule" to listOf(Triple("feed", "feedSyncId", "feedGeneration")), "website_parse_preference" to listOf(Triple("feed", "feedSyncId", "feedGeneration")),
        "rsshub_subscription_source" to listOf(Triple("feed", "feedSyncId", "feedGeneration")),
        "message" to listOf(Triple("conversation", "conversationSyncId", null)),
        "tool_call" to listOf(Triple("conversation", "conversationSyncId", null), Triple("message", "assistantMessageSyncId", null)),
        "context_ref" to listOf(Triple("conversation", "conversationSyncId", null), Triple("message", "assistantMessageSyncId", null)),
        "evidence_block" to listOf(Triple("context_ref", "contextRefSyncId", null)),
        "citation_ref" to listOf(Triple("conversation", "conversationSyncId", null), Triple("message", "assistantMessageSyncId", null), Triple("context_ref", "contextRefSyncId", null)),
        "citation_annotation" to listOf(Triple("conversation", "conversationSyncId", null), Triple("message", "assistantMessageSyncId", null)),
        "conversation_article" to listOf(Triple("conversation", "conversationSyncId", null)),
        "citation_annotation_ref" to listOf(Triple("citation_annotation", "annotationSyncId", null), Triple("citation_ref", "citationRefSyncId", null)))

    /** 父身份必须存在，父代次保留原始数值；缺失强依赖不能被任意默认实体补齐。 */
    fun parents(type: String, fields: JsonObject): List<Parent> {
        // 全局规则没有父 Feed，偏好和 RSSHub 来源使用协议的嵌套载荷。
        if (type == "filter_rule" && (fields["feedSyncId"] == null || fields["feedSyncId"] == JsonNull)) return emptyList()
        val values = when (type) {
            "website_parse_preference" -> fields.getValue("preference").jsonObject
            "rsshub_subscription_source" -> fields.getValue("source").jsonObject
            else -> fields
        }
        val optionalEvidence = if (type == "citation_ref" && fields["evidenceBlockSyncId"] != null && fields["evidenceBlockSyncId"] != JsonNull)
            listOf(Triple("evidence_block", "evidenceBlockSyncId", null)) else emptyList()
        return (rules[type].orEmpty() + optionalEvidence).map { (parent, field, generation) ->
            val id = values[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            check(!id.isNullOrBlank()) { "SNAPSHOT_CORRUPTED: missing parent identity $type/$field" }
            val value = generation?.let { values[it] }?.takeUnless { it == JsonNull }?.jsonPrimitive?.long
            check(value == null || value >= 0L) { "SNAPSHOT_CORRUPTED: invalid parent generation" }
            Parent(parent, checkNotNull(id), value)
        }
    }
}
