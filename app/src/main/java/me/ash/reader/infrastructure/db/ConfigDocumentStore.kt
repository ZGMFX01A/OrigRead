package me.ash.reader.infrastructure.db

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.infrastructure.util.AtomicUtf8File

/** 配置权威值与 Reader Outbox 使用同一事务；历史文件仅作一次导入输入。 */
interface ConfigDocumentStore {
    fun read(file: File): String?
    fun write(file: File, value: String)
}

@Singleton
class SqliteConfigDocumentStore @Inject constructor(database: AndroidDatabase) : ConfigDocumentStore {
    private val liveDatabase = database
    // 冻结转换只读取当前 cut 的副本，普通业务调用继续访问自身数据库。
    private val database get() = me.ash.reader.infrastructure.sync.core.SyncFrozenSourceContext.database(liveDatabase)

    /** 首次导入保留原值；已迁入的 key 从不再读取旧文件覆盖本地/远端因果状态。 */
    override fun read(file: File): String? {
        val sql = database.openHelper.writableDatabase
        val key = "config-document:${file.name}"
        sql.query("SELECT value FROM local_config_state WHERE `key`=?", arrayOf(key)).use {
            if (it.moveToFirst()) return it.getString(0).takeIf(String::isNotEmpty)
        }
        val legacy = AtomicUtf8File.readOrNull(file)
        // 缺文件同样记录缺席，防止后续旧文件恢复被误当新本地意图。
        write(file, legacy ?: "")
        return legacy
    }

    /** 使用 Room 当前线程上的事务连接，业务配置与 Outbox 成功或回滚保持一致。 */
    override fun write(file: File, value: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO local_config_state(`key`,value) VALUES(?,?)",
            arrayOf("config-document:${file.name}", value),
        )
    }
}

/** 显式独立文件模式供规则文件工具使用，生产 Hilt 构造使用 SQLite 存储。 */
object FileConfigDocumentStore : ConfigDocumentStore {
    override fun read(file: File): String? = AtomicUtf8File.readOrNull(file)
    override fun write(file: File, value: String) = AtomicUtf8File.write(file, value)
}
