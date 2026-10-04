package me.ash.reader.infrastructure.sync.core

import java.security.MessageDigest
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull

object SyncOperationCanonicalizer {
    private val json = Json

    /** 协议摘要固定为小写十六进制；直接编码字节，避免每个摘要创建 32 个格式解析器。 */
    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHexString()

    /**
     * Deterministic JSON used by payload hashes and signing material.
     * Object keys are sorted recursively; array order and primitive values are preserved.
     */
    fun canonicalJson(value: String): String =
        canonicalize(json.parseToJsonElement(value))

    /** 已解码值直接规范编码，来源片段输出不再制造 stringify/parse 的完整副本。 */
    internal fun canonicalValue(value: JsonElement): String = canonicalize(value)

    fun signingDigest(operation: SyncOperationEntity): String =
        sha256Hex(signingMaterial(operation))

    /** Stable protocol identity. Device-local outbox IDs are intentionally not wire identities. */
    fun operationId(
        syncSpaceId: String,
        actorIncarnationId: String,
        replicationLaneId: String,
        sequence: Long,
    ): String {
        require(syncSpaceId.isNotBlank()) { "syncSpaceId must not be blank" }
        require(actorIncarnationId.isNotBlank()) { "actorIncarnationId must not be blank" }
        require(replicationLaneId.isNotBlank()) { "replicationLaneId must not be blank" }
        require(sequence > 0) { "sequence must be positive" }
        val material =
            buildString {
                append("ORIGREAD_SYNC_OPERATION_ID_V1\n")
                field("syncSpaceId", syncSpaceId)
                field("actorIncarnationId", actorIncarnationId)
                field("replicationLaneId", replicationLaneId)
                field("sequence", sequence.toString())
            }
        return "op1:${sha256Hex(material)}"
    }

    internal fun signingMaterial(operation: SyncOperationEntity): String =
        buildString {
            append("ORIGREAD_SYNC_OPERATION_V1\n")
            field("operationId", operation.operationId)
            field("syncSpaceId", operation.syncSpaceId)
            field("authorDeviceId", operation.authorDeviceId)
            field("actorIncarnationId", operation.actorIncarnationId)
            field("replicationLaneId", operation.replicationLaneId)
            field("sequence", operation.sequence.toString())
            field("logicalClock", operation.logicalClock.toString())
            field("causalContextJson", operation.causalContextJson)
            field("dependencyDotsJson", operation.dependencyDotsJson)
            field("entityType", operation.entityType)
            field("entitySyncId", operation.entitySyncId)
            field("entityGeneration", operation.entityGeneration.toString())
            field("operationType", operation.operationType)
            field("payloadSchemaVersion", operation.payloadSchemaVersion.toString())
            field("schemaVersion", operation.schemaVersion.toString())
            nullableField("authGrantId", operation.authGrantId)
            nullableField("authEpoch", operation.authEpoch?.toString())
            field("createdWallClock", operation.createdWallClock.toString())
            field("payloadHash", operation.payloadHash)
        }

    private fun StringBuilder.field(name: String, value: String) {
        append(name)
        append('=')
        append(value.toByteArray(Charsets.UTF_8).size)
        append(':')
        append(value)
        append('\n')
    }

    private fun StringBuilder.nullableField(name: String, value: String?) {
        if (value == null) {
            append(name)
            append("=-1:\n")
        } else {
            field(name, value)
        }
    }

    private fun canonicalize(element: JsonElement): String =
        when (element) {
            is JsonObject ->
                element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
                    "${quote(key)}:${canonicalize(value)}"
                }
            is JsonArray -> element.joinToString(",", "[", "]", transform = ::canonicalize)
            JsonNull -> "null"
            is JsonPrimitive -> when {
                element.isString -> quote(element.content)
                element.content == "true" || element.content == "false" -> element.content
                else -> canonicalNumber(element.content)
            }
        }

    private fun quote(value: String): String {
        var index = 0
        while (index < value.length) {
            val unit = value[index++]
            if (unit.isHighSurrogate()) {
                require(index < value.length && value[index++].isLowSurrogate()) { "Unpaired Unicode surrogate" }
            } else require(!unit.isLowSurrogate()) { "Unpaired Unicode surrogate" }
        }
        return JsonPrimitive(value).toString()
    }

    /** Shortest round-tripping binary64 decimal, with ECMAScript's fixed/exponent thresholds. */
    private fun canonicalNumber(raw: String): String {
        val value = raw.toDouble()
        require(value.isFinite()) { "Non-finite number is not valid canonical JSON" }
        // binary64 安全整数位于 ECMAScript 固定十进制区间；无需搜索有效位数。
        // 只优化可精确表示的整数，不能将普通小数或大整数静默取整。
        if (kotlin.math.abs(value) <= 9_007_199_254_740_991.0) {
            val integer = value.toLong()
            if (integer.toDouble() == value) return integer.toString()
        }
        if (value == 0.0) return "0"
        val exact = BigDecimal(value)
        val shortest = (1..17).firstNotNullOf { precision ->
            exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
                .takeIf { it.toDouble() == value }
        }.stripTrailingZeros()
        val magnitude = kotlin.math.abs(value)
        if (magnitude >= 1e-6 && magnitude < 1e21) return shortest.toPlainString()
        val digits = shortest.unscaledValue().abs().toString()
        val exponent = digits.length - shortest.scale() - 1
        val fraction = if (digits.length == 1) digits else digits.first() + "." + digits.drop(1)
        return (if (value < 0) "-" else "") + fraction + "e" + (if (exponent >= 0) "+" else "") + exponent
    }
}
