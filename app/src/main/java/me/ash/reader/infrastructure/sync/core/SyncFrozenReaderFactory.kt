package me.ash.reader.infrastructure.sync.core

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import me.ash.reader.infrastructure.db.AndroidDatabase

/** 转换副本使用当前同型 Room schema，不打开或迁移来源数据库。 */
class SyncFrozenReaderFactory @Inject constructor(@ApplicationContext private val context: Context) {
    fun open(cut: String): AndroidDatabase = Room.databaseBuilder(context, AndroidDatabase::class.java,
        "snapshot-reader-${SyncOperationCanonicalizer.sha256Hex(cut)}.db").build()

    /** 只删除已经关闭且完成转换的专用副本；Context 同时清理它的 WAL/SHM。 */
    fun retire(cut: String) { context.deleteDatabase("snapshot-reader-${SyncOperationCanonicalizer.sha256Hex(cut)}.db") }
}
