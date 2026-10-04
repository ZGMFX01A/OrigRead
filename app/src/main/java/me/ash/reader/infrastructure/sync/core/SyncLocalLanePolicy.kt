package me.ash.reader.infrastructure.sync.core

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.LocalConfigStateEntity
import kotlinx.coroutines.sync.withLock

/** Device-local, Space-scoped preferences; never replicated as another peer's policy. */
@Singleton
class SyncLocalLanePolicy @Inject constructor(private val database: AndroidDatabase) {
    private val supportedPolicies = setOf("ENABLED", "PAUSED", "UNSUPPORTED", "LOCAL_PURGE")
    private val requiredCoreLanes =
        setOf(
            SyncReplicationLane.CORE_META.wireName,
            SyncReplicationLane.AUTH.wireName,
        )

    suspend fun read(syncSpaceId: String): Map<String, String> =
        database.localConfigStateDao().read(key(syncSpaceId))?.let {
            Json.decodeFromString<Map<String, String>>(it)
                .filter { (lane, policy) ->
                    SyncReplicationLane.entries.any { it.wireName == lane } &&
                        policy in supportedPolicies &&
                        (lane !in requiredCoreLanes || policy == "ENABLED")
                }
        } ?: emptyMap()

    suspend fun set(syncSpaceId: String, lane: SyncReplicationLane, policy: String) {
        require(policy in supportedPolicies)
        require(lane.wireName !in requiredCoreLanes || policy == "ENABLED") {
            "${lane.wireName} is a required Sync core lane and cannot be disabled by local policy"
        }
        // 政策切换与当前引用补齐共用短投影屏障，不能在 Chat 提交期间改变许可。
        database.syncProjectionMutex.withLock {
            database.withTransaction {
                database.localConfigStateDao().write(LocalConfigStateEntity(key(syncSpaceId),
                    Json.encodeToString(read(syncSpaceId) + (lane.wireName to policy))))
            }
        }
    }

    private fun key(space: String): String {
        require(space.isNotBlank())
        return "sync.lane-policy:$space"
    }
}
