package me.ash.reader.infrastructure.net

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class RetryConfig(
    val attempts: Int = 2,
    val timeoutPerAttempt: Long = 10000L,
    val initialDelay: Long = 1000L,
    val maxDelay: Long = 5000L,
    val delayFactor: Double = 2.0,
    val onRetry: ((Throwable) -> Unit)? = null,
    val shouldRetry: (Throwable) -> Boolean = { it !is CancellationException },
)

class TimeoutException(val timeout: Long) : Exception("Task attempt timed out after $timeout ms")

sealed class RetryableTaskResult<out R> {
    data class Success<out T>(val value: T) : RetryableTaskResult<T>()

    data class Failure(
        val finalException: Throwable,
        val attemptExceptions: List<Throwable> = emptyList(),
    ) : RetryableTaskResult<Nothing>()

    val isSuccess: Boolean
        get() = this is Success

    val isFailure: Boolean
        get() = this is Failure

    fun getOrThrow(): R {
        return when (this) {
            is Success -> value
            is Failure -> throw finalException
        }
    }

    fun getOrNull(): R? {
        return when (this) {
            is Success -> value
            is Failure -> null
        }
    }

    fun exceptionOrNull(): Throwable? =
        when (this) {
            is Failure -> finalException
            is Success<*> -> null
        }
}

@OptIn(ExperimentalContracts::class)
inline fun <R, T> RetryableTaskResult<T>.fold(
    onSuccess: (value: T) -> R,
    onFailure: (exception: Throwable) -> R,
): R {
    contract {
        callsInPlace(onSuccess, InvocationKind.AT_MOST_ONCE)
        callsInPlace(onFailure, InvocationKind.AT_MOST_ONCE)
    }
    return when (this) {
        is RetryableTaskResult.Failure -> onFailure(finalException)
        is RetryableTaskResult.Success<*> -> onSuccess(value as T)
    }
}

@OptIn(ExperimentalContracts::class)
inline fun <T> RetryableTaskResult<T>.mapFailure(
    onFailure: (exception: Throwable) -> Throwable
): RetryableTaskResult<T> {
    contract { callsInPlace(onFailure, InvocationKind.AT_MOST_ONCE) }
    return when (this) {
        is RetryableTaskResult.Failure -> RetryableTaskResult.Failure(onFailure(finalException))
        is RetryableTaskResult.Success<*> -> this as RetryableTaskResult<T>
    }
}

@OptIn(ExperimentalContracts::class)
inline fun <T> RetryableTaskResult<T>.onSuccess(block: (T) -> Unit): RetryableTaskResult<T> {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    if (this is RetryableTaskResult.Success) {
        block(value)
    }
    return this
}

@OptIn(ExperimentalContracts::class)
inline fun <T> RetryableTaskResult<T>.onFailure(
    block: (Throwable) -> Unit
): RetryableTaskResult<T> {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    if (this is RetryableTaskResult.Failure) {
        block(finalException)
    }
    return this
}

suspend fun <R> withRetries(
    config: RetryConfig = RetryConfig(),
    block: suspend () -> R,
): RetryableTaskResult<R> {
    require(config.attempts > 0) { "重试次数必须大于零" }
    val attemptExceptions = mutableListOf<Throwable>()
    with(config) {
        var currentDelay = initialDelay

        for (attempt in 1..attempts) {
            var blockFailure: Throwable? = null
            try {
                val result = try {
                    withTimeout(timeoutPerAttempt) {
                        try {
                            block()
                        } catch (error: Throwable) {
                            blockFailure = error
                            throw error
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    // 外层超时属于调用方取消，不能转换成一次可重试失败。
                    currentCoroutineContext().ensureActive()
                    throw TimeoutException(timeoutPerAttempt)
                }
                return RetryableTaskResult.Success(result)
            } catch (caught: Throwable) {
                currentCoroutineContext().ensureActive()
                // 单次超时使用业务超时；任务主动抛出的其余异常保持原实例。
                val th = if (caught is TimeoutException) caught else blockFailure ?: caught
                if (th is CancellationException) throw th
                attemptExceptions.add(th)

                if (attempt == attempts || !shouldRetry(th)) {
                    return RetryableTaskResult.Failure(
                        finalException = th,
                        attemptExceptions = attemptExceptions,
                    )
                }

                onRetry?.invoke(th)
                delay(currentDelay)
                currentDelay = (currentDelay * delayFactor).toLong().coerceAtMost(maxDelay)
            }
        }
        return RetryableTaskResult.Failure(
            finalException = attemptExceptions.last(),
            attemptExceptions = attemptExceptions,
        )
    }
}

suspend fun <R> withRetriesOrNull(config: RetryConfig, block: suspend () -> R?): R? =
    withRetries(config, block).getOrNull()
