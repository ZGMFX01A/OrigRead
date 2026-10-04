package me.ash.reader.infrastructure.sync.core

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 只复用相同 AUTH 内容的纯解码和格式校验，授权结论仍由每次读取的持久账本决定。 */
@Singleton
class SyncValidatedAuthObjects @Inject constructor() {
    private data class Decoded(val json: String, val value: SyncAuthProtocolObject)
    private val decoded = ConcurrentHashMap<String, Decoded>()
    private val validated = ConcurrentHashMap<String, SyncAuthProtocolObject>()
    private val payloads = ConcurrentHashMap<String, JsonObject>()

    /** 只复用完全相同正文的 JSON 解析，当前 grant、cutoff 和 checkpoint 判断仍每次执行。 */
    fun payload(value: String): JsonObject = payloads.computeIfAbsent(value) {
        immutableJson(Json.parseToJsonElement(it)).jsonObject
    }

    /** JSON 只读接口仍可能暴露可变视图，递归冻结 entry、集合及所有子节点后才共享。 */
    private fun immutableJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(Collections.unmodifiableMap(value.mapValues { (_, child) -> immutableJson(child) }))
        is JsonArray -> JsonArray(Collections.unmodifiableList(value.map(::immutableJson)))
        else -> value
    }

    /** 调用方仍逐次查询当前账本；同一对象正文变化时必须完整重新解码及校验。 */
    fun decode(row: SyncAuthLedgerEntity): SyncAuthProtocolObject {
        val key = "${row.syncSpaceId}\u0000${row.authObjectId}"
        return checkNotNull(decoded.compute(key) { _, old ->
            if (old?.json == row.authObjectJson) old else {
                val value = immutable(SyncAuthWireCodec.decode(row.authObjectJson))
                validated[identity(value)] = value
                Decoded(row.authObjectJson, value)
            }
        }).value
    }

    /** 完整对象相等才复用校验，不能仅凭 ID、签名或 payload 摘要接受变更内容。 */
    fun validate(value: SyncAuthProtocolObject) {
        // 先冻结再验证同一份内容，避免校验后调用方改变 Map 而污染比较基线。
        val snapshot = immutable(value)
        validated.compute(identity(snapshot)) { _, old ->
            if (old == snapshot) old else {
                SyncAuthWireCodec.validate(snapshot)
                snapshot
            }
        }
    }

    /** 空间也是缓存身份的一部分，防止同名对象跨空间共享内容。 */
    private fun identity(value: SyncAuthProtocolObject): String = "${value.syncSpaceId}\u0000${value.authObjectId}"

    /** 深拷贝并冻结嵌套 coverage，调用方修改原始 Map 时不能同时修改已验证比较基线。 */
    private fun immutable(value: SyncAuthProtocolObject): SyncAuthProtocolObject = value.copy(
        previousEpochFinalAcceptedPrefixByActorLane = immutableCoverage(value.previousEpochFinalAcceptedPrefixByActorLane),
        revokeCutoffByActorLane = value.revokeCutoffByActorLane?.let(::immutableCoverage),
    )

    /** 两层 Map 都不可变，原 JSON、签名及其他不可变字段保持原值。 */
    private fun immutableCoverage(value: SyncCoverage): SyncCoverage = Collections.unmodifiableMap(
        value.mapValues { (_, actors) -> Collections.unmodifiableMap(actors.toMap()) },
    )
}
