package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 在 parser 丢失重复字段前检查原始 JSON；不改变已签名 canonical bytes。 */
object SyncStrictJson {
    /** 接收 JSON 使用已有 canonical 输入域的有界嵌套。 */
    private const val MAX_JSON_NESTING = 64
    private data class Container(val objectValue: Boolean, var keyExpected: Boolean, val keys: MutableSet<String> = mutableSetOf())

    /** 词法判重使用解码后的属性名，完整语法交给 kotlinx.serialization 检查。 */
    fun parse(raw: String): JsonElement {
        val stack = mutableListOf<Container>()
        var index = 0
        while (index < raw.length) {
            when (raw[index]) {
                '"' -> {
                    val end = stringEnd(raw, index)
                    val container = stack.lastOrNull()
                    if (container?.objectValue == true && container.keyExpected) {
                        val key = Json.parseToJsonElement(raw.substring(index, end + 1)).jsonPrimitive.content
                        require(container.keys.add(key)) { "INVALID_OPERATION: duplicate JSON property" }
                        container.keyExpected = false
                    }
                    index = end
                }
                '{', '[' -> {
                    stack.add(Container(raw[index] == '{', raw[index] == '{'))
                    require(stack.size <= MAX_JSON_NESTING) { "INVALID_OPERATION: JSON nesting exceeds canonical profile" }
                }
                '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                ',' -> stack.lastOrNull()?.takeIf { it.objectValue }?.let { it.keyExpected = true }
            }
            index++
        }
        return Json.parseToJsonElement(raw).also(::validateEnvelopes)
    }

    /** compatibility=3 的 envelope 为封闭契约；未知签名扩展不能丢弃后中继。 */
    private fun validateEnvelopes(value: JsonElement) {
        when (value) {
            is JsonArray -> value.forEach(::validateEnvelopes)
            is JsonObject -> {
                if ("operationId" in value && "authorSignature" in value && "payloadJson" in value) {
                    val descriptor = SyncOperationEnvelope.serializer().descriptor
                    val fields = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
                    require(value.keys.all { it in fields }) { "INVALID_OPERATION: unsupported envelope extension" }
                }
                value.values.forEach(::validateEnvelopes)
            }
            else -> Unit
        }
    }

    /** 字符串内转义不影响对象层级，缺少闭引号立即报告输入损坏。 */
    private fun stringEnd(raw: String, start: Int): Int {
        var index = start + 1
        while (index < raw.length) {
            if (raw[index] == '\\') index++ else if (raw[index] == '"') return index
            index++
        }
        error("INVALID_OPERATION: unterminated JSON string")
    }
}
