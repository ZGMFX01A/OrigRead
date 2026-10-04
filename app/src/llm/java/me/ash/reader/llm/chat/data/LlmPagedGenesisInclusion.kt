package me.ash.reader.llm.chat.data

import androidx.room.withTransaction
import javax.inject.Inject
import me.ash.reader.infrastructure.sync.core.SyncProjectionGenesisInclusion

/** Chat writer 是 AI_HISTORY inclusion 的 authority，Reader 发布重试不会复制或重建 AI Outbox。 */
class LlmPagedGenesisInclusion @Inject constructor(private val database: LlmChatDatabase) {
    /** 在所属数据库按连续 cut 前缀更新，处理任意 Outbox 总量而不读出 payload。 */
    suspend fun mark(options: SyncProjectionGenesisInclusion) {
        val frontier = options.laneFrontiers["AI_HISTORY"] ?: return
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            for ((actor, prefix) in frontier) {
                sql.execSQL("""UPDATE sync_outbox SET status='BUILT',genesisIncludedAt=?,updatedAt=?
                    WHERE syncSpaceId=? AND replicationLaneId='AI_HISTORY' AND actorIncarnationId=? AND sequence<=?
                      AND status IN ('PENDING_BUILD','BUILT') AND genesisIncludedAt IS NULL""",
                    arrayOf(options.now, options.now, options.syncSpaceId, actor, prefix))
            }
        }
    }
}
