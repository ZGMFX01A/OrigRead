package me.ash.reader.infrastructure.content

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 全文抓取失败原因。UI 只展示稳定原因，不直接暴露底层异常文本。 */
enum class FullContentFailureReason {
    NO_CONTENT,
    DYNAMIC_CONTENT,
    ACCESS_RESTRICTED,
    PAGE_UNAVAILABLE,
    INVALID_URL,
    NETWORK,
    UNKNOWN,
}

/** 带稳定失败原因的全文抓取异常。 */
class FullContentException(
    val reason: FullContentFailureReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 根据静态 HTML 特征区分动态页面、访问限制和普通正文缺失。 */
object FullContentFailureClassifier {
    fun classifyHtml(html: String): FullContentFailureReason {
        val normalized = html.lowercase()
        return when {
            ACCESS_MARKERS.any(normalized::contains) -> FullContentFailureReason.ACCESS_RESTRICTED
            DYNAMIC_MARKERS.any(normalized::contains) -> FullContentFailureReason.DYNAMIC_CONTENT
            else -> FullContentFailureReason.NO_CONTENT
        }
    }

    fun classifyHttpStatus(code: Int): FullContentFailureReason = when (code) {
        401, 403, 407, 429, 451 -> FullContentFailureReason.ACCESS_RESTRICTED
        in 400..599 -> FullContentFailureReason.PAGE_UNAVAILABLE
        else -> FullContentFailureReason.UNKNOWN
    }

    fun classifyThrowable(throwable: Throwable): FullContentFailureReason = when (throwable) {
        is FullContentException -> throwable.reason
        is SocketTimeoutException, is UnknownHostException, is IOException ->
            FullContentFailureReason.NETWORK
        else -> FullContentFailureReason.UNKNOWN
    }

    private val ACCESS_MARKERS = listOf(
        "access denied",
        "forbidden",
        "verify you are human",
        "captcha",
        "cloudflare ray id",
        "安全验证",
        "访问受限",
        "请先登录",
        "登录后查看",
    )

    private val DYNAMIC_MARKERS = listOf(
        "enable javascript",
        "please enable javascript",
        "javascript is required",
        "id=\"__next\"",
        "id='__next'",
        "id=\"app\"></div>",
        "id='app'></div>",
        "_guard/auto.js",
        "需要启用 javascript",
    )
}
