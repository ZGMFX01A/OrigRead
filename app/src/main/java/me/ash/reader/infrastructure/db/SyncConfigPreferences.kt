package me.ash.reader.infrastructure.db

import android.content.SharedPreferences
import org.json.JSONObject

/** CONFIG 的 SQLite 真源；仅非同步网络历史继续留在原 SharedPreferences。 */
class SyncConfigPreferences(private val options: Options) : SharedPreferences by options.legacy {
    data class Options(val database: AndroidDatabase, val legacy: SharedPreferences, val namespace: String,
        val syncKey: (String) -> Boolean)
    private val sql get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(options.database).openHelper.writableDatabase
    private val prefix = "sync-preferences:${options.namespace}:"

    init {
        // 只在首次迁移导入旧值；已迁移后不再从旧文件恢复被删除的配置。
        transaction {
            val marker = prefix + "@migrated"
            if (read(marker) == null) {
                options.legacy.all.filterKeys(options.syncKey).forEach { (key, value) -> write(prefix + key, encode(value)) }
                write(marker, "true")
            }
        }
    }

    override fun getString(key: String?, defValue: String?): String? = if (key != null && options.syncKey(key))
        read(prefix + key)?.let { JSONObject(it).getString("value") } ?: defValue else options.legacy.getString(key, defValue)

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = if (key != null && options.syncKey(key))
        read(prefix + key)?.let { JSONObject(it).getBoolean("value") } ?: defValue else options.legacy.getBoolean(key, defValue)

    override fun contains(key: String?): Boolean = if (key != null && options.syncKey(key)) read(prefix + key) != null else options.legacy.contains(key)

    /** 遍历仅返回配置值，不向 RSSHub 的历史过滤器暴露迁移 marker。 */
    override fun getAll(): MutableMap<String, *> = buildMap<String, Any?> {
        putAll(options.legacy.all.filterKeys { !options.syncKey(it) })
        sql.query("SELECT `key`,value FROM local_config_state WHERE `key` LIKE ?", arrayOf(prefix + "%")).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0).removePrefix(prefix)
                if (key != "@migrated") put(key, JSONObject(cursor.getString(1)).get("value"))
            }
        }
    }.toMutableMap()

    override fun edit(): SharedPreferences.Editor = Editor()

    /** Editor 先收集变化，commit 与当前 Room Outbox 事务共享同一数据库连接。 */
    private inner class Editor : SharedPreferences.Editor {
        private val legacy = options.legacy.edit()
        private val changes = linkedMapOf<String, String?>()
        private var clear = false
        override fun putString(key: String?, value: String?) = update(key, value) { legacy.putString(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = update(key, value) { legacy.putBoolean(key, value) }
        override fun remove(key: String?) = update(key, null) { legacy.remove(key) }
        override fun putInt(key: String?, value: Int) = update(key, value) { legacy.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = update(key, value) { legacy.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = update(key, value) { legacy.putFloat(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = update(key, values) { legacy.putStringSet(key, values) }
        override fun clear(): SharedPreferences.Editor = apply { clear = true; legacy.clear() }
        override fun apply() { commit() }

        /** 同步键不会写回外部文件，进程死亡时业务值与 Outbox 一起提交或一起回滚。 */
        override fun commit(): Boolean {
            transaction {
                if (clear) sql.execSQL("DELETE FROM local_config_state WHERE `key` LIKE ? AND `key`<>?", arrayOf(prefix + "%", prefix + "@migrated"))
                changes.forEach { (key, value) ->
                    if (value == null) sql.execSQL("DELETE FROM local_config_state WHERE `key`=?", arrayOf(prefix + key))
                    else write(prefix + key, value)
                }
                check(legacy.commit()) { "Failed to persist device-local RSSHub history" }
            }
            return true
        }

        /** 配置白名单在调用边界固定，其他键明确走原设备本地存储。 */
        private fun update(key: String?, value: Any?, other: () -> Unit): SharedPreferences.Editor = apply {
            requireNotNull(key) { "Preference key is required" }
            if (options.syncKey(key)) changes[key] = value?.let(::encode) else other()
        }
    }

    /** 嵌入已存在的 Room 事务；独立配置写入则创建自己的原子提交。 */
    private fun transaction(action: () -> Unit) {
        if (sql.inTransaction()) { action(); return }
        sql.beginTransaction()
        try { action(); sql.setTransactionSuccessful() } finally { sql.endTransaction() }
    }

    private fun read(key: String): String? = sql.query("SELECT value FROM local_config_state WHERE `key`=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    private fun write(key: String, value: String) { sql.execSQL("INSERT OR REPLACE INTO local_config_state(`key`,value) VALUES(?,?)", arrayOf(key, value)) }
    private fun encode(value: Any?): String = JSONObject().put("value", value).toString()
}
