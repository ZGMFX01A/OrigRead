package me.ash.reader.infrastructure.json

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf(String::isNotEmpty)

fun JSONObject.optCleanString(key: String, default: String = ""): String =
    if (!has(key) || isNull(key)) default else optString(key)

/** 数组中的 JSON null 与对象字段遵循相同规则。 */
fun JSONArray.optNullableString(index: Int): String? =
    if (isNull(index)) null else optString(index).trim().takeIf(String::isNotEmpty)
