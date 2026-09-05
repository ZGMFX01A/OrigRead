package me.ash.reader.ui.component.webview

object WebViewHtml {

    const val HTML: String = """
<!DOCTYPE html>
<html dir="auto">
<head>
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
