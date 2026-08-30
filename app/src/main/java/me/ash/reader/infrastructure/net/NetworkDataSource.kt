package me.ash.reader.infrastructure.net

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming
import retrofit2.http.Url

interface NetworkDataSource {

    @GET
    suspend fun getReleaseLatest(@Url url: String): Response<LatestRelease>

    @GET
    @Streaming
    suspend fun downloadFile(@Url url: String): ResponseBody

    /** 只取一个字节探测 Release 资产是否真实存在，避免 tag 存在但对应 Edition APK 未发布。 */
    @GET
    @Streaming
    suspend fun probeFile(
        @Url url: String,
        @Header("Range") range: String = "bytes=0-0",
    ): Response<ResponseBody>

    companion object {

        private var instance: NetworkDataSource? = null

        fun getInstance(): NetworkDataSource {
            return instance ?: synchronized(this) {
                instance ?: Retrofit.Builder()
                    .baseUrl("https://api.github.com/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build().create(NetworkDataSource::class.java).also {
                        instance = it
                    }
            }
        }
    }
}

internal data class LatestReleaseCheckResult(
    val release: LatestRelease?,
    val lastResponseCode: Int?,
)

private const val MAX_JSDELIVR_VERSION_RESPONSE_BYTES = 256 * 1024L
private val JSDELIVR_RELEASE_VERSION_PATTERN = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")

/** jsDelivr GitHub package API 只需要 versions 字段；其他服务端字段全部忽略。 */
internal data class JsDelivrPackageVersions(
    val versions: List<String> = emptyList(),
)

private enum class ReleaseAssetProbeResult {
    AVAILABLE,
    MISSING,
    UNAVAILABLE,
}

/** 按候选地址顺序检查 Release，并拒绝镜像返回的非法或非官方元数据。 */
internal suspend fun NetworkDataSource.getTrustedLatestRelease(
    urls: List<String>,
    repositoryUrl: String,
): LatestReleaseCheckResult {
    var lastResponseCode: Int? = null
    for (url in urls) {
        try {
            val response = getReleaseLatest(url)
            lastResponseCode = response.code()
            val body = response.body()
            if (response.isSuccessful && body != null && body.isTrustedReleaseMetadata(repositoryUrl)) {
                return LatestReleaseCheckResult(body, lastResponseCode)
            }
        } catch (_: Exception) {
            // A mirror failure must not prevent trying the next candidate.
            lastResponseCode = null
        }
    }
    return LatestReleaseCheckResult(null, lastResponseCode)
}

/**
 * 从 jsDelivr 的仓库 tag 索引中按版本从新到旧确认当前 Edition 的 APK 是否真实存在。
 * jsDelivr 响应中的任意 URL 都不会被信任或使用；APK URL 始终由内置官方仓库自行构造。
 */
internal suspend fun NetworkDataSource.getTrustedLatestReleaseVersion(
    urls: List<String>,
    repositoryUrl: String,
    llmEdition: Boolean,
): LatestRelease? {
    val gson = Gson()
    for (url in urls) {
        try {
            val responseBody = downloadFile(url)
            responseBody.use { body ->
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_JSDELIVR_VERSION_RESPONSE_BYTES) return@use
                val raw = body.string()
                if (raw.toByteArray(Charsets.UTF_8).size > MAX_JSDELIVR_VERSION_RESPONSE_BYTES) return@use
                val index = gson.fromJson(raw, JsDelivrPackageVersions::class.java) ?: return@use
                val versions =
                    index.versions
                        .asSequence()
                        .map(String::trim)
                        .filter(JSDELIVR_RELEASE_VERSION_PATTERN::matches)
                        .distinct()
                        .sortedWith(Comparator(::compareReleaseVersions).reversed())
                        .toList()
                if (versions.isEmpty()) return@use

                for (version in versions) {
                    val release = version.toTrustedLatestRelease(repositoryUrl, llmEdition)
                    val assetUrl = release.assets?.singleOrNull()?.browser_download_url ?: return null
                    when (probeReleaseAsset(assetUrl)) {
                        ReleaseAssetProbeResult.AVAILABLE -> return release
                        ReleaseAssetProbeResult.MISSING -> continue
                        ReleaseAssetProbeResult.UNAVAILABLE -> return null
                    }
                }
            }
        } catch (_: Exception) {
            // CDN 索引失败不影响官方 GitHub API 兜底。
        }
    }
    return null
}

/**
 * Range 探测只请求第一个字节；Streaming 响应不会预先把 APK 下载进内存。
 * 404 才能确定当前 Edition 在该 tag 下没有资产；其他异常状态交给 GitHub API 兜底，
 * 避免把限流、网络抖动等误判成“最新版不存在”后错误选择更旧版本。
 */
private suspend fun NetworkDataSource.probeReleaseAsset(url: String): ReleaseAssetProbeResult =
    try {
        val response = probeFile(url)
        try {
            when (response.code()) {
                200, 206 -> ReleaseAssetProbeResult.AVAILABLE
                404 -> ReleaseAssetProbeResult.MISSING
                else -> ReleaseAssetProbeResult.UNAVAILABLE
            }
        } finally {
            response.body()?.close()
            response.errorBody()?.close()
        }
    } catch (_: Exception) {
        ReleaseAssetProbeResult.UNAVAILABLE
    }

/**
 * 国内优先 jsDelivr tag 索引，海外优先官方 API；两条链互为兜底。
 * 公共代理不再访问 api.github.com，从根源避开共享出口的 GitHub API 限流。
 */
internal suspend fun NetworkDataSource.getReliableLatestRelease(
    apiUrls: List<String>,
    versionUrls: List<String>,
    repositoryUrl: String,
    llmEdition: Boolean,
    preferVersionIndex: Boolean,
): LatestReleaseCheckResult {
    if (preferVersionIndex) {
        val indexedRelease = getTrustedLatestReleaseVersion(versionUrls, repositoryUrl, llmEdition)
        if (indexedRelease != null) {
            // jsDelivr 负责在大陆网络下可靠发现最新 tag，官方 API 可达时再补齐更新日志、发布日期和大小。
            val apiResult = getTrustedLatestRelease(apiUrls, repositoryUrl)
            val officialRelease = apiResult.release
            if (officialRelease != null && officialRelease.hasSameReleaseTag(indexedRelease)) {
                return apiResult
            }
            return LatestReleaseCheckResult(indexedRelease, apiResult.lastResponseCode)
        }
        return getTrustedLatestRelease(apiUrls, repositoryUrl)
    }

    val apiResult = getTrustedLatestRelease(apiUrls, repositoryUrl)
    if (apiResult.release != null) return apiResult
    val indexedRelease = getTrustedLatestReleaseVersion(versionUrls, repositoryUrl, llmEdition)
    return if (indexedRelease != null) {
        LatestReleaseCheckResult(indexedRelease, apiResult.lastResponseCode)
    } else {
        apiResult
    }
}

/** 比较 tag 时忽略历史版本中可选的 `v` 前缀，避免同一版本因格式差异无法补齐官方元数据。 */
private fun LatestRelease.hasSameReleaseTag(other: LatestRelease): Boolean =
    tag_name
        ?.trim()
        ?.removePrefix("v")
        ?.equals(
            other.tag_name?.trim()?.removePrefix("v"),
            ignoreCase = true,
        ) == true

/** jsDelivr 只决定最新 tag；Release 页面与 APK URL 仍由内置官方仓库确定。 */
private fun String.toTrustedLatestRelease(
    repositoryUrl: String,
    llmEdition: Boolean,
): LatestRelease {
    val normalizedTag = "v${trim().removePrefix("v")}"
    val repository = repositoryUrl.trim().trimEnd('/')
    val apkName = if (llmEdition) "OrigRead-X-$normalizedTag.apk" else "OrigRead-$normalizedTag.apk"
    return LatestRelease(
        html_url = "$repository/releases/tag/$normalizedTag",
        tag_name = normalizedTag,
        name = "OrigRead $normalizedTag",
        assets =
            listOf(
                AssetsItem(
                    name = apkName,
                    content_type = "application/vnd.android.package-archive",
                    size = 0,
                    browser_download_url = "$repository/releases/download/$normalizedTag/$apkName",
                )
            ),
        body = "",
    )
}

/** 只用于 jsDelivr 已经过滤的 semver 字符串；稳定版高于同核心版本的预发布版。 */
private fun compareReleaseVersions(left: String, right: String): Int {
    fun parts(value: String): Pair<List<Int>, String?> {
        val withoutBuild = value.substringBefore('+')
        val core = withoutBuild.substringBefore('-').split('.').map(String::toInt)
        val preRelease = withoutBuild.substringAfter('-', missingDelimiterValue = "").takeIf(String::isNotBlank)
        return core to preRelease
    }

    val (leftCore, leftPre) = parts(left)
    val (rightCore, rightPre) = parts(right)
    leftCore.indices.forEach { index ->
        val compared = leftCore[index].compareTo(rightCore[index])
        if (compared != 0) return compared
    }
    if (leftPre == null && rightPre != null) return 1
    if (leftPre != null && rightPre == null) return -1
    return leftPre.orEmpty().compareTo(rightPre.orEmpty())
}

fun ResponseBody.downloadToFileWithProgress(saveFile: File): Flow<Download> =
    flow {
        emit(Download.Progress(0))

        // flag to delete file if download errors or is cancelled
        var deleteFile = true

        try {
            byteStream().use { inputStream ->
                saveFile.outputStream().use { outputStream ->
                    val totalBytes = contentLength()
                    val data = ByteArray(8_192)
                    var progressBytes = 0L

                    while (true) {
                        val bytes = inputStream.read(data)

                        if (bytes == -1) {
                            break
                        }

                        outputStream.channel
                        outputStream.write(data, 0, bytes)
                        progressBytes += bytes

                        emit(Download.Progress(percent = ((progressBytes * 100) / totalBytes).toInt()))
                    }

                    when {
                        progressBytes < totalBytes ->
                            throw Exception("missing bytes")

                        progressBytes > totalBytes ->
                            throw Exception("too many bytes")

                        else ->
                            deleteFile = false
                    }
                }
            }

            emit(Download.Finished(saveFile))
        } finally {
            // check if download was successful

            if (deleteFile) {
                saveFile.delete()
            }
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

data class LatestRelease(
    val html_url: String? = null,
    val tag_name: String? = null,
    val name: String? = null,
    val draft: Boolean? = null,
    val prerelease: Boolean? = null,
    val created_at: String? = null,
    val published_at: String? = null,
    val assets: List<AssetsItem>? = null,
    val body: String? = null,
)

data class AssetsItem(
    val name: String? = null,
    val content_type: String? = null,
    val size: Int? = null,
    val download_count: Int? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val browser_download_url: String? = null,
)

sealed class Download {
    object NotYet : Download()
    data class Progress(val percent: Int) : Download()
    data class Finished(val file: File) : Download()
    data class Error(val message: String) : Download()
    object Cancelled : Download()
}

/**
 * 从 GitHub Release 资产中选择 OrigRead 的可安装 APK。
 *
 * GitHub Release 可能同时包含校验文件、源码压缩包或后续增加的其他资产，不能依赖列表第一项。
 */
internal fun LatestRelease.preferredApkAsset(llmEdition: Boolean = false): AssetsItem? =
    assets.orEmpty()
        .asSequence()
        .filter { asset ->
            val name = asset.name.orEmpty()
            val url = asset.browser_download_url.orEmpty()
            val normalizedName = name.lowercase()
            val matchesEdition =
                if (llmEdition) {
                    // 新品牌使用 OrigRead-X；兼容旧 Release 的 OrigRead-LLM 资产，避免升级链断裂。
                    normalizedName.startsWith("origread-x-") ||
                        normalizedName.startsWith("origread-llm-")
                } else {
                    normalizedName.startsWith("origread-") &&
                        !normalizedName.startsWith("origread-x-") &&
                        !normalizedName.startsWith("origread-llm-")
                }
            url.isNotBlank() &&
                matchesEdition &&
                (name.endsWith(".apk", ignoreCase = true) ||
                    asset.content_type.equals("application/vnd.android.package-archive", ignoreCase = true))
        }
        .sortedWith(
            compareBy<AssetsItem> { it.name.orEmpty().contains("debug", ignoreCase = true) }
                .thenBy { it.name.orEmpty() },
        )
        .firstOrNull()

/** 镜像只用于传输，Release 页面和安装包仍必须属于内置的官方仓库。 */
internal fun LatestRelease.isTrustedReleaseMetadata(repositoryUrl: String): Boolean {
    val repository = repositoryUrl.trim().trimEnd('/')
    val releasePage = html_url?.trim().orEmpty()
    val tag = tag_name?.trim().orEmpty()
    return tag.isNotBlank() &&
        releasePage.equals("$repository/releases/tag/$tag", ignoreCase = true)
}

internal fun LatestRelease.preferredTrustedApkAsset(
    repositoryUrl: String,
    llmEdition: Boolean = false,
): AssetsItem? {
    val downloadPrefix = "${repositoryUrl.trim().trimEnd('/')}/releases/download/"
    return copy(
        assets = assets.orEmpty().filter { asset ->
            asset.browser_download_url
                ?.trim()
                ?.startsWith(downloadPrefix, ignoreCase = true) == true
        },
    ).preferredApkAsset(llmEdition = llmEdition)
}
