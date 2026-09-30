package me.ash.reader.ui.page.home.feeds.subscribe

import me.ash.reader.R
import me.ash.reader.infrastructure.rsshub.*
import me.ash.reader.infrastructure.website.CandidateState
import org.junit.Assert.assertEquals
import org.junit.Test

class RssHubFailureTextTest {
    @Test
    fun `server errors preserve status instead of being displayed as network failures`() {
        assertEquals(R.string.rsshub_http_denied_notice,
            rssHubFailureText(failure(RssHubFailureReason.HTTP_ERROR, 403)).resourceId)
        assertEquals(R.string.rsshub_http_rate_limit_notice,
            rssHubFailureText(failure(RssHubFailureReason.HTTP_ERROR, 429)).resourceId)
        assertEquals(RssHubFailureText(R.string.rsshub_http_status_notice, listOf(503)),
            rssHubFailureText(failure(RssHubFailureReason.HTTP_ERROR, 503)))
        assertEquals(R.string.rsshub_explicit_blocked_notice,
            rssHubFailureText(failure(RssHubFailureReason.BLOCKED, 403)).resourceId)
    }

    @Test
    fun `summary reports mixed causes and never claims all servers failed after budget expiration`() {
        val failures = listOf(failure(RssHubFailureReason.DNS_FAILURE), failure(RssHubFailureReason.HTML_RESPONSE))
        assertEquals(R.string.rsshub_mixed_failures_notice, rssHubFailureSummary(failures)?.resourceId)
        assertEquals(R.string.rsshub_probe_incomplete_notice,
            rssHubFailureSummary(failures + failure(RssHubFailureReason.PROBE_BUDGET_EXHAUSTED))?.resourceId)
        assertEquals(R.string.rsshub_no_instances_notice,
            rssHubFailureSummary(listOf(failure(RssHubFailureReason.NO_INSTANCES)))?.resourceId)
    }

    private fun failure(reason: RssHubFailureReason, status: Int? = null) = RssHubProbeResult(
        match = RssHubRouteMatch(RssHubRouteDefinition("test", "RSSHub", "rsshub", "/zhihu", "/zhihu/hot")),
        state = CandidateState.NETWORK_UNAVAILABLE,
        failureReason = reason,
        statusCode = status,
    )

    @Test
    fun `unsupported outputs and instance credential errors have actionable notices`() {
        assertEquals(R.string.rsshub_unsupported_format_notice,
            rssHubFailureText(failure(RssHubFailureReason.UNSUPPORTED_FORMAT)).resourceId)
        assertEquals(R.string.rsshub_auth_instance_notice,
            rssHubFailureText(failure(RssHubFailureReason.AUTHENTICATION_REQUIRES_INSTANCE)).resourceId)
        assertEquals(R.string.rsshub_bound_instance_disabled_notice,
            rssHubFailureText(failure(RssHubFailureReason.BOUND_INSTANCE_DISABLED)).resourceId)
    }
}
