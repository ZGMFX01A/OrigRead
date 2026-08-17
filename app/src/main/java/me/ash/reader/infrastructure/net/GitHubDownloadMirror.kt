package me.ash.reader.infrastructure.net

import android.content.res.Resources

private const val GITHUB_RELEASE_PREFIX = "https://github.com/"
private const val GITHUB_RELEASE_DOWNLOAD_MARKER = "/releases/download/"
private const val GITHUB_RELEASE_MIRROR_PREFIX = "https://gh-proxy.com/"

/** 只对 GitHub Release 二进制资产生成镜像地址，不代理 API、源码页或任意第三方 URL。 */
internal fun githubReleaseDownloadCandidates(
    url: String,
    preferMirror: Boolean = isMainlandChinaSystemRegion(),
): List<String> {
    val original = url.trim()
    if (
        !original.startsWith(GITHUB_RELEASE_PREFIX, ignoreCase = true) ||
            !original.contains(GITHUB_RELEASE_DOWNLOAD_MARKER, ignoreCase = true)
    ) {
        return listOf(original).filter(String::isNotBlank)
    }
    val mirror = "$GITHUB_RELEASE_MIRROR_PREFIX$original"
    return if (preferMirror) listOf(mirror, original) else listOf(original)
}

internal fun isMainlandChinaSystemRegion(): Boolean =
    Resources.getSystem().configuration.locales[0]
        ?.country
        ?.equals("CN", ignoreCase = true) == true
