package me.ash.reader.domain.model.article

import androidx.room.*
import me.ash.reader.domain.model.feed.Feed
import java.util.*

/**
 * TODO: Add class description
 */
@Entity(
    tableName = "article",
    foreignKeys = [ForeignKey(
        entity = Feed::class,
        parentColumns = ["id"],
        childColumns = ["feedId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )],
    indices = [
        Index(name = "index_article_feedId", value = ["feedId"]),
        Index(name = "index_article_accountId", value = ["accountId"]),
        Index(name = "index_article_feedId_link", value = ["feedId", "link"]),
        Index(
            name = "index_article_accountId_isUnread_date",
            value = ["accountId", "isUnread", "date"],
        ),
        Index(
            name = "index_article_accountId_isStarred_date",
            value = ["accountId", "isStarred", "date"],
        ),
    ],
)
data class Article(
    @PrimaryKey
    var id: String,
    @ColumnInfo
    var date: Date,
    @ColumnInfo
    var title: String,
    @ColumnInfo
    var author: String? = null,
    @ColumnInfo
    var rawDescription: String,
    @ColumnInfo
    var shortDescription: String,
    @ColumnInfo
    @Deprecated("fullContent is the same as rawDescription")
    var fullContent: String? = null,
    @ColumnInfo
    var img: String? = null,
    @ColumnInfo
    var link: String,
    @ColumnInfo
    var feedId: String,
    @ColumnInfo
    var accountId: Int,
    @ColumnInfo
    var isUnread: Boolean = true,
    @ColumnInfo
    var isStarred: Boolean = false,
    @ColumnInfo
    var isReadLater: Boolean = false,
    @ColumnInfo
    var updateAt: Date? = null,
) {

    @Ignore
    var dateString: String? = null
}
