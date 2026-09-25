package me.ash.reader.infrastructure.sync.identity

/**
 * R10 跨端协议使用的实体类型名称。
 *
 * wireName 一旦进入同步历史后必须保持稳定；本地类名、Room 表名或 Desktop 表名均不能直接
 * 充当协议类型名。
 */
enum class SyncEntityType(val wireName: String) {
    GROUP("group"),
    FEED("feed"),
    ARTICLE("article"),
    FILTER_RULE("filter_rule"),
    WEBSITE_RULE("website_rule"),
    JSON_RULE("json_rule"),
    RSSHUB_SETTINGS("rsshub_settings"),
    WEBSITE_PARSE_PREFERENCE("website_parse_preference"),
    RSSHUB_SUBSCRIPTION_SOURCE("rsshub_subscription_source"),
    CONVERSATION("conversation"),
    CONVERSATION_ARTICLE("conversation_article"),
    MESSAGE("message"),
    TOOL_CALL("tool_call"),
    CONTEXT_REF("context_ref"),
    EVIDENCE_BLOCK("evidence_block"),
    CITATION_REF("citation_ref"),
    CITATION_ANNOTATION("citation_annotation"),
    CITATION_ANNOTATION_REF("citation_annotation_ref"),
    ALIAS_EDGE("alias_edge");

    companion object {
        fun fromWireName(value: String): SyncEntityType? =
            entries.firstOrNull { it.wireName == value }
    }
}
