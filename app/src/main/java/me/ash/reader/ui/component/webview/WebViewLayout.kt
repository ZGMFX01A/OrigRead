package me.ash.reader.ui.component.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import me.ash.reader.infrastructure.content.ContentHtmlSanitizer
import me.ash.reader.infrastructure.preference.ReadingFontsPreference

object WebViewLayout {

    @SuppressLint("SetJavaScriptEnabled")
    internal fun get(
        context: Context,
        readingFontsPreference: ReadingFontsPreference,
        webViewClient: WebViewClient,
        onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    ) =
        ArticleSelectionWebView(context).apply {
            this.webViewClient = webViewClient
            scrollBarSize = 0
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
            with(this.settings) {
                standardFontFamily =
                    when (readingFontsPreference) {
                        ReadingFontsPreference.Cursive -> "cursive"
                        ReadingFontsPreference.Monospace -> "monospace"
                        ReadingFontsPreference.SansSerif -> "sans-serif"
                        ReadingFontsPreference.Serif -> "serif"
                        ReadingFontsPreference.GoogleSans -> {
                            "sans-serif"
                        }
                        ReadingFontsPreference.External -> {
                            "sans-serif"
                        }

                        else -> "sans-serif"
                    }
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                domStorageEnabled = false
                javaScriptEnabled = true
                addJavascriptInterface(
                    object : JavaScriptInterface {
                        @JavascriptInterface
                        override fun onImgTagClick(imgUrl: String?, alt: String?) {
                            if (onImageClick != null && imgUrl != null && ContentHtmlSanitizer.safeImageUrl(imgUrl)) {
                                onImageClick.invoke(imgUrl, alt ?: "")
                            }
                        }
                    },
                    JavaScriptInterface.NAME,
                )
                setSupportZoom(false)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    isAlgorithmicDarkeningAllowed = true
                }
            }
        }
}
