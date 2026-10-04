package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 旧版空值误编码只按原作者已验签的真实载荷恢复，不重写历史操作或伪造版本。 */
class SyncFeedIconRepair @Inject constructor(
    private val database: AndroidDatabase,
    private val fields: SyncFieldStateRows,
    private val remote: SyncRemoteApplyCoordinator,
) {
    @Inject lateinit var aliases: AndroidSyncAliasResolver
    @Inject lateinit var resolver: SyncPagedFieldResolver
    /** 在固定 cut 前修复已知误编码，整个候选、winner 和业务投影一起提交。 */
    suspend fun repair(space: String) = database.syncProjectionMutex.withLock { database.withTransaction {
        val sql = database.openHelper.writableDatabase
        val identities = sql.query("""SELECT entitySyncId,entityGeneration,versionToken,sourceOperationId
            FROM sync_field_candidate c WHERE syncSpaceId=? AND entityType='feed' AND fieldId='icon'
            AND valueJson=? AND sourceOperationId IS NOT NULL
            AND NOT EXISTS(SELECT 1 FROM sync_inbox_operation i WHERE i.operationId=c.sourceOperationId AND i.state<>'APPLIED')""",
            arrayOf(space, MISENCODED_NULL)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Identity(cursor.getString(0), cursor.getLong(1),
                cursor.getString(2), cursor.getString(3))) }
        }
        // 全部历史先恢复，再裁决共享物理行，避免较新的别名合法值被旧身份清空。
        val restored = buildList { for (identity in identities) if (repairCandidate(space, identity)) add(identity) }
        for (identity in restored) repairProjection(space, identity)
    } }

    /** 字符串 "null" 是合法用户值；只有原签名明确承诺 JSON null 时才进行修复。 */
    private suspend fun repairCandidate(space: String, identity: Identity): Boolean {
        val operation = database.syncOperationDao().findById(identity.operationId) ?: return false
        val payload = Json.parseToJsonElement(operation.payloadJson).jsonObject
        val value = if (operation.operationType == "FIELD_SET") {
            if (payload["field"]?.jsonPrimitive?.content != "icon") return false
            payload["value"]
        } else ((payload["fields"] as? JsonObject) ?: payload)["icon"]
        if (value != JsonNull) return false
        check(operation.syncSpaceId == space && operation.entityType == "feed" &&
            operation.entitySyncId == identity.id && operation.entityGeneration == identity.generation &&
            SyncVersionToken.operation(operation.actorIncarnationId, operation.replicationLaneId, operation.sequence) == identity.token) {
            "SNAPSHOT_CORRUPTED: icon repair source identity mismatch"
        }
        remote.verifySnapshotOperation(SyncOperationWireCodec.toWire(operation))
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""UPDATE sync_field_candidate SET valueJson=? WHERE syncSpaceId=? AND entityType='feed'
            AND entitySyncId=? AND entityGeneration=? AND fieldId='icon' AND versionToken=?""",
            arrayOf(JsonNull.toString(), space, identity.id, identity.generation, identity.token))
        val field = SyncFieldStateRows.Field(space, "feed", identity.id, "icon")
        val current = fields.find(field) ?: return false
        if (current.versionToken != identity.token || current.entityGeneration != identity.generation || current.valueJson != MISENCODED_NULL) return false
        database.syncInboxDao().upsertFieldVersion(current.copy(valueJson = JsonNull.toString()))
        return true
    }

    /** 同代次别名可能共用物理 Feed；只修复本空间当前账户中仍存活的真实拥有者。 */
    private suspend fun repairProjection(space: String, identity: Identity) {
        val mapping = aliases.resolveMapping(space, "feed", identity.id, identity.generation) ?: return
        val tombstone = database.syncInboxDao().findTombstone(space, "feed", mapping.syncId)
        if (tombstone != null && tombstone.entityGeneration >= identity.generation) return
        val binding = checkNotNull(database.syncRuntimeDao().findBindingBySpace(space)) { "Missing icon repair Space binding" }
        val feed = database.feedDao().queryById(mapping.localId) ?: return
        check(mapping.generation == identity.generation && feed.accountId == binding.localAccountId) {
            "SNAPSHOT_CORRUPTED: icon repair projection belongs to another generation or account"
        }
        val members = aliases.componentMembers(space, "feed", identity.id, identity.generation).filter { id ->
            val deleted = database.syncInboxDao().findTombstone(space, "feed", id)
            deleted == null || deleted.entityGeneration < identity.generation
        }
        val winner = resolver.resolveRecords("icon", { members.asSequence().flatMap { id ->
            fields.candidates(SyncFieldStateRows.Candidates(SyncFieldStateRows.Field(space, "feed", id, "icon"), identity.generation))
        } })
        val icon = Json.parseToJsonElement(winner.value.getValue("valueJson").jsonPrimitive.content).jsonPrimitive.contentOrNull
        if (feed.icon != icon) database.feedDao().updateAll(listOf(feed.copy(icon = icon)))
    }

    private data class Identity(val id: String, val generation: Long, val token: String, val operationId: String)

    companion object {
        /** 旧 JsonNull.content 误写的 JSON 字符串，不能和真正的 JSON null 混用。 */
        private const val MISENCODED_NULL = "\"null\""
    }
}
