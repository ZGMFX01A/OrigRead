package me.ash.reader.infrastructure.net

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
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.io.File

interface NetworkDataSource {

    @GET
    suspend fun getReleaseLatest(@Url url: String): Response<LatestRelease>

    @GET
    @Streaming
    suspend fun downloadFile(@Url url: String): ResponseBody

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
                    normalizedName.startsWith("origread-llm-")
                } else {
                    normalizedName.startsWith("origread-") &&
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
