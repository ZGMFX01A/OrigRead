package me.ash.reader.infrastructure.json

import kotlinx.serialization.Serializable

/** 可导入的 JSON/API 来源规则集合。 */
@Serializable
data class JsonRuleBundle(
    val schemaVersion: Int = 1,
    val rules: List<JsonRule>,
)

/** JSON 数据实际所在位置。旧规则未声明时默认继续按独立 API 处理。 */
@Serializable
enum class JsonSourceKind {
    API,
    NEXT_DATA,
    NUXT_DATA,
}

/**
 * JSON/API 文章列表规则。路径使用受限 JSONPath：支持 $.a.b、数组下标和 [*]。
 */
@Serializable
data class JsonRule(
    val id: String,
    val name: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    val hosts: List<String>,
    val sourceKind: JsonSourceKind = JsonSourceKind.API,
    val endpoint: String,
    val itemsPath: String,
    val titlePath: String,
    val linkPath: String,
    val datePath: String? = null,
    val authorPath: String? = null,
    val descriptionPath: String? = null,
    /** 可选正文路径；未声明时兼容旧规则，继续使用 descriptionPath。 */
    val contentPath: String? = null,
    val imagePath: String? = null,
    val idPath: String? = null,
    val dateFormat: String? = null,
    val maxItems: Int = 50,
)

