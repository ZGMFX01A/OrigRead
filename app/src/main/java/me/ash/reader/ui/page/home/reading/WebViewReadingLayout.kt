package me.ash.reader.ui.page.home.reading

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.material3.MaterialTheme
import kotlin.math.roundToInt
import me.ash.reader.ui.component.scrollbar.VerticalScrollIndicatorFactory
import me.ash.reader.ui.component.scrollbar.scrollIndicator
import me.ash.reader.ui.component.webview.WebViewReaderScrollState

private enum class WebViewReadingSlot { Header, Footer, WebView }

/**
 * Only the browser document is long. The Android View and its Compose host always have a finite
 * viewport. Native metadata/actions occupy matching HTML spacers and follow the browser's scroll.
 */
@Composable
internal fun WebViewReadingLayout(
    modifier: Modifier,
    scrollState: WebViewReaderScrollState,
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    webView: @Composable (headerHeightPx: Int, footerHeightPx: Int) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(scrollState, interactions) {
        interactions.interactions.collect {
            if (it is DragInteraction.Start) scrollState.beginUserScroll()
        }
    }
    // Drags beginning on native metadata/actions must scroll the same document as browser drags.
    val nativeOverlayScroll = rememberScrollableState { delta ->
        val view = scrollState.view
        if (view == null) {
            0f
        } else {
            view.stopReaderFling()
            val before = view.scrollY
            val maxScroll = (view.readerContentHeight - view.height).coerceAtLeast(0)
            view.scrollTo(0, (before - delta.toInt()).coerceIn(0, maxScroll))
            (before - view.scrollY).toFloat()
        }
    }
    val nativeOverlayFling = remember(scrollState) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                if (scrollState.view?.startReaderFling((-initialVelocity).roundToInt()) == true) 0f
                else initialVelocity
        }
    }
    val thumbColor = MaterialTheme.colorScheme.outline.copy(alpha = .5f)
    val indicator = remember(thumbColor) { VerticalScrollIndicatorFactory(thumbColor = thumbColor) }
    SubcomposeLayout(
        modifier = modifier.clipToBounds().scrollIndicator(indicator, scrollState, Orientation.Vertical),
    ) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Header/footer can be taller than the viewport, but must never dictate the WebView size.
        val overlayConstraints = Constraints(maxWidth = width)
        val headerPlaceable = subcompose(WebViewReadingSlot.Header) {
            Box(Modifier.scrollable(nativeOverlayScroll, Orientation.Vertical,
                flingBehavior = nativeOverlayFling, interactionSource = interactions)) { header() }
        }.single().measure(overlayConstraints)
        val footerPlaceable = subcompose(WebViewReadingSlot.Footer) {
            Box(Modifier.scrollable(nativeOverlayScroll, Orientation.Vertical,
                flingBehavior = nativeOverlayFling, interactionSource = interactions)) { footer() }
        }.single().measure(overlayConstraints)
        val browser = subcompose(WebViewReadingSlot.WebView) {
            webView(headerPlaceable.height, footerPlaceable.height)
        }.single().measure(Constraints.fixed(width, height))
        layout(width, height) {
            browser.placeRelative(0, 0)
            val scroll = scrollState.scrollOffset
            headerPlaceable.placeRelative((width - headerPlaceable.width) / 2, -scroll)
            val footerTop = scrollState.contentSize - footerPlaceable.height - scroll
            // Until the first document layout is published, avoid drawing actions over the header.
            if (scrollState.contentSize > 0) {
                footerPlaceable.placeRelative((width - footerPlaceable.width) / 2, footerTop)
            }
        }
    }
}
