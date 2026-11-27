package me.ash.reader.infrastructure.coroutines

import kotlinx.coroutines.CancellationException

/** 业务失败保留为 Result，取消必须交还调用协程处理。 */
suspend inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
