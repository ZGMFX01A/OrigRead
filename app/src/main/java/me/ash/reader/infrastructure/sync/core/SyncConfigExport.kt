package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 凭据无法安全拆分时暂停配置导出，本机业务值和冻结 Outbox 原样保留。 */
object SyncConfigExport {
    /** 来源配置域；文章/工具内容仍按普通数据处理。 */
    private val CONFIG_TYPES = setOf("feed", "json_rule", "website_rule", "rsshub_settings", "rsshub_subscription_source", "website_parse_preference", "filter_rule")
    /** 认证参数名固定且两端一致；从不删业务 query 后伪装同一来源。 */
    private val SECRET_KEYS = setOf("apikey", "apitoken", "accesskey", "accesstoken", "refreshtoken", "token", "password", "passwd", "secret", "clientsecret", "authorization", "cookie", "key", "code")
    /** 只检查产品协议承载 URL 的字段。 */
    private val URL_FIELDS = setOf("url", "endpoint", "sourceUrl", "originalInput", "preferredInstance", "automaticUrlPattern")
    /** JSON 规则允许输出的正式字段，不把未知扩展夹带到远端。 */
    private val JSON_RULE_FIELDS = setOf("id", "name", "version", "enabled", "hosts", "sourceKind", "endpoint", "itemsPath", "titlePath", "linkPath", "datePath", "authorPath", "descriptionPath", "contentPath", "imagePath", "idPath", "dateFormat", "maxItems")

    /** 检查只发生在构建/导出边界，不能影响本机保存或修改历史签名材料。 */
    fun requireExportable(type: String, value: JsonElement) {
        if (type in CONFIG_TYPES) walk(value, type, "")
    }

    /** 错误只包含字段名，日志不输出完整含密钥地址。 */
    private fun walk(value: JsonElement, type: String, field: String) {
        when (value) {
            is JsonArray -> value.forEach { walk(it, type, field) }
            is JsonObject -> value.forEach { (key, child) ->
                if (normalize(key) in SECRET_KEYS && child != JsonNull && child.toString() != "\"\"") blocked(key)
                if (type == "json_rule" && field == "rule" && key !in JSON_RULE_FIELDS) blocked(key)
                walk(child, type, key)
            }
            is JsonPrimitive -> if (value.isString && field in URL_FIELDS && value.content.isNotBlank()) checkUrl(value.content, field)
        }
    }

    /** 相对 endpoint 仅借固定基址识别 query，永不改写原始 URL。 */
    private fun checkUrl(value: String, field: String) {
        val parsed = value.toHttpUrlOrNull() ?: requireNotNull("https://sync-config.invalid/".toHttpUrlOrNull()?.resolve(value)) {
            "CONFIG_INVALID: invalid URL field $field"
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) blocked(field)
        parsed.queryParameterNames.forEach { key ->
            if (normalize(key) in SECRET_KEYS && parsed.queryParameterValues(key).any { !it.isNullOrEmpty() }) blocked(field)
        }
    }

    private fun normalize(value: String): String = value.lowercase().replace("-", "").replace("_", "")
    private fun blocked(field: String): Nothing = error("CREDENTIAL_REQUIRED: $field 含本机认证信息，配置导出已暂停；请先在本机拆分凭据绑定")
}
