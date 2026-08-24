package me.ash.reader.llm.chat.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [LlmConversationEntity::class, LlmMessageEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(LlmChatConverters::class)
abstract class LlmChatDatabase : RoomDatabase() {
    abstract fun chatDao(): LlmChatDao
}
