package me.ash.reader.infrastructure.sync.core

import me.ash.reader.infrastructure.db.AndroidDatabase

/** 维护属于 Space 内核，LAN 和 Server 共用每日一个机会。 */
internal object SyncMaintenanceSchedule {
    /** 比较/领取与 SQLite 原子提交，多个 Peer 不会并发重复执行全量维护。 */
    fun claim(database: AndroidDatabase, space: String, now: Long): Boolean {
        val sql = database.openHelper.writableDatabase
        val key = "sync-maintenance:$space"
        sql.beginTransaction()
        try {
            val last = sql.query("SELECT value FROM local_config_state WHERE `key`=?", arrayOf(key)).use {
                if (it.moveToFirst()) it.getString(0).toLong() else 0L
            }
            if (last != 0L && now - last < MAINTENANCE_INTERVAL_MS) return false
            sql.execSQL("INSERT OR REPLACE INTO local_config_state(`key`,value) VALUES(?,?)", arrayOf(key, now.toString()))
            sql.setTransactionSuccessful()
            return true
        } finally { sql.endTransaction() }
    }
    /** 低频任务不随着网络轮询反复生成大快照。 */
    private const val MAINTENANCE_INTERVAL_MS = 24L * 60L * 60L * 1000L
}
