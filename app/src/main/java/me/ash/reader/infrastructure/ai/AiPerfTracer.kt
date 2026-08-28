package me.ash.reader.infrastructure.ai

import android.os.SystemClock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong
import me.ash.reader.BuildConfig
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Response
import timber.log.Timber

/**
 * P0 AI Transport 性能追踪。
 *
 * 只记录阶段、耗时、大小和 Provider 类型等非敏感元数据；禁止记录正文、Query、API Key、Authorization
 * 或完整响应内容。Debug 构建用于基准与回归，Release 构建完全静默。
 */
data class AiPerfTrace(
    val id: String,
    val kind: String,
    val startedAtMs: Long,
)

/** 挂到 OkHttp Request 上，使 EventListener 能把 DNS/TLS/Headers 事件归并到同一条业务 trace。 */
internal data class AiPerfRequestTag(
    val trace: AiPerfTrace,
)

internal object AiPerfTracer {
    private val serial = AtomicLong(0L)

    /** 创建一条新的业务 trace，并以单调时钟作为统一基准。 */
    fun start(kind: String): AiPerfTrace {
        val trace =
            AiPerfTrace(
                id = "$kind-${serial.incrementAndGet()}",
                kind = kind,
                startedAtMs = SystemClock.elapsedRealtime(),
            )
        mark(trace, "trace_start")
        return trace
    }

    /** 记录一个阶段；fields 只能传不含用户内容的数字/枚举/布尔值。 */
    fun mark(
        trace: AiPerfTrace,
        phase: String,
        vararg fields: Pair<String, Any?>,
    ) {
        if (!BuildConfig.DEBUG) return
        val elapsedMs = (SystemClock.elapsedRealtime() - trace.startedAtMs).coerceAtLeast(0L)
        val suffix =
            fields
                .filter { it.second != null }
                .joinToString(separator = " ", prefix = if (fields.isEmpty()) "" else " ") { (key, value) ->
                    "$key=$value"
                }
        Timber.tag(PERF_LOG_TAG).i(
            "trace=%s kind=%s phase=%s elapsedMs=%d%s",
            trace.id,
            trace.kind,
            phase,
            elapsedMs,
            suffix,
        )
    }
}

/**
 * 共享 OkHttp 的轻量事件监听器。
 *
 * 只有显式挂 [AiPerfRequestTag] 的 P0 请求才输出事件，因此不会把普通 RSS/图片等网络请求混入基线。
 */
internal class AiPerfEventListener : EventListener() {
    override fun callStart(call: Call) {
        mark(call, "http_call_start")
    }

    override fun dnsStart(call: Call, domainName: String) {
        mark(call, "dns_start")
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
    ) {
        mark(call, "connect_start")
    }

    override fun secureConnectStart(call: Call) {
        mark(call, "tls_start")
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        mark(call, "connection_acquired")
    }

    override fun responseHeadersStart(call: Call) {
        mark(call, "response_headers_start")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        mark(call, "response_headers_end", "httpCode" to response.code)
    }

    override fun responseBodyStart(call: Call) {
        mark(call, "response_body_start")
    }

    override fun callEnd(call: Call) {
        mark(call, "http_call_end")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        mark(call, "http_call_failed", "error" to ioe.javaClass.simpleName)
    }

    private fun mark(
        call: Call,
        phase: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val tag = call.request().tag(AiPerfRequestTag::class.java) ?: return
        AiPerfTracer.mark(
            tag.trace,
            phase,
            "host" to call.request().url.host,
            *fields,
        )
    }
}

internal const val PERF_LOG_TAG = "OrigReadPerf"
