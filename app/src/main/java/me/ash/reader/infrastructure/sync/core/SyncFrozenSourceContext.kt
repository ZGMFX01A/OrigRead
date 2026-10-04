package me.ash.reader.infrastructure.sync.core

import androidx.room.RoomDatabase
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** 转换阶段明确绑定冻结数据库；协程切线程也不得重新读取活库。 */
object SyncFrozenSourceContext {
    private val sources = ThreadLocal<Map<String, RoomDatabase>?>()

    /** 普通产品调用读自己的数据库，冻结转换必须提供对应的同型数据库。 */
    fun <T : RoomDatabase> database(live: T, name: String = "reader"): T {
        val selected = sources.get() ?: return live
        val frozen = checkNotNull(selected[name]) { "Snapshot frozen database is missing: $name" }
        check(frozen.javaClass == live.javaClass) { "Snapshot frozen database type differs: $name" }
        @Suppress("UNCHECKED_CAST")
        return frozen as T
    }

    /** 输入字典不可变，整个转换只访问同一个 cut 的 Reader/Chat 副本。 */
    suspend fun <T> run(databases: Map<String, RoomDatabase>, action: suspend () -> T): T =
        withContext(sources.asContextElement(databases.toMap())) { action() }
}
