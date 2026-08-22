package me.ash.reader.infrastructure.share

/** Prevents repeated taps from creating duplicate Notion pages. */
internal class NotionShareRequestGuard(
    private val debounceWindowMillis: Long = DEFAULT_DEBOUNCE_WINDOW_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private var inFlight = false
    private var lastReleasedAt = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nowMillis()
        if (inFlight || (lastReleasedAt != Long.MIN_VALUE && now - lastReleasedAt < debounceWindowMillis)) return false
        inFlight = true
        return true
    }

    @Synchronized
    fun release() {
        inFlight = false
        lastReleasedAt = nowMillis()
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_WINDOW_MILLIS = 1_500L
    }
}
