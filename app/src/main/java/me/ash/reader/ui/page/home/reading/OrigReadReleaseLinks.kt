package me.ash.reader.ui.page.home.reading

private const val ORIGREAD_RELEASE_TAG_PREFIX =
    "https://github.com/ZGMFX01A/OrigRead/releases/tag/"
private const val ORIGREAD_RELEASE_DOWNLOAD_PREFIX =
    "https://github.com/ZGMFX01A/OrigRead/releases/download/"

internal data class OrigReadReleaseLinks(
    val releasePageUrl: String,
    val apkDownloadUrl: String,
)

/**
 * 从 OrigRead GitHub Release 文章链接推导 APK 下载地址。
 *
 * 内置订阅使用 GitHub `releases.atom`，Feed 正文不会携带 Assets 列表；
 * APK 文件名与发布 tag 保持一致，例如 `v1.0.1` -> `OrigRead-v1.0.1.apk`。
 */
internal fun String?.toOrigReadReleaseLinks(): OrigReadReleaseLinks? {
    val releasePageUrl = this?.trim()?.takeIf { it.startsWith(ORIGREAD_RELEASE_TAG_PREFIX) }
        ?: return null
    val tag =
        releasePageUrl
            .removePrefix(ORIGREAD_RELEASE_TAG_PREFIX)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
            .takeIf { it.matches(Regex("[vV]?\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9._-]+)?")) }
            ?: return null

    return OrigReadReleaseLinks(
        releasePageUrl = releasePageUrl,
        apkDownloadUrl =
            "$ORIGREAD_RELEASE_DOWNLOAD_PREFIX$tag/OrigRead-$tag.apk",
    )
}
