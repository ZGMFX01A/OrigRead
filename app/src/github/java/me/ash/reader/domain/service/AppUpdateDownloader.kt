package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import com.king.app.updater.AppUpdater
import com.king.app.updater.listener.SimpleDownloadListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import me.ash.reader.R
import me.ash.reader.infrastructure.net.Download
import me.ash.reader.infrastructure.net.githubReleaseDownloadCandidates
import me.ash.reader.ui.ext.getLatestApk

/** GitHub 渠道使用成熟 AppUpdater 下载 Release APK；安装动作仍由 OrigRead UI 与系统安装器负责。 */
class AppUpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun download(url: String, filename: String): Flow<Download> =
        callbackFlow {
            if (url.isBlank()) {
                trySend(Download.Error(context.getString(R.string.download_failure)))
                close()
                return@callbackFlow
            }

            val candidates = githubReleaseDownloadCandidates(url)

            fun startCandidate(index: Int) {
                val candidate = candidates.getOrNull(index)
                if (candidate == null) {
                    trySend(Download.Error(context.getString(R.string.download_failure)))
                    close()
                    return
                }
                Log.i("RLog", "downloadUpdate start candidate ${index + 1}/${candidates.size}: $candidate")
                val updater =
                    AppUpdater.Builder(context)
                    .setUrl(candidate)
                    .setFilename(filename)
                    // Compose 弹窗已经显示进度，不额外申请 Android 13+ 通知权限。
                    .setShowNotification(false)
                    .setShowPercentage(false)
                    // 下载后先由 UI 完成 Android 8+ “安装未知应用”授权，再交给系统安装器。
                    .setInstallApk(false)
                    .setDownloadListener(
                        object : SimpleDownloadListener() {
                            override fun onStart(url: String) {
                                trySend(Download.Progress(0))
                            }

                            override fun onProgress(progress: Long, total: Long) {
                                val percent =
                                    if (total > 0L) ((progress * 100L) / total).toInt().coerceIn(0, 100)
                                    else 0
                                trySend(Download.Progress(percent))
                            }

                            override fun onSuccess(file: File) {
                                runCatching {
                                    val target = context.getLatestApk()
                                    if (file.absolutePath != target.absolutePath) {
                                        file.copyTo(target, overwrite = true)
                                    }
                                    target
                                }.onSuccess { target ->
                                    trySend(Download.Finished(target))
                                }.onFailure { error ->
                                    Log.e("RLog", "copy update apk failed", error)
                                    trySend(Download.Error(error.message ?: context.getString(R.string.download_failure)))
                                }
                                close()
                            }

                            override fun onError(cause: Throwable) {
                                Log.w("RLog", "downloadUpdate candidate failed: $candidate", cause)
                                if (index + 1 < candidates.size) {
                                    startCandidate(index + 1)
                                } else {
                                    trySend(
                                        Download.Error(
                                            cause.message ?: context.getString(R.string.download_failure)
                                        )
                                    )
                                    close()
                                }
                            }

                            override fun onCancel() {
                                trySend(Download.Cancelled)
                                close()
                            }
                        },
                    )
                    .build()
                updater.start()
            }

            startCandidate(0)
            // 下载任务由库内 Service 管理；Flow 关闭仅结束 UI 状态桥接。
            awaitClose { }
        }
}
