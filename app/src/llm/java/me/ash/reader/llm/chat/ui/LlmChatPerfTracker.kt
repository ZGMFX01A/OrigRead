package me.ash.reader.llm.chat.ui

import java.util.LinkedHashMap
import me.ash.reader.BuildConfig
import me.ash.reader.infrastructure.ai.AiPerfTrace
import me.ash.reader.infrastructure.ai.AiPerfTracer

/**
 * R7 Chat 本地热路径性能追踪。
 *
 * 只在 Debug 构建保存有界计数，并且只记录耗时、字符数量、枚举状态等非敏感元数据；
 * 不记录用户问题、文章正文、模型输出、搜索 Query、API Key 或 Tool 参数。
 */
internal object LlmChatPerfTracker {
    private data class State(
        val trace: AiPerfTrace,
        var deltaCount: Int = 0,
        var contentDeltaChars: Int = 0,
        var reasoningDeltaChars: Int = 0,
        var toolCallDeltaCount: Int = 0,
        var roomPersistCount: Int = 0,
        var streamingUiPublishCount: Int = 0,
        var markdownParseCount: Int = 0,
        var markdownParseTotalMs: Long = 0L,
        var autoFollowScrollCount: Int = 0,
        var firstVisibleMarked: Boolean = false,
        var firstMarkdownParseMarked: Boolean = false,
    )

    private val states = LinkedHashMap<String, State>()

    @Synchronized
    fun start(
        assistantMessageId: String,
        toolRound: Int,
    ): AiPerfTrace? {
        if (!BuildConfig.DEBUG) return null
        val trace = AiPerfTracer.start("llm-chat")
        states[assistantMessageId] = State(trace = trace)
        trimToBound()
        AiPerfTracer.mark(trace, "request_pipeline_start", "toolRound" to toolRound)
        return trace
    }

    @Synchronized
    fun mark(
        assistantMessageId: String,
        phase: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val state = states[assistantMessageId] ?: return
        AiPerfTracer.mark(state.trace, phase, *fields)
    }

    @Synchronized
    fun recordTransportDelta(
        assistantMessageId: String,
        contentChars: Int,
        reasoningChars: Int,
        toolCallCount: Int,
    ) {
        val state = states[assistantMessageId] ?: return
        state.deltaCount += 1
        state.contentDeltaChars += contentChars.coerceAtLeast(0)
        state.reasoningDeltaChars += reasoningChars.coerceAtLeast(0)
        state.toolCallDeltaCount += toolCallCount.coerceAtLeast(0)
        if (state.deltaCount == 1) {
            AiPerfTracer.mark(
                state.trace,
                "first_transport_delta_collected",
                "contentChars" to contentChars.coerceAtLeast(0),
                "reasoningChars" to reasoningChars.coerceAtLeast(0),
                "toolCalls" to toolCallCount.coerceAtLeast(0),
            )
        }
    }

    @Synchronized
    fun recordRoomPersist(
        assistantMessageId: String,
        contentChars: Int,
        reasoningChars: Int,
    ) {
        val state = states[assistantMessageId] ?: return
        state.roomPersistCount += 1
        if (state.roomPersistCount == 1) {
            AiPerfTracer.mark(
                state.trace,
                "first_stream_room_persist",
                "contentChars" to contentChars.coerceAtLeast(0),
                "reasoningChars" to reasoningChars.coerceAtLeast(0),
            )
        }
    }

    @Synchronized
    fun recordStreamingUiPublish(
        assistantMessageId: String,
        contentChars: Int,
        reasoningChars: Int,
    ) {
        val state = states[assistantMessageId] ?: return
        state.streamingUiPublishCount += 1
        if (state.streamingUiPublishCount == 1) {
            AiPerfTracer.mark(
                state.trace,
                "first_streaming_ui_publish",
                "contentChars" to contentChars.coerceAtLeast(0),
                "reasoningChars" to reasoningChars.coerceAtLeast(0),
            )
        }
    }

    @Synchronized
    fun recordFirstVisible(
        assistantMessageId: String,
        contentChars: Int,
        reasoningChars: Int,
    ) {
        val state = states[assistantMessageId] ?: return
        if (state.firstVisibleMarked) return
        state.firstVisibleMarked = true
        AiPerfTracer.mark(
            state.trace,
            "first_model_text_visible",
            "contentChars" to contentChars.coerceAtLeast(0),
            "reasoningChars" to reasoningChars.coerceAtLeast(0),
        )
    }

    @Synchronized
    fun recordMarkdownParse(
        assistantMessageId: String,
        markdownChars: Int,
        durationNanos: Long,
    ) {
        val state = states[assistantMessageId] ?: return
        val durationMs = (durationNanos.coerceAtLeast(0L) / 1_000_000L)
        state.markdownParseCount += 1
        state.markdownParseTotalMs += durationMs
        if (!state.firstMarkdownParseMarked) {
            state.firstMarkdownParseMarked = true
            AiPerfTracer.mark(
                state.trace,
                "first_markdown_parse",
                "markdownChars" to markdownChars.coerceAtLeast(0),
                "durationMs" to durationMs,
            )
        } else if (state.markdownParseCount % MARKDOWN_SAMPLE_INTERVAL == 0) {
            AiPerfTracer.mark(
                state.trace,
                "markdown_parse_sample",
                "parseCount" to state.markdownParseCount,
                "markdownChars" to markdownChars.coerceAtLeast(0),
                "durationMs" to durationMs,
            )
        }
    }

    @Synchronized
    fun recordAutoFollowScroll(assistantMessageId: String) {
        val state = states[assistantMessageId] ?: return
        state.autoFollowScrollCount += 1
        if (state.autoFollowScrollCount == 1) {
            AiPerfTracer.mark(state.trace, "first_auto_follow_scroll")
        }
    }

    @Synchronized
    fun finish(
        assistantMessageId: String,
        status: String,
        contentChars: Int,
        reasoningChars: Int,
    ) {
        val state = states[assistantMessageId] ?: return
        AiPerfTracer.mark(
            state.trace,
            "request_pipeline_complete",
            "status" to status,
            "contentChars" to contentChars.coerceAtLeast(0),
            "reasoningChars" to reasoningChars.coerceAtLeast(0),
            "deltaCount" to state.deltaCount,
            "contentDeltaChars" to state.contentDeltaChars,
            "reasoningDeltaChars" to state.reasoningDeltaChars,
            "toolCallDeltaCount" to state.toolCallDeltaCount,
            "roomPersistCount" to state.roomPersistCount,
            "streamingUiPublishCount" to state.streamingUiPublishCount,
            "markdownParseCount" to state.markdownParseCount,
            "markdownParseTotalMs" to state.markdownParseTotalMs,
            "autoFollowScrollCount" to state.autoFollowScrollCount,
        )
        // 完成汇总已经冻结本次请求的指标；继续保留会让之后的历史重排/滚动错误归因到已完成请求。
        // 设备基线曾观察到一条请求 COMPLETE 约 90 秒后又被记为 first_auto_follow_scroll，
        // 因此这里立即结束 trace 生命周期。最终 Room/Compose 收口仍由产品状态自己完成，不再污染性能样本。
        states.remove(assistantMessageId)
    }

    private fun trimToBound() {
        while (states.size > MAX_TRACKED_REQUESTS) {
            val oldest = states.entries.firstOrNull()?.key ?: return
            states.remove(oldest)
        }
    }

    private const val MAX_TRACKED_REQUESTS = 32
    private const val MARKDOWN_SAMPLE_INTERVAL = 10
}
