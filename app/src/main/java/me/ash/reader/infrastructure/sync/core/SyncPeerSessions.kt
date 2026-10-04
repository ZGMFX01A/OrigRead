package me.ash.reader.infrastructure.sync.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同一 Peer 串行协商，不同 Peer 的网络等待独立；业务写入仍走现有数据库事务/投影锁。 */
internal class SyncPeerSessions {
    private data class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    /** 计数包含等待者，最后一个使用者离开才回收 Peer 锁。 */
    suspend fun <T> run(key: String, action: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(entries) {
                entry.users--
                if (entry.users == 0) entries.remove(key)
            }
        }
    }
}
