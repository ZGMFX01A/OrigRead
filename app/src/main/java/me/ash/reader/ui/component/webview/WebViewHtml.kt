package me.ash.reader.ui.component.webview

import java.net.URI
import java.net.URISyntaxException
import java.security.SecureRandom
import java.util.Base64

object WebViewHtml {
    fun generateCspNonce(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .let { Base64.getEncoder().withoutPadding().encodeToString(it) }

    fun render(
        styleCss: String,
        baseHref: String?,
        articleHtml: String,
        script: String,
        nonce: String = generateCspNonce(),
    ): String {
        require(Regex("[A-Za-z0-9+/_-]+={0,2}").matches(nonce)) { "Invalid CSP nonce" }
        val base = baseHref?.let { value ->
            val uri = try { URI(value) } catch (_: URISyntaxException) { null }
            if (uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
                "<base href=\"${webViewHtmlAttributeEscape(value)}\" />" else ""
        }.orEmpty()
        val csp = "default-src 'none'; img-src https: http: data:; media-src https: http:; " +
            "font-src https://appassets.androidplatform.net data:; style-src 'nonce-$nonce'; " +
            "script-src 'nonce-$nonce'; base-uri https: http:; form-action 'none'; object-src 'none'; frame-src 'none';"
        return """
            <!DOCTYPE html><html dir="auto"><head>
            <meta name="viewport" content="initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no, width=device-width, viewport-fit=cover" />
            <meta content="text/html; charset=utf-8" http-equiv="content-type" />
            <meta http-equiv="Content-Security-Policy" content="$csp" />
            $base
            <style type="text/css" nonce="$nonce">$styleCss</style>
            </head><body>
            <div id="origread-reader-header" aria-hidden="true"></div>
            <main><article>$articleHtml</article></main>
            <div id="origread-reader-footer" aria-hidden="true"></div>
            <script nonce="$nonce">$script</script>
            </body></html>
        """.trimIndent()
    }


    const val HTML: String = """
<!DOCTYPE html>
<html dir="auto">
<head>
    <meta http-equiv="Content-Security-Policy" content="object-src 'none'; frame-src 'none'; form-action 'none';" />
    <meta name="viewport" content="initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no, width=device-width, viewport-fit=cover" />
    <meta content="text/html; charset=utf-8" http-equiv="content-type"/>
    <style type="text/css">
        %s
    </style>
    <base href="%s" />
</head>
<body>
<div id="origread-reader-header" aria-hidden="true"></div>
<main>
    <article>
        %s
    </article>
</main>
<div id="origread-reader-footer" aria-hidden="true"></div>
<script>
%s
</script>
</body>
</html>
"""
}
