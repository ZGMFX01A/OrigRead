package me.ash.reader.infrastructure.sync.core

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/** 持久围栏使观察查询暂时不可用，异常携带独立控制库检查，不占业务连接等待。 */
class SyncSnapshotInstallingException(private val pending: () -> Boolean) :
    IllegalStateException("SYNC_INSTALLING_RETRYABLE: account Snapshot is not yet complete") {
    /** 原 Room 查询和事务已经退出后，观察流等待真实围栏解除再重新查询。 */
    suspend fun awaitVisible() {
        while (pending()) delay(VISIBILITY_POLL_MS)
    }

    companion object {
        /** 控制查询只检查小型持久围栏，避免观察流忙循环。 */
        private const val VISIBILITY_POLL_MS = 250L
    }
}

/** DAO 只对安装围栏异常恢复观察；其他 SQLite、转换和取消异常保持原样传播。 */
class SyncSnapshotVisibilityDao {
    private val proxies = IdentityHashMap<Any, Any>()

    /** 保留所有原方法与参数，只有 Flow 的重新订阅遵守快照可见性协议。 */
    fun <T : Any> wrap(dao: T, type: Class<T>): T = synchronized(proxies) {
        type.cast(proxies.getOrPut(dao) {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
                val result = try { method.invoke(dao, *(arguments ?: emptyArray())) }
                catch (error: InvocationTargetException) {
                    // 反射不应改变 Room、取消或业务校验的异常类型。
                    throw checkNotNull(error.cause)
                }
                if (result is Flow<*>) observe(result) else result
            }
        })
    }

    /** retryWhen 在上游退出之后运行，等待期间没有 Reader/Chat 事务和 Cursor。 */
    private fun observe(source: Flow<*>): Flow<*> = source.retryWhen { error, _ ->
        if (error !is SyncSnapshotInstallingException) return@retryWhen false
        error.awaitVisible()
        true
    }
}
