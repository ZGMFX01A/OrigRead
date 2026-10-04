package me.ash.reader.infrastructure.sync.core

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** 正式 Room 连接统一拦截查询和已缓存语句执行，所有业务 DAO 都必须遵守持久安装围栏。 */
class SyncSnapshotGuardFactory(context: Context) : SupportSQLiteOpenHelper.Factory {
    private data class Execution(val database: SupportSQLiteDatabase, val sql: String, val arguments: List<Any?>)
    private val access = SyncSnapshotAccess(context)
    private val delegate = FrameworkSQLiteOpenHelperFactory()
    private val connections = WeakHashMap<SupportSQLiteDatabase, SupportSQLiteDatabase>()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            override val writableDatabase: SupportSQLiteDatabase get() = guarded(helper.writableDatabase)
            override val readableDatabase: SupportSQLiteDatabase get() = guarded(helper.readableDatabase)
        }
    }

    /** 语句编译可以缓存，执行时仍重新读取当前持久 fence，避免缓存绕过后来开始的安装。 */
    private fun guarded(database: SupportSQLiteDatabase): SupportSQLiteDatabase = synchronized(connections) {
        connections.getOrPut(database) { databaseProxy(database) }
    }

    /** 每个实际连接复用代理身份，schema 缓存和 Room 的连接判断保持稳定。 */
    private fun databaseProxy(database: SupportSQLiteDatabase): SupportSQLiteDatabase = proxy(SupportSQLiteDatabase::class.java) { method, args ->
        val sql = when (val first = args.firstOrNull()) {
            is SupportSQLiteQuery -> first.sql
            is String -> if (method.name in SQL_METHODS) first else null
            else -> null
        }
        if (method.name == "compileStatement") return@proxy statement(database, database.compileStatement(checkNotNull(sql)), sql)
        val statementSql = if (method.name in TABLE_METHODS) "UPDATE ${args.first()}" else sql
        if (statementSql == null) return@proxy invoke(database, method, args)
        val arguments = (args.firstOrNull() as? SupportSQLiteQuery)?.let(::queryBindings)
            ?: (args.getOrNull(1) as? Array<*>)?.toList().orEmpty()
        execute(Execution(database, statementSql, arguments)) { invoke(database, method, args) }
    }

    /** 保存当前语句绑定，访问检查不改变 SQL、绑定值或执行结果。 */
    private fun statement(database: SupportSQLiteDatabase, statement: SupportSQLiteStatement, sql: String): SupportSQLiteStatement {
        val bindings = sortedMapOf<Int, Any?>()
        return proxy(SupportSQLiteStatement::class.java) { method, args ->
            if (method.name.startsWith("bind")) bindings[args[0] as Int] = args.getOrNull(1)
            if (method.name == "clearBindings") bindings.clear()
            if (method.name !in EXECUTION_METHODS) return@proxy invoke(statement, method, args)
            val values = (1..(bindings.keys.maxOrNull() ?: 0)).map { bindings[it] }
            execute(Execution(database, sql, values)) { invoke(statement, method, args) }
        }
    }

    /** 写入先取得本库写锁再检查 fence；围栏建立后的排队写入不能使用检查前的旧许可。 */
    private fun execute(input: Execution, action: () -> Any?): Any? {
        val database = input.database
        SyncSnapshotTrace.sql(input.sql)
        val ownTransaction = WRITE_SQL.containsMatchIn(input.sql) && !database.inTransaction()
        val waiting = System.nanoTime()
        if (ownTransaction) database.beginTransaction()
        val started = System.nanoTime()
        if (ownTransaction) SyncSnapshotTrace.lockWait(started - waiting)
        try {
            access.requireAllowed(input.sql, input.arguments)
            return action().also { if (ownTransaction) database.setTransactionSuccessful() }
        } finally {
            // 原有 Room 事务仍由 Room 结束；这里只负责原本 autocommit 的单语句写入。
            if (ownTransaction) {
                database.endTransaction()
                SyncSnapshotTrace.transactionHold(System.nanoTime() - started)
            }
        }
    }

    /** Room 查询对象的真实绑定决定账户范围，不能把带账户的查询退化成全局围栏。 */
    private fun queryBindings(query: SupportSQLiteQuery): List<Any?> {
        val values = arrayOfNulls<Any?>(query.argCount)
        val capture = object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { values[index - 1] = null }
            override fun bindLong(index: Int, value: Long) { values[index - 1] = value }
            override fun bindDouble(index: Int, value: Double) { values[index - 1] = value }
            override fun bindString(index: Int, value: String) { values[index - 1] = value }
            override fun bindBlob(index: Int, value: ByteArray) { values[index - 1] = value }
            override fun clearBindings() { values.fill(null) }
            override fun close() = Unit
        }
        query.bindTo(capture)
        return values.toList()
    }

    /** 反射只用于透明接口委托，数据库原异常必须原样传播给 Room 和作业控制面。 */
    private fun invoke(target: Any, method: Method, arguments: Array<out Any?>): Any? = try {
        method.invoke(target, *arguments)
    } catch (error: InvocationTargetException) {
        // 保留 SQLite/取消的原始错误类型，不能把持久围栏拒绝包装成成功。
        throw checkNotNull(error.cause)
    }

    /** 动态代理覆盖完整 SupportSQLite 接口，升级接口不会产生未实现的降级调用。 */
    private fun <T> proxy(type: Class<T>, invoke: (Method, Array<out Any?>) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> invoke(method, args ?: emptyArray()) })

    companion object {
        /** 单语句业务变更必须在写锁内检查围栏，读查询保持原有 cursor 生命周期。 */
        private val WRITE_SQL = Regex("(?i)^\\s*(?:INSERT|UPDATE|DELETE|REPLACE)\\b")
        /** 原生 SQL 入口，具体业务范围由语句中的本机表名识别。 */
        private val SQL_METHODS = setOf("query", "execSQL", "compileStatement")
        /** ContentValues 入口同样属于业务写入，不能绕过 prepared statement 围栏。 */
        private val TABLE_METHODS = setOf("insert", "update", "delete")
        /** 缓存语句每次实际执行都检查当前围栏。 */
        private val EXECUTION_METHODS = setOf("execute", "executeInsert", "executeUpdateDelete", "simpleQueryForLong", "simpleQueryForString")
    }
}
