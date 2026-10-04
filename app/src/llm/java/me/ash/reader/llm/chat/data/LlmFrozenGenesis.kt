package me.ash.reader.llm.chat.data

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import me.ash.reader.infrastructure.sync.core.SyncOperationCanonicalizer
import me.ash.reader.infrastructure.sync.core.SyncRawSnapshotFreeze

/** Chat typed staging 与 Reader 先后提交，各自 receipt 都完成才能转换同一个 cut。 */
internal class LlmFrozenGenesis @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chat: LlmChatDatabase,
) {
    suspend fun freeze(cut: String) = SyncRawSnapshotFreeze.capture(chat, cut)
    fun ready(cut: String): Boolean = SyncRawSnapshotFreeze.complete(chat, cut)

    /** 只回收已完成作业自己的源副本，SQLite 内部空闲不当作磁盘释放。 */
    suspend fun retire(cut: String) {
        SyncRawSnapshotFreeze.retire(chat, cut)
        context.deleteDatabase("snapshot-chat-${SyncOperationCanonicalizer.sha256Hex(cut)}.db")
    }

    /** 专用转换库保留 SQL 原始类型；缺失、损坏或复制失败直接暴露。 */
    fun open(cut: String, progress: me.ash.reader.infrastructure.sync.core.SyncSourceCopyProgress? = null): LlmChatDatabase {
        val frozen = Room.databaseBuilder(context, LlmChatDatabase::class.java,
            "snapshot-chat-${SyncOperationCanonicalizer.sha256Hex(cut)}.db").build()
        try {
            SyncRawSnapshotFreeze.copy(SyncRawSnapshotFreeze.Copy(chat, frozen, cut, progress, sourceName = "chat"))
            return frozen
        } catch (error: Exception) {
            // 半成品仅属于转换库；关闭连接后由同一个原始 cut 重建。
            frozen.close()
            throw error
        }
    }
}
