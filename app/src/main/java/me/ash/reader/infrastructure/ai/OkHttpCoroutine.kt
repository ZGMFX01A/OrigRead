package me.ash.reader.infrastructure.ai

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response

/**
 * 在 OkHttp IO worker 中读取并关闭完整响应，且让协程取消钩子覆盖响应头与响应体两个阶段。
 *
 * 不能先恢复 [Response] 再由调用方读取 body：恢复后取消钩子会丢失，慢速 `body.string()` 只能等超时。
 * 该封装不改变 Call 已配置的 connect/read/call timeout，并保证响应资源只在 [block] 期间有效。
 */
suspend fun <T> Call.awaitResponseAndUse(block: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    // Callback 本身运行在 OkHttp IO worker；保持 continuation 挂起直到 body 消费完成。
                    val result = runCatching { response.use(block) }
                    if (!continuation.isActive) return
                    result.fold(
                        onSuccess = { value -> continuation.resume(value) },
                        onFailure = { error -> continuation.resumeWithException(error) },
                    )
                }
            }
        )
    }
