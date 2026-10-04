package me.ash.reader.infrastructure.sync.core

import java.io.IOException
import okhttp3.Response

/** 保留真实 HTTP 状态，避免把鉴权、策略拒绝或服务异常当成缺失附件。 */
class SyncHttpStatusException(val statusCode: Int, action: String) :
    IOException("$action: HTTP $statusCode")

/** 在读取或应用正文前检查服务端结果；失败由同步会话明确报告。 */
internal fun requireSyncHttpSuccess(response: Response, action: String) {
    if (!response.isSuccessful) throw SyncHttpStatusException(response.code, action)
}

/** 元数据先行仅允许远端明确缺少附件；其他失败必须停止当前会话。 */
internal object SyncOptionalBlobFailure {
    // 404 表示远端附件缺失，已有元数据与持久化引用可在稍后补齐附件。
    private const val HTTP_NOT_FOUND = 404

    /** 保留可补齐的附件引用；拒绝、断线或内容损坏则继续上抛。 */
    fun handle(error: Throwable, optional: Boolean) {
        if (!optional || error !is SyncHttpStatusException || error.statusCode != HTTP_NOT_FOUND) {
            throw error
        }
        // 附件确实缺失时保留待补齐状态，并明确记录原因；不吞掉网络或授权失败。
        android.util.Log.w("OrigReadSync", "Optional Blob missing on remote endpoint", error)
    }
}
