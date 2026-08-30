package me.ash.reader.infrastructure.net

import android.content.res.Resources

private const val GITHUB_RELEASE_PREFIX = "https://github.com/"
private const val GITHUB_RELEASE_DOWNLOAD_MARKER = "/releases/download/"
private const val GITHUB_API_PREFIX = "https://api.github.com/"
private const val GITHUB_LATEST_RELEASE_MARKER = "/releases/latest"
private const val JSDELIVR_GITHUB_PACKAGE_API = "https://data.jsdelivr.com/v1/package/gh/"
private val GITHUB_RELEASE_MIRROR_PREFIXES =
    listOf(
        // 2026-08 对真实 OrigRead v1.4.0 APK 做 Range 请求可用；仅作为官方地址失败后的兜底。
        "https://ghfast.top/",
        "https://gh.zwy.one/",
    )

/** 只对 GitHub Release 二进制资产生成镜像地址，不代理源码页、API 或任意第三方 URL。 */
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
    if (!preferMirror) return listOf(original)
    return buildList {
        // VPN/代理环境下官方 GitHub 往往更快，不能仅按系统地区强制先绕公共镜像。
        add(original)
        GITHUB_RELEASE_MIRROR_PREFIXES.forEach { prefix -> add("$prefix$original") }
    }.distinct()
}

/**
 * GitHub API 检查只保留官方地址。
 *
 * 公共 GitHub 下载代理共享出口 IP，代理 `api.github.com` 时容易命中 GitHub 未认证 API 限流；
 * Release 文件下载可用并不代表 API JSON 可用，因此更新检查不再把下载镜像套在 API URL 前面。
 */
internal fun githubReleaseCheckCandidates(
    url: String,
    preferMirror: Boolean = isMainlandChinaSystemRegion(),
): List<String> {
    val original = url.trim()
    if (
        !original.startsWith(GITHUB_API_PREFIX, ignoreCase = true) ||
            !original.endsWith(GITHUB_LATEST_RELEASE_MARKER, ignoreCase = true)
    ) {
        return listOf(original).filter(String::isNotBlank)
    }
    return listOf(original)
}

/**
 * 国内更新检查优先查询 jsDelivr 的 GitHub package 版本索引。
 * 该接口只返回仓库 tags/versions；客户端仍从内置官方仓库自行构造 Release 与 APK URL。
 */
internal fun githubReleaseVersionCandidates(repositoryUrl: String): List<String> {
    val repository = repositoryUrl.trim().trimEnd('/')
    if (!repository.startsWith(GITHUB_RELEASE_PREFIX, ignoreCase = true)) return emptyList()
    val slug = repository.substring(GITHUB_RELEASE_PREFIX.length).trim('/')
    if (slug.count { it == '/' } != 1) return emptyList()
    return listOf("$JSDELIVR_GITHUB_PACKAGE_API$slug")
}

internal fun isMainlandChinaSystemRegion(): Boolean =
    Resources.getSystem().configuration.locales[0]
        ?.country
        ?.equals("CN", ignoreCase = true) == true
