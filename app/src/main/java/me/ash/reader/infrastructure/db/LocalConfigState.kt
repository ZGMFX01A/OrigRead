package me.ash.reader.infrastructure.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "local_config_state")
data class LocalConfigStateEntity(@PrimaryKey val key: String, val value: String)

@Dao
interface LocalConfigStateDao {
    @Query("SELECT value FROM local_config_state WHERE `key`=:key")
    suspend fun read(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(value: LocalConfigStateEntity)
}

val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE local_config_state (`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))")
    }
}
