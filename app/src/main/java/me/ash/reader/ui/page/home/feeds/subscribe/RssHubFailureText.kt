package me.ash.reader.ui.page.home.feeds.subscribe

import me.ash.reader.R
import me.ash.reader.infrastructure.rsshub.RssHubFailureReason
import me.ash.reader.infrastructure.rsshub.RssHubProbeResult
import me.ash.reader.infrastructure.website.CandidateState

/** 主提示与实例卡片共用同一错误分类，避免把服务端错误再次显示为网络断开。 */
internal data class RssHubFailureText(val resourceId: Int, val arguments: List<Any> = emptyList())

internal fun rssHubFailureText(result: RssHubProbeResult): RssHubFailureText {
    val reason = result.failureReason ?: when (result.state) {
        CandidateState.TIMEOUT -> RssHubFailureReason.TIMEOUT
        CandidateState.NETWORK_UNAVAILABLE -> RssHubFailureReason.NETWORK_UNAVAILABLE
        CandidateState.INVALID_CONTENT -> RssHubFailureReason.INVALID_CONTENT
        else -> null
    }
    val id = when (reason) {
        RssHubFailureReason.BLOCKED -> R.string.rsshub_explicit_blocked_notice
        RssHubFailureReason.HTTP_ERROR -> return when (result.statusCode) {
            401 -> RssHubFailureText(R.string.rsshub_http_auth_notice)
            403 -> RssHubFailureText(R.string.rsshub_http_denied_notice)
            404 -> RssHubFailureText(R.string.rsshub_http_not_found_notice)
            429 -> RssHubFailureText(R.string.rsshub_http_rate_limit_notice)
            null -> RssHubFailureText(R.string.rsshub_explicit_http_error_notice)
            else -> RssHubFailureText(R.string.rsshub_http_status_notice, listOf(result.statusCode))
        }
        RssHubFailureReason.TIMEOUT -> R.string.rsshub_request_timeout_notice
        RssHubFailureReason.NETWORK_UNAVAILABLE -> R.string.rsshub_explicit_network_notice
        RssHubFailureReason.CONNECTION_CLOSED -> R.string.rsshub_connection_closed_notice
        RssHubFailureReason.DNS_FAILURE -> R.string.rsshub_dns_notice
        RssHubFailureReason.TLS_ERROR -> R.string.rsshub_tls_notice
        RssHubFailureReason.HTML_RESPONSE -> R.string.rsshub_html_notice
        RssHubFailureReason.INVALID_CONTENT -> R.string.rsshub_explicit_invalid_notice
        RssHubFailureReason.DISABLED -> R.string.rsshub_disabled_notice
        RssHubFailureReason.NO_INSTANCES -> R.string.rsshub_no_instances_notice
        RssHubFailureReason.PROBE_BUDGET_EXHAUSTED -> R.string.rsshub_probe_incomplete_notice
        RssHubFailureReason.UNSUPPORTED_FORMAT -> R.string.rsshub_unsupported_format_notice
        RssHubFailureReason.AUTHENTICATION_REQUIRES_INSTANCE -> R.string.rsshub_auth_instance_notice
        RssHubFailureReason.BOUND_INSTANCE_DISABLED -> R.string.rsshub_bound_instance_disabled_notice
        null -> when (result.state) {
            CandidateState.NEEDS_INPUT -> return RssHubFailureText(
                R.string.rsshub_missing_parameters_notice,
                listOf(result.match.route.name, result.match.missingParameters.joinToString()),
            )
            CandidateState.UNSUPPORTED -> R.string.rsshub_disabled_notice
            else -> R.string.rsshub_explicit_unavailable_notice
        }
    }
    return RssHubFailureText(id)
}

internal fun rssHubFailureSummary(results: List<RssHubProbeResult>): RssHubFailureText? {
    val failures = results.filterNot { it.available }
    if (failures.isEmpty()) return null
    failures.firstOrNull { it.failureReason == RssHubFailureReason.PROBE_BUDGET_EXHAUSTED }
        ?.let { return rssHubFailureText(it) }
    val texts = failures.map(::rssHubFailureText).distinct()
    return if (texts.size > 1) RssHubFailureText(R.string.rsshub_mixed_failures_notice) else texts.single()
}
