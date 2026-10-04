package me.ash.reader.infrastructure.sync.core

import android.content.Context

/** 当前真实执行器的上下文与持久 fence 一起控制安装可见性，不靠页面完成位判断。 */
internal class SyncSnapshotAccess(context: Context) {
    private val control = SyncSnapshotJobsDatabase(context).writableDatabase

    /** 有账户过滤的查询只检查该账户；不带账户条件的全局查询必须排除全部未完成空间。 */
    fun requireAllowed(sql: String, arguments: List<Any?>) {
        if (!BUSINESS_TABLE.containsMatchIn(sql)) return
        // 本机政策仍允许暂停/撤销；它不属于可见业务图，原子修订会使最终证明失效。
        if (isPolicyAccess(sql, arguments)) return
        val match = ACCOUNT_FILTER.find(sql)
        val account = match?.let { arguments.getOrNull(sql.substring(0, it.range.first).count { char -> char == '?' }) }
        val condition = if (account == null) "" else "WHERE account=?"
        val bindings = if (account == null) emptyArray() else arrayOf(account.toString())
        val owner = ownedSpace.get()
        val pending = {
            control.rawQuery("SELECT space FROM snapshot_install_fence $condition", bindings).use { cursor ->
                var blocked = false
                while (cursor.moveToNext()) if (cursor.getString(0) != owner) blocked = true
                blocked
            }
        }
        if (pending()) throw SyncSnapshotInstallingException(pending)
    }

    /** 仅按真实 key 绑定识别政策访问，正文/配置 value 恰好含前缀不能获得写入许可。 */
    private fun isPolicyAccess(sql: String, arguments: List<Any?>): Boolean {
        if (!sql.contains("local_config_state", ignoreCase = true)) return false
        val filter = KEY_FILTER.find(sql)
        val index = filter?.let { sql.substring(0, it.range.first).count { char -> char == '?' } }
            ?: if (KEY_FIRST_INSERT.containsMatchIn(sql)) 0 else return false
        return arguments.getOrNull(index)?.toString()?.startsWith("sync.lane-policy:") == true
    }

    companion object {
        /** Coroutine context 将真实 owner 传播到 Room 的执行线程，不使用全局允许写入开关。 */
        val ownedSpace = ThreadLocal<String?>()
        /** Reader 与 Chat 的正式业务表，辅助回执及授权表不属于可见业务图。 */
        private val BUSINESS_TABLE = Regex("(?i)\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+[`\"\\[]?(?:article|feed|group|llm_[A-Za-z0-9_]+|local_config_state)\\b")
        /** 本机 SQL 的直接账户条件用于限定围栏范围，动态值仍由原语句绑定。 */
        private val ACCOUNT_FILTER = Regex("(?i)\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?account_?id\\s*=\\s*\\?")
        /** Room 的 key 查询和更新均通过占位符绑定实际政策键。 */
        private val KEY_FILTER = Regex("(?i)[`\"]?key[`\"]?\\s*=\\s*\\?")
        /** Room upsert 的列清单明确 key 为首列才读取首个绑定。 */
        private val KEY_FIRST_INSERT = Regex("(?i)\\bINTO\\s+[`\"]?local_config_state[`\"]?\\s*\\(\\s*[`\"]?key[`\"]?\\s*,")
    }
}
