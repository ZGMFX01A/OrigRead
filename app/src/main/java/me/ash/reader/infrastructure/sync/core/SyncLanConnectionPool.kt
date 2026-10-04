package me.ash.reader.infrastructure.sync.core

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 未配对 LAN 连接的资源准入；队列、连接和同源并发都有明确上限。 */
internal class SyncLanConnectionPool(private val handle: (Socket, Boolean) -> Unit) : AutoCloseable {
    private val acceptors = Executors.newFixedThreadPool(ACCEPTOR_THREADS)
    private val workers = ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUED_CONNECTIONS), ThreadPoolExecutor.AbortPolicy())
    private val sockets = mutableSetOf<Socket>()
    private val hosts = mutableMapOf<String, Int>()

    /** accept 与阻塞读使用不同线程池，慢请求不能耗尽接受循环的线程。 */
    fun listen(server: ServerSocket, tls: Boolean) {
        acceptors.execute {
            while (!server.isClosed) {
                try {
                    submit(server.accept(), tls)
                } catch (error: java.io.IOException) {
                    // stop 关闭监听 socket 是正常终止；其他接收失败需要暴露给日志。
                    if (!server.isClosed) Log.e("SyncLan", "LAN accept failed", error)
                }
            }
        }
    }

    /** 达到上限时关闭新连接，不把任务放入无界队列，也不分配请求正文。 */
    private fun submit(socket: Socket, tls: Boolean) {
        val host = socket.inetAddress.hostAddress.orEmpty()
        val admitted = synchronized(sockets) {
            if ((hosts[host] ?: 0) >= CONNECTIONS_PER_HOST) false else {
                hosts[host] = (hosts[host] ?: 0) + 1
                sockets.add(socket)
                true
            }
        }
        if (!admitted) { socket.close(); return }
        try {
            workers.execute {
                try { handle(socket, tls) } finally { release(socket, host) }
            }
        } catch (error: RejectedExecutionException) {
            // 线程池过载或正在停止，必须释放尚未交给 handler 的 socket。
            release(socket, host)
        }
    }

    /** 在同一个锁下释放同源计数，避免并发删除计数导致准入上限失效。 */
    private fun release(socket: Socket, host: String) {
        socket.close()
        synchronized(sockets) {
            sockets.remove(socket)
            val count = checkNotNull(hosts[host]) - 1
            if (count == 0) hosts.remove(host) else hosts[host] = count
        }
    }

    override fun close() {
        synchronized(sockets) { sockets.forEach { it.close() } }
        acceptors.shutdownNow()
        workers.shutdownNow()
    }

    private companion object {
        // 两个接受循环分别服务 bootstrap 与 TLS。
        const val ACCEPTOR_THREADS = 2
        // 限制未认证阻塞读取占用的线程和排队 socket。
        const val WORKER_THREADS = 8
        const val QUEUED_CONNECTIONS = 16
        // 同一来源不能占满所有业务处理线程。
        const val CONNECTIONS_PER_HOST = 4
    }
}
