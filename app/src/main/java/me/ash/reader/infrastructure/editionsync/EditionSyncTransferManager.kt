package me.ash.reader.infrastructure.editionsync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 创建只授权给另一 Edition 的一次性加密同步 Intent。 */
@Singleton
class EditionSyncTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val editionSyncService: EditionSyncService,
) {
    /** 仅判断另一 Edition 是否已安装；签名信任仍在真正创建直传 Intent 时严格校验。 */
    fun isPeerInstalled(peerPackage: String = EditionSyncContract.peerPackageName()): Boolean =
        runCatching { context.packageManager.getPackageInfo(peerPackage, 0) }.isSuccess

    /** 生成完整快照、加密并构造显式目标 Activity Intent；公共 AI/翻译 API Key 固定随 Edition Sync 迁移。 */
    suspend fun createPeerTransferIntent(): Intent {
        val peerPackage = EditionSyncContract.peerPackageName()
        requireTrustedPeer(peerPackage)
        cleanupOldTransfers()

        val encrypted =
            EditionSyncCrypto.encrypt(
                editionSyncService.exportBundle().toByteArray(Charsets.UTF_8)
            )
        val directory = File(context.cacheDir, EditionSyncContract.TEMP_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}${EditionSyncContract.TEMP_FILE_SUFFIX}")
        file.writeBytes(encrypted.ciphertext)
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        return Intent().apply {
            component = ComponentName(peerPackage, EditionSyncContract.IMPORT_ACTIVITY_CLASS)
            setDataAndType(uri, EditionSyncContract.MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(EditionSyncContract.EXTRA_SOURCE_PACKAGE, context.packageName)
            putExtra(EditionSyncContract.EXTRA_AES_KEY, encrypted.keyBase64)
            putExtra(EditionSyncContract.EXTRA_AES_IV, encrypted.ivBase64)
        }
    }

    /**
     * 直传只信任同签名对应 Edition。
     * 不同签名无法共享受信任的显式直传通道，第一版应改用现有“加密配置备份 → 手工恢复”，不伪装成已支持自动 fallback。
     */
    fun requireTrustedPeer(peerPackage: String = EditionSyncContract.peerPackageName()) {
        val peerInfo =
            runCatching { context.packageManager.getPackageInfo(peerPackage, 0) }
                .getOrElse { throw IllegalStateException("未安装对应的 OrigRead Edition", it) }
        require(peerInfo.packageName == peerPackage) { "目标 Edition 包名无效" }
        require(
            context.packageManager.checkSignatures(context.packageName, peerPackage) ==
                PackageManager.SIGNATURE_MATCH
        ) {
            "两个 OrigRead Edition 的签名不同，不能直接同步；请使用加密配置备份手动迁移"
        }
    }

    private fun cleanupOldTransfers() {
        val cutoff = System.currentTimeMillis() - TEMP_FILE_MAX_AGE_MILLIS
        File(context.cacheDir, EditionSyncContract.TEMP_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private companion object {
        const val TEMP_FILE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    }
}
