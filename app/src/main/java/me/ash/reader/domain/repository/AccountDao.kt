package me.ash.reader.domain.repository

import androidx.room.*
import java.util.Date
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.account.Account

@Dao
interface AccountDao {

    @Query(
        """
        SELECT * FROM account
        WHERE id = :id
        """
    )
    fun queryAccount(id: Int): Flow<Account?>

    @Query(
        """
        SELECT * FROM account
        """
    )
    suspend fun queryAll(): List<Account>

    @Query(
        """
        SELECT * FROM account
        """
    )
    fun queryAllAsFlow(): Flow<List<Account>>

    @Query(
        """
        SELECT * FROM account
        WHERE id = :id
        """
    )
    suspend fun queryById(id: Int): Account?

    @Insert
    suspend fun insert(account: Account): Long

    @Insert
    suspend fun insertList(accounts: List<Account>): List<Long>

    @Update
    suspend fun update(vararg account: Account)

    /** 刷新成功只推进时间列，避免网络期间的旧账户快照覆盖用户设置。 */
    @Query("UPDATE account SET updateAt = :updatedAt WHERE id = :accountId")
    suspend fun updateSyncTime(accountId: Int, updatedAt: Date)

    /** Fever 同步成功只推进同步时间与增量游标，避免整行回写覆盖同步期间的用户设置修改。 */
    @Query("UPDATE account SET updateAt = :updatedAt, lastArticleId = COALESCE(:lastArticleId, lastArticleId) WHERE id = :accountId")
    suspend fun updateSyncMetadata(accountId: Int, updatedAt: Date, lastArticleId: String?)

    @Delete
    suspend fun delete(vararg account: Account)

    /** 同一 Room 数据库提供的 DAO 在事务内按现有顺序清理，失败时账户和关联数据一起回滚。 */
    @Transaction
    suspend fun deleteWithRelatedData(
        account: Account,
        articleDao: ArticleDao,
        feedDao: FeedDao,
        groupDao: GroupDao,
    ) {
        val accountId = requireNotNull(account.id)
        articleDao.deleteByAccountId(accountId)
        feedDao.deleteByAccountId(accountId)
        groupDao.deleteByAccountId(accountId)
        delete(account)
    }
}
