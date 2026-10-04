package me.ash.reader.infrastructure.sync.core

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 协程取消绑定到真实 Call；完整正文消费期间仍可取消，返回前始终关闭响应。 */
internal suspend fun <T> OkHttpClient.consumeSyncResponse(request: Request, consume: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                // 已取消任务由协程报告 CancellationException；其他连接失败保留原始错误。
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) { response.close(); return }
                try {
                    val value = response.use(consume)
                    continuation.resume(value)
                } catch (error: Throwable) {
                    // 读正文、校验与解析失败原样传播；取消导致的读失败不覆盖协程取消原因。
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
