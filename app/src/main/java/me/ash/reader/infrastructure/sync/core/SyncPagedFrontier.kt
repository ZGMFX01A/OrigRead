package me.ash.reader.infrastructure.sync.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 分页格式只接受单个真实 lane 的规范 frontier，不能用历史解析器合并重复行。 */
object SyncPagedFrontier {
    /** Android Long 与 Desktop Number 的共同精确整数范围。 */
    private const val MAX_WIRE_INTEGER = 9_007_199_254_740_991L

    /** 逐个 actor 严格检查数值类型和连续前缀，不把字符串数字或负数补成有效覆盖度。 */
    fun decode(lane: SyncSnapshotLanePages): Map<String, Long> {
        val rows = Json.parseToJsonElement(lane.frontierJson)
        require(rows is JsonArray && rows.size == 1) { invalid() }
        val row = rows.single()
        require(row is JsonObject && row["replicationLaneId"] is JsonPrimitive &&
            row.getValue("replicationLaneId").jsonPrimitive.isString &&
            row.getValue("replicationLaneId").jsonPrimitive.content == lane.replicationLaneId) { invalid() }
        val actors = row["actorFrontiers"]
        require(actors is JsonObject) { invalid() }
        return actors.mapValues { (actor, value) ->
            require(actor.isNotBlank() && value is JsonPrimitive && !value.isString &&
                value.longOrNull?.let { it in 0..MAX_WIRE_INTEGER } == true) { invalid() }
            requireNotNull(value.longOrNull)
        }
    }

    /** 非法 frontier 在页面接收前暴露，不允许安装后才发现覆盖度被合并。 */
    private fun invalid(): String = "SNAPSHOT_CORRUPTED: invalid paged Snapshot logical frontier"
}
