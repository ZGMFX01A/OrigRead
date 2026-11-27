package me.ash.reader.ui.page.home.reading

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ash.reader.infrastructure.content.FullContentFailureReason
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.ui.component.reader.PendingCitationNavigation
import me.ash.reader.ui.component.reader.ReaderEvidenceAnchorTarget
import me.ash.reader.ui.page.adaptive.ReaderState
import me.ash.reader.ui.page.adaptive.ReaderTranslationUiState
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CitationNavigationAwaitTest {
    private val pending = PendingCitationNavigation(
        conversationId = "conversation", assistantMessageId = "message", citationId = "citation",
        articleId = "target", target = ReaderEvidenceAnchorTarget(articleId = "target", stableLocatorKey = "key"),
        requestedAt = 0L, originArticleId = "origin",
    )
    private val native: ReadingRendererPreference = ReadingRendererPreference.NativeComponent
    private val web: ReadingRendererPreference = ReadingRendererPreference.WebView

    @Test fun `cross article loading waits for target content`() = runTest {
        val reader = MutableStateFlow(ReaderState(articleId = "origin"))
        val translation = MutableStateFlow(ReaderTranslationUiState())
        val ready = MutableStateFlow(Triple(native, "target", null as String?))
        val loads = mutableListOf<String>()
        val result = async { awaitCitationNavigationTarget(pending, reader, translation, ready, loads::add, {}) }
        runCurrent()
        assertEquals(listOf("target"), loads)
        reader.value = ReaderState(articleId = "target", content = ReaderState.Loading)
        runCurrent()
        assertFalse(result.isCompleted)
        reader.value = reader.value.copy(content = ReaderState.Description("Ready"))
        runCurrent()
        assertEquals(CitationNavigationTargetResolution.Ready(native), result.await())
    }
    @Test fun `translation switches to original before readiness`() = runTest {
        val reader = MutableStateFlow(ReaderState(articleId = "target", content = ReaderState.Description("Ready")))
        val translation = MutableStateFlow(ReaderTranslationUiState(showTranslation = true))
        val ready = MutableStateFlow(Triple(native, "target", null as String?))
        var switches = 0
        val result = async { awaitCitationNavigationTarget(pending, reader, translation, ready, {}, { switches++ }) }
        runCurrent()
        assertEquals(1, switches)
        assertFalse(result.isCompleted)
        translation.value = translation.value.copy(showTranslation = false)
        runCurrent()
        assertEquals(CitationNavigationTargetResolution.Ready(native), result.await())
    }
    @Test fun `load error returns failed`() = runTest {
        val reader = MutableStateFlow(ReaderState(articleId = "target", content = ReaderState.Error(FullContentFailureReason.NETWORK)))
        val result = awaitCitationNavigationTarget(pending, reader, MutableStateFlow(ReaderTranslationUiState()),
            MutableStateFlow(Triple(native, null as String?, null as String?)), {}, {})
        assertEquals(CitationNavigationTargetResolution.Failed, result)
    }
    @Test fun `third article invalidates during each waiting stage`() = runTest {
        for (stage in 0..2) {
            val reader = MutableStateFlow(ReaderState(articleId = "target",
                content = if (stage == 0) ReaderState.Loading else ReaderState.Description("Ready")))
            val translation = MutableStateFlow(ReaderTranslationUiState(showTranslation = stage == 1))
            val result = async { awaitCitationNavigationTarget(pending, reader, translation,
                MutableStateFlow(Triple(native, null as String?, null as String?)), {}, {}) }
            runCurrent()
            assertFalse(result.isCompleted)
            reader.value = ReaderState(articleId = "third")
            runCurrent()
            assertEquals(CitationNavigationTargetResolution.Invalidated, result.await())
        }
    }
    @Test fun `renderer switch waits for current webview readiness`() = runTest {
        val reader = MutableStateFlow(ReaderState(articleId = "target", content = ReaderState.Description("Ready")))
        val ready = MutableStateFlow(Triple(native, null as String?, null as String?))
        val result = async { awaitCitationNavigationTarget(pending, reader, MutableStateFlow(ReaderTranslationUiState()), ready, {}, {}) }
        runCurrent()
        ready.value = Triple(web, "target", null)
        runCurrent()
        assertFalse(result.isCompleted)
        ready.value = Triple(web, "target", "target")
        runCurrent()
        assertEquals(CitationNavigationTargetResolution.Ready(web), result.await())
    }
}
