package me.ash.reader.infrastructure.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 受限 JSONPath 执行器，避免引入重量级依赖。
 * 支持：$.data.items、$[0]、$.items[*].title。
 */
object SimpleJsonPath {
    fun query(root: JsonElement, path: String): List<JsonElement> {
        val tokens = tokenize(path)
        return tokens.fold(listOf(root)) { current, token ->
            current.flatMap { element -> token.read(element) }
        }
    }

    fun first(root: JsonElement, path: String?): JsonElement? =
        path?.takeIf(String::isNotBlank)?.let { query(root, it).firstOrNull() }

    private fun tokenize(path: String): List<PathToken> {
        require(path.startsWith("$")) { "JSONPath 必须以 $ 开头：$path" }
        val tokens = mutableListOf<PathToken>()
        var index = 1
        while (index < path.length) {
            when (path[index]) {
                '.' -> {
                    index++
                    val start = index
                    while (index < path.length && path[index] != '.' && path[index] != '[') index++
                    require(index > start) { "JSONPath 字段名不能为空：$path" }
                    tokens += PathToken.Field(path.substring(start, index))
                }

                '[' -> {
                    val end = path.indexOf(']', index)
                    require(end > index) { "JSONPath 数组表达式不完整：$path" }
                    val value = path.substring(index + 1, end)
                    tokens += if (value == "*") PathToken.Wildcard else PathToken.Index(value.toInt())
                    index = end + 1
                }

                else -> error("不支持的 JSONPath 语法：$path")
            }
        }
        return tokens
    }

    private sealed interface PathToken {
        fun read(element: JsonElement): List<JsonElement>

        data class Field(val name: String) : PathToken {
            override fun read(element: JsonElement): List<JsonElement> =
                listOfNotNull((element as? JsonObject)?.get(name))
        }

        data class Index(val index: Int) : PathToken {
            override fun read(element: JsonElement): List<JsonElement> =
                listOfNotNull((element as? JsonArray)?.getOrNull(index))
        }

        data object Wildcard : PathToken {
            override fun read(element: JsonElement): List<JsonElement> =
                (element as? JsonArray)?.toList().orEmpty()
        }
    }
}

