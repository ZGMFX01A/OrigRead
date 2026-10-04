package me.ash.reader.infrastructure.sync.core

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.sync.identity.SyncCanonicalIdentity
import me.ash.reader.infrastructure.sync.identity.SyncEntityType

/** 增量和 tail 联合物化同一父订阅配置，各逻辑寄存器继续保存自己的真实候选。 */
class SyncFeedConfigProjection @Inject constructor(
    private val database: AndroidDatabase,
    private val fields: SyncFieldStateRows,
    private val aliases: AndroidSyncAliasResolver,
) {
    @Inject lateinit var resolver: SyncPagedFieldResolver
    data class Current(val field: SyncFieldStateRows.Candidates, val candidate: SyncFieldCandidate)

    /** 只选择同一父代次组件的当前存活配置，独立配置代次不参与父身份猜测。 */
    suspend fun groups(input: Current): List<SyncFieldStateRows.Candidates>? {
        val type = input.field.field.entityType
        if (FEED_FIELDS[type] != input.field.field.fieldId) return null
        val parent = Json.parseToJsonElement(input.candidate.valueJson).jsonObject
        val generation = parent["feedGeneration"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.long ?: return null
        val ids = aliases.componentMembers(input.field.field.syncSpaceId, "feed", parent.getValue("feedSyncId").jsonPrimitive.content, generation)
        val enumType = SyncEntityType.entries.first { it.wireName == type }
        val result = mutableListOf(input.field)
        for (id in ids) {
            val syncId = SyncCanonicalIdentity.configRuleSyncId(enumType, id)
            if (syncId == input.field.field.entitySyncId) continue
            val mapping = database.syncIdentityMappingDao().findBySyncId(input.field.field.syncSpaceId, type, syncId) ?: continue
            val deleted = database.syncInboxDao().findTombstone(input.field.field.syncSpaceId, type, syncId)
            if (deleted != null && deleted.entityGeneration >= mapping.generation) continue
            result.add(SyncFieldStateRows.Candidates(input.field.field.copy(entitySyncId = syncId), mapping.generation))
        }
        return result
    }

    /** 全部真实候选裁决后只返回业务投影，不能把另一个身份的候选搬到当前寄存器。 */
    fun project(input: Current, groups: List<SyncFieldStateRows.Candidates>): SyncFieldCandidate {
        val record = resolver.resolveRecords(input.field.field.fieldId, { groups.asSequence().flatMap { fields.candidates(it) } })
        val value = record.value
        val winner = Json.parseToJsonElement(value.getValue("valueJson").jsonPrimitive.content).jsonObject
        val parent = Json.parseToJsonElement(input.candidate.valueJson).jsonObject
        val enumType = SyncEntityType.entries.first { it.wireName == input.field.field.entityType }
        val winnerIdentity = SyncCanonicalIdentity.configRuleSyncId(enumType, winner.getValue("feedSyncId").jsonPrimitive.content)
        check(winner.getValue("feedGeneration") == parent.getValue("feedGeneration") &&
            groups.any { it.field.entitySyncId == winnerIdentity }) { "CONFIG winner references another Feed generation" }
        // 关系已由 groups 的同代次组件证明；保持当前配置身份的父引用用于既有身份校验。
        val body = JsonObject(winner + ("feedSyncId" to parent.getValue("feedSyncId")))
        val token = value.getValue("versionToken").jsonPrimitive.content
        return SyncFieldCandidate(input.field.field.fieldId, body.toString(), token, SyncVersionToken.source(token),
            causalContextJson = value["causalContextJson"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content,
            logicalClock = value.getValue("logicalClock").jsonPrimitive.long)
    }

    companion object {
        /** 同一订阅的两个原子配置寄存器，其他规则实体继续独立处理。 */
        private val FEED_FIELDS = mapOf("website_parse_preference" to "preference", "rsshub_subscription_source" to "source")
    }
}
