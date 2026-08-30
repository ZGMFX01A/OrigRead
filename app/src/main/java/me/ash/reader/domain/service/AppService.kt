package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.general.toVersion
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.net.Download
import me.ash.reader.infrastructure.net.NetworkDataSource
import me.ash.reader.infrastructure.net.getReliableLatestRelease
import me.ash.reader.infrastructure.net.githubReleaseCheckCandidates
import me.ash.reader.infrastructure.net.githubReleaseVersionCandidates
import me.ash.reader.infrastructure.net.isMainlandChinaSystemRegion
import me.ash.reader.infrastructure.net.preferredTrustedApkAsset
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.infrastructure.preference.NewVersionSizePreference.formatSize
import me.ash.reader.ui.ext.getCurrentVersion
import me.ash.reader.ui.ext.isLlmEdition
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.skipVersionNumber
import javax.inject.Inject

class AppService @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val networkDataSource: NetworkDataSource,
    private val appUpdateDownloader: AppUpdateDownloader,
    @IODispatcher
    private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher
    private val mainDispatcher: CoroutineDispatcher,
) {

    suspend fun checkUpdate(showToast: Boolean = true): Boolean? = withContext(ioDispatcher) {
        try {
            val repositoryUrl = context.getString(R.string.github_link)
            val preferVersionIndex = isMainlandChinaSystemRegion()
            val checkResult =
                networkDataSource.getReliableLatestRelease(
                    apiUrls =
                        githubReleaseCheckCandidates(
                            context.getString(R.string.update_link),
                            preferMirror = preferVersionIndex,
                        ),
                    versionUrls = githubReleaseVersionCandidates(repositoryUrl),
                    repositoryUrl = repositoryUrl,
                    llmEdition = isLlmEdition,
                    preferVersionIndex = preferVersionIndex,
                )
            val latest = checkResult.release ?: run {
                withContext(mainDispatcher) {
                    if (showToast) {
                        context.showToast(
                            context.getString(
                                if (checkResult.lastResponseCode == 403 || checkResult.lastResponseCode == 429) {
                                    R.string.rate_limit
                                } else {
                                    R.string.check_failure
                                },
                            ),
                        )
                    }
                }
                return@withContext null
            }

            val skipVersion = context.skipVersionNumber.toVersion()
            val currentVersion = context.getCurrentVersion()
            val latestVersion = latest.tag_name.toVersion()
//            val latestVersion = "1.0.0".toVersion()
            val latestLog = latest.body ?: ""
            val latestPublishDate = latest.published_at ?: latest.created_at ?: ""
            // GitHub Release 不保证 APK 永远位于 assets 第一项，必须按文件类型明确选择。
            val apkAsset =
                latest.preferredTrustedApkAsset(
                    repositoryUrl = repositoryUrl,
                    llmEdition = isLlmEdition,
                )
            val latestSize = apkAsset?.size ?: 0
            val latestDownloadUrl = apkAsset?.browser_download_url.orEmpty()

            Log.i("RLog", "current version $currentVersion")
            if (latestVersion.whetherNeedUpdate(currentVersion, skipVersion)) {
                Log.i("RLog", "new version $latestVersion")
                NewVersionNumberPreference.put(context, this, latestVersion.toString())
                NewVersionLogPreference.put(context, this, latestLog)
                NewVersionPublishDatePreference.put(context, this, latestPublishDate)
                NewVersionSizePreference.put(context, this, latestSize.formatSize())
                NewVersionDownloadUrlPreference.put(context, this, latestDownloadUrl)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("RLog", "checkUpdate: ${e.message}")
            withContext(mainDispatcher) {
                if (showToast) context.showToast(context.getString(R.string.check_failure))
            }
            null
        }
    }

    /** 下载实现由 flavor 提供：GitHub 使用 AppUpdater，商店渠道不携带自更新库。 */
    fun downloadUpdate(url: String, filename: String): Flow<Download> =
        appUpdateDownloader.download(url, filename)
}
