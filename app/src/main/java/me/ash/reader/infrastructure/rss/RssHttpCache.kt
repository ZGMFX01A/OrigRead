package me.ash.reader.infrastructure.rss

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import me.ash.reader.domain.model.feed.Feed

/**
 * RSS 条件请求的内部性能缓存。
 *
 * 该表不属于 Feed/备份契约，只保存 HTTP validator；Feed 删除时随外键级联清理。
 */
@Entity(
    tableName = "rss_http_cache",
    foreignKeys = [
        ForeignKey(
            entity = Feed::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["feedId"]), Index(value = ["feedUrl"])],
)
data class RssHttpCache(
    @PrimaryKey val feedId: String,
    val feedUrl: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val updatedAt: Long,
)

@Dao
interface RssHttpCacheDao {
    @Query("SELECT * FROM rss_http_cache WHERE feedId = :feedId LIMIT 1")
    suspend fun query(feedId: String): RssHttpCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: RssHttpCache)

    @Query("DELETE FROM rss_http_cache WHERE feedId = :feedId")
    suspend fun delete(feedId: String)
}
