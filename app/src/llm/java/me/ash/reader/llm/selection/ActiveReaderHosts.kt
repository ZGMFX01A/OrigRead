package me.ash.reader.llm.selection

/** Serializes boundary notifications with ownership changes, including concurrent disposal. */
internal class ActiveReaderHosts(private val onBoundary: (Boolean) -> Unit) {
    private var count = 0

    @Synchronized
    fun acquire() {
        if (count++ == 0) onBoundary(true)
    }

    @Synchronized
    fun release() {
        check(count > 0) { "Reader host released without ownership" }
        if (--count == 0) onBoundary(false)
    }
}
