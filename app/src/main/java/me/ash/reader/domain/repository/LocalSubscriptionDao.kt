package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Transaction
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed

/** Local source first-subscription write boundary: Feed and its probe articles commit together. */
@Dao
interface LocalSubscriptionDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertFeed(feed: Feed)

    @Insert
    suspend fun insertArticles(articles: List<Article>)

    @Transaction
    suspend fun insertFeedWithArticles(feed: Feed, articles: List<Article>) {
        insertFeed(feed)
        if (articles.isNotEmpty()) insertArticles(articles)
    }
}
