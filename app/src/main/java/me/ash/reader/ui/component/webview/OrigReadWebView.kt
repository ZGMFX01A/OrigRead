package me.ash.reader.ui.component.webview

import android.util.Log
import android.webkit.WebView
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.infrastructure.preference.LocalReadingBoldCharacters
import me.ash.reader.infrastructure.preference.LocalReadingFonts
import me.ash.reader.infrastructure.preference.LocalReadingImageHorizontalPadding
import me.ash.reader.infrastructure.preference.LocalReadingImageRoundedCorners
import me.ash.reader.infrastructure.preference.LocalReadingPageTonalElevation
import me.ash.reader.infrastructure.preference.LocalReadingSubheadBold
import me.ash.reader.infrastructure.preference.LocalReadingSubheadUpperCase
import me.ash.reader.infrastructure.preference.LocalReadingTextAlign
import me.ash.reader.infrastructure.preference.LocalReadingTextBold
import me.ash.reader.infrastructure.preference.LocalReadingTextFontSize
import me.ash.reader.infrastructure.preference.LocalReadingTextHorizontalPadding
import me.ash.reader.infrastructure.preference.LocalReadingTextLetterSpacing
import me.ash.reader.infrastructure.preference.LocalReadingTextLineHeight
import me.ash.reader.infrastructure.preference.ReadingFontsPreference
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerSnapshot
import me.ash.reader.ui.component.reader.ReaderEvidenceMarkerNavigationTarget
import me.ash.reader.ui.component.reader.readerEvidenceMarkerDisplayOrder
import me.ash.reader.ui.ext.ExternalFonts
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.theme.palette.alwaysLight

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OrigReadWebView(
    modifier: Modifier = Modifier,
    articleId: String? = null,
    sourceUrl: String? = null,
    isOriginalContent: Boolean = true,
    content: String,
    refererDomain: String? = null,
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    selectionActionLabel: String? = null,
    onSelectedTextAction: ((String) -> Unit)? = null,
    readerAnchorState: WebViewReaderAnchorState? = null,
    markerSnapshot: ReaderEvidenceMarkerSnapshot? = null,
    onEvidenceMarkerClick: ((ReaderEvidenceMarkerNavigationTarget) -> Unit)? = null,
) {
    val context = LocalContext.current
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp.value
    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current
    val tonalElevation = LocalReadingPageTonalElevation.current
    val backgroundColor =
        MaterialTheme.colorScheme.surfaceColorAtElevation(tonalElevation.value.dp).toArgb()
    val selectionTextColor = Color.Black.toArgb()
    val selectionBgColor = (MaterialTheme.colorScheme.tertiaryContainer alwaysLight true).toArgb()
    val textColor: Int = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val textBold: Boolean = LocalReadingTextBold.current.value
    val textAlign: String = LocalReadingTextAlign.current.toTextAlignCSS()
    val textMargin: Int = LocalReadingTextHorizontalPadding.current
    val boldTextColor: Int = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkTextColor: Int = MaterialTheme.colorScheme.primary.toArgb()
    val subheadBold: Boolean = LocalReadingSubheadBold.current.value
    val subheadUpperCase: Boolean = LocalReadingSubheadUpperCase.current.value
    val readingFonts = LocalReadingFonts.current
    val fontSize: Int = LocalReadingTextFontSize.current
    val letterSpacing: Float = LocalReadingTextLetterSpacing.current
    val lineHeight: Float = LocalReadingTextLineHeight.current
    val imgMargin: Int = LocalReadingImageHorizontalPadding.current
    val imgBorderRadius: Int = LocalReadingImageRoundedCorners.current
    val codeTextColor: Int = MaterialTheme.colorScheme.tertiary.toArgb()
    val codeBgColor: Int =
        MaterialTheme.colorScheme.surfaceColorAtElevation((tonalElevation.value + 6).dp).toArgb()
    val boldCharacters = LocalReadingBoldCharacters.current
    val citationHighlightColorCss =
        MaterialTheme.colorScheme.secondaryContainer.toArgb().toWebCssColor()
    val citationMarkerForegroundCss =
        MaterialTheme.colorScheme.onSecondaryContainer.toArgb().toWebCssColor()
    val citationMarkerBackgroundCss =
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f).toArgb().toWebCssColor()
    val citationHighlightSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val citationHighlightDurationMillis =
        remember(citationHighlightSpec) {
            citationHighlightSpec
                .vectorize(Float.VectorConverter)
                .getDurationNanos(
                    AnimationVector1D(0f),
                    AnimationVector1D(1f),
                    AnimationVector1D(0f),
                )
                .div(1_000_000L)
                .coerceAtLeast(1L)
        }

    val fontPath =
        if (readingFonts is ReadingFontsPreference.External)
            ExternalFonts.FontType.ReadingFont.toPath(context)
        else if (readingFonts is ReadingFontsPreference.GoogleSans) {
            "/android_res/font/google_sans_flex.ttf"
        } else null

    val preparedContent =
        remember(content, sourceUrl, isOriginalContent) {
            prepareWebViewReaderContent(content, sourceUrl, isOriginalContent)
        }
    val renderSpec =
        WebViewRenderSpec(
            articleId = articleId,
            sourceUrl = sourceUrl,
            originalContent = isOriginalContent,
            content = preparedContent.html,
            fontSize = fontSize,
            fontPath = fontPath,
            lineHeight = lineHeight,
            letterSpacing = letterSpacing,
            textMargin = textMargin,
            textColor = textColor,
            textBold = textBold,
            textAlign = textAlign,
            boldTextColor = boldTextColor,
            subheadBold = subheadBold,
            subheadUpperCase = subheadUpperCase,
            imgMargin = imgMargin,
            imgBorderRadius = imgBorderRadius,
            linkTextColor = linkTextColor,
            codeTextColor = codeTextColor,
            codeBgColor = codeBgColor,
            selectionTextColor = selectionTextColor,
            selectionBgColor = selectionBgColor,
            boldCharacters = boldCharacters.value,
        )
    val renderGuard = remember { WebViewRenderGuard() }
    val currentArticleId by rememberUpdatedState(articleId)
    val currentMarkerSnapshot by rememberUpdatedState(markerSnapshot)
    val currentEvidenceMarkerClick by rememberUpdatedState(onEvidenceMarkerClick)

    AndroidView(
        modifier = modifier,
        factory = {
            // factory 重新创建了实体时必须让新 WebView 完成首次正文加载。
            renderGuard.reset()
            WebViewLayout.get(
                context = context,
                readingFontsPreference = readingFonts,
                webViewClient =
                    WebViewClient(
                        context = context,
                        refererDomain = refererDomain,
                        onOpenLink = { url ->
                            val displayOrder = readerEvidenceMarkerDisplayOrder(url)
                            if (displayOrder != null) {
                                currentMarkerSnapshot
                                    ?.navigationTargetFor(currentArticleId, displayOrder)
                                    ?.let { target -> currentEvidenceMarkerClick?.invoke(target) }
                            } else {
                                context.openURL(url, openLink, openLinkSpecificBrowser)
                            }
                        },
                        onPageFinishedReady = { view, pageUrl ->
                            renderGuard.acceptedReaderGeneration(pageUrl)?.let { generation ->
                                view.postVisualStateCallback(
                                    generation,
                                    object : WebView.VisualStateCallback() {
                                        override fun onComplete(requestId: Long) {
                                            if (
                                                requestId == generation &&
                                                    renderGuard.isCurrentGeneration(generation)
                                            ) {
                                                readerAnchorState?.markRenderReady(view, generation)
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    ),
                onImageClick = onImageClick,
            )
        },
        update = {
            it.apply {
                // 选区回调属于交互状态，可以随父级重组更新，但不应因此重载整篇正文。
                configureSelectionAction(selectionActionLabel, onSelectedTextAction)
                readerAnchorState?.setMarkerSnapshot(markerSnapshot)
                settings.defaultFontSize = fontSize
                renderGuard.beginReload(renderSpec)?.let { renderGeneration ->
                    Log.i("RLog", "maxWidth: ${maxWidth}")
                    Log.i("RLog", "readingFont: ${context.filesDir.absolutePath}")
                    readerAnchorState?.bindRender(
                        articleId = articleId,
                        originalContent = isOriginalContent,
                        evidenceDocument = preparedContent.evidenceDocument,
                        renderGeneration = renderGeneration,
                        webView = this,
                        highlightColorCss = citationHighlightColorCss,
                        markerForegroundCss = citationMarkerForegroundCss,
                        markerBackgroundCss = citationMarkerBackgroundCss,
                        highlightDurationMillis = citationHighlightDurationMillis,
                    )
                    loadDataWithBaseURL(
                        webViewReaderBaseUrl(sourceUrl, renderGeneration),
                        WebViewHtml.HTML.format(
                            WebViewStyle.get(
                                fontSize = fontSize,
                                fontPath = fontPath,
                                lineHeight = lineHeight,
                                letterSpacing = letterSpacing,
                                textMargin = textMargin,
                                textColor = textColor,
                                textBold = textBold,
                                textAlign = textAlign,
                                boldTextColor = boldTextColor,
                                subheadBold = subheadBold,
                                subheadUpperCase = subheadUpperCase,
                                imgMargin = imgMargin,
                                imgBorderRadius = imgBorderRadius,
                                linkTextColor = linkTextColor,
                                codeTextColor = codeTextColor,
                                codeBgColor = codeBgColor,
                                tableMargin = textMargin,
                                selectionTextColor = selectionTextColor,
                                selectionBgColor = selectionBgColor,
                            ),
                            webViewHtmlAttributeEscape(sourceUrl.orEmpty()),
                            preparedContent.html,
                            WebViewScript.get(boldCharacters.value),
                        ),
                        "text/HTML",
                        "UTF-8",
                        null,
                    )
                }
            }
        },
        onRelease = { view ->
            renderGuard.reset()
            readerAnchorState?.unbind(view)
            view.stopLoading()
            view.configureSelectionAction(null, null)
            view.removeJavascriptInterface(JavaScriptInterface.NAME)
            view.loadUrl("about:blank")
            view.clearHistory()
            view.removeAllViews()
            view.destroy()
        },
    )
}

private fun Int.toWebCssColor(): String {
    val alpha = ((this ushr 24) and 0xff) / 255f
    val red = (this ushr 16) and 0xff
    val green = (this ushr 8) and 0xff
    val blue = this and 0xff
    return "rgba($red,$green,$blue,$alpha)"
}

internal fun webViewHtmlAttributeEscape(value: String): String =
    value.replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
