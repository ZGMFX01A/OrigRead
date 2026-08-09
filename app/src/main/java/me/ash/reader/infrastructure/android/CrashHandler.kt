package me.ash.reader.infrastructure.android

import android.content.Context
import android.util.Log
import java.lang.Thread.UncaughtExceptionHandler

/**
 * The uncaught exception handler for the application.
 */
class CrashHandler(private val context: Context) : UncaughtExceptionHandler {

    /** 保留系统原始处理器，记录日志后必须交还系统完成进程退出。 */
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /**
     * Catch all uncaught exception and log it.
     */
    override fun uncaughtException(p0: Thread, p1: Throwable) {
        val causeMessage = getCauseMessage(p1)
        Log.e("RLog", "uncaughtException: $causeMessage", p1)

        // 旧实现从已崩溃的进程中启动 CrashReportActivity，并吞掉系统处理器，
        // 会产生独立白屏任务且让虚拟机等待非守护线程，最终演变为 ANR。
        previousHandler?.uncaughtException(p0, p1)
            ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
    }

    private fun getCauseMessage(e: Throwable?): String? {
        val cause = getCauseRecursively(e)
        return if (cause != null) cause.message.toString() else e?.javaClass?.name
    }

    private fun getCauseRecursively(e: Throwable?): Throwable? {
        var cause: Throwable?
        cause = e
        while (cause?.cause != null && cause !is RuntimeException) {
            cause = cause.cause
        }
        return cause
    }
}
