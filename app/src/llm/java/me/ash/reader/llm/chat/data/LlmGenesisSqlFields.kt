package me.ash.reader.llm.chat.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** SQL 单行值保持数据库的真实 null 与数值类型，字段缺失不伪造默认值。 */
internal class LlmGenesisSqlFields(private val row: JsonObject) {
    /** 必需文本缺失时暴露源数据错误，避免改变历史消息的语义。 */
    fun text(column: String): String = requireNotNull(nullableText(column)) { "Missing Genesis SQL field: $column" }

    /** 可空字段只接受数据库明确的 null；不存在的列属于 schema 错误。 */
    fun nullableText(column: String): String? {
        val value = requireNotNull(row[column]) { "Missing Genesis SQL column: $column" }
        return if (value == JsonNull) null else value.jsonPrimitive.content
    }

    /** 时间和计数按原始整数读取，损坏值不降级为零。 */
    fun number(column: String): Long = requireNotNull(nullableNumber(column)) { "Missing Genesis SQL number: $column" }

    /** 可空计数保留 null；非数值列显式失败。 */
    fun nullableNumber(column: String): Long? {
        if (nullableText(column) == null) return null
        return requireNotNull(row.getValue(column).jsonPrimitive.longOrNull) { "Invalid Genesis SQL number: $column" }
    }

    /** Room 的布尔列使用 SQLite 整数，沿用非零表示 true 的规则。 */
    fun flag(column: String): Boolean = number(column) != 0L
}
