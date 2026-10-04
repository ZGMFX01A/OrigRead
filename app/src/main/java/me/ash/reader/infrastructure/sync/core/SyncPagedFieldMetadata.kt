package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** FV 的因果证据在索引发布前验证，缺失证据不能被解释为零时钟或空向量。 */
internal object SyncPagedFieldMetadata {
    /** 协议数字必须同时能由 Android Long 与 Desktop Number 精确表示。 */
    private const val MAX_WIRE_INTEGER = 9_007_199_254_740_991L
    /** 原始因果上下文 schema，与历史 Operation 协议保持一致。 */
    private const val CAUSAL_SCHEMA = 1L
    /** Genesis token 的固定编码字段数，包含最后的 framed 身份摘要。 */
    private const val GENESIS_TOKEN_PARTS = 6

    /** token 绑定真实实体字段和逻辑 lane；Operation 候选必须带齐时钟及因果上下文。 */
    fun validate(lane: String, record: SyncSnapshotRecord) {
        val value = record.value
        val token = text(value.getValue("versionToken"))
        if (token.startsWith("GENESIS_V1|")) {
            val parts = token.split('|')
            require(parts.size == GENESIS_TOKEN_PARTS && token == SyncVersionToken.genesis(
                genesisBaselineId = parts[1], replicationLaneId = lane,
                entitySyncId = text(value.getValue("entitySyncId")), fieldId = text(value.getValue("fieldId")))) { invalid() }
            return
        }
        val dot = requireNotNull(SyncVersionToken.parseOperationDot(token)) { invalid() }
        require(dot.replicationLaneId == lane && token == SyncVersionToken.operation(dot.actorIncarnationId, lane, dot.sequence)) { invalid() }
        integer(value.getValue("logicalClock"))
        validateContext(Json.parseToJsonElement(text(value.getValue("causalContextJson"))))
    }

    /** 不允许重复 lane/actor 在解码 Map 时折叠成另一份因果证明。 */
    private fun validateContext(value: JsonElement) {
        val context = objectValue(value)
        require(integer(context.getValue("schemaVersion")) == CAUSAL_SCHEMA) { invalid() }
        val lanes = requireNotNull(context["lanes"] as? JsonArray) { invalid() }
        val seen = mutableSetOf<String>()
        for (item in lanes) {
            val lane = objectValue(item)
            val id = text(lane.getValue("replicationLaneId"))
            require(knownLane(id) && seen.add(id)) { invalid() }
            validateActors(requireNotNull(lane["actors"] as? JsonArray) { invalid() })
        }
        context["observedGenesisBaselinesByLane"]?.takeUnless { it == JsonNull }?.let(::validateObserved)
    }

    /** 序号必须是非负 JSON 数字，字符串数字不能通过跨端校验。 */
    private fun validateActors(values: JsonArray) {
        val seen = mutableSetOf<String>()
        for (item in values) {
            val actor = objectValue(item)
            require(seen.add(text(actor.getValue("actorIncarnationId")))) { invalid() }
            integer(actor.getValue("prefix"))
        }
    }

    /** 历史基线观察按真实 lane 保存唯一身份，不能静默放弃损坏的观察记录。 */
    private fun validateObserved(value: JsonElement) {
        for ((lane, element) in objectValue(value)) {
            require(knownLane(lane)) { invalid() }
            val ids = requireNotNull(element as? JsonArray) { invalid() }.map(::text)
            require(ids.distinct().size == ids.size) { invalid() }
        }
    }

    private fun knownLane(value: String): Boolean = SyncReplicationLane.entries.any { it.wireName == value }
    private fun objectValue(value: JsonElement): JsonObject = requireNotNull(value as? JsonObject) { invalid() }

    /** 字符串标识必须有实际内容，不把其他 JSON 类型转换成字符串。 */
    private fun text(value: JsonElement): String {
        require(value is JsonPrimitive && value.isString && value.content.isNotBlank()) { invalid() }
        return value.content
    }

    /** 数字范围取协议共同可表示范围，不增加快照总量上限。 */
    private fun integer(value: JsonElement): Long {
        require(value is JsonPrimitive && !value.isString && value.longOrNull?.let { it in 0..MAX_WIRE_INTEGER } == true) { invalid() }
        return value.longOrNull!!
    }

    private fun invalid(): String = "SNAPSHOT_CORRUPTED: paged field version has invalid causal metadata"
}
