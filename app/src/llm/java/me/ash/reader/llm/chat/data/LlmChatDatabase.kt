package me.ash.reader.llm.chat.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import me.ash.reader.infrastructure.sync.core.SyncAppliedFrontierEntity
import me.ash.reader.infrastructure.sync.core.SyncLaneWriterStateEntity
import me.ash.reader.infrastructure.sync.core.SyncOutboxDao
import me.ash.reader.infrastructure.sync.core.SyncOutboxEntity
import me.ash.reader.infrastructure.sync.core.SyncProjectionGenesisCutDao
import me.ash.reader.infrastructure.sync.core.SyncProjectionGenesisCutEntity
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingDao
import me.ash.reader.infrastructure.sync.identity.SyncIdentityMappingEntity

@Database(
    entities = [
        LlmConversationEntity::class,
        LlmConversationArticleEntity::class,
        LlmMessageEntity::class,
        LlmToolCallEntity::class,
        LlmContextRefEntity::class,
        LlmEvidenceBlockEntity::class,
        LlmCitationRefEntity::class,
        LlmCitationAnnotationEntity::class,
        LlmCitationAnnotationRefEntity::class,
        SyncIdentityMappingEntity::class,
        SyncLaneWriterStateEntity::class,
        SyncAppliedFrontierEntity::class,
        SyncOutboxEntity::class,
        SyncProjectionGenesisCutEntity::class,
        LlmSyncApplyJournalEntity::class,
    ],
    version = 22,
    exportSchema = true,
)
@TypeConverters(LlmChatConverters::class)
abstract class LlmChatDatabase : RoomDatabase() {
    abstract fun chatDao(): LlmChatDao
    abstract fun syncIdentityMappingDao(): SyncIdentityMappingDao
    abstract fun syncIdentitySourceDao(): LlmSyncIdentitySourceDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun syncProjectionGenesisCutDao(): SyncProjectionGenesisCutDao
    abstract fun syncApplyJournalDao(): LlmSyncApplyJournalDao
}
