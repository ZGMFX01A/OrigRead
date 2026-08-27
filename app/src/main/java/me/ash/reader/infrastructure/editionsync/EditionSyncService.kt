package me.ash.reader.infrastructure.editionsync

import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ash.reader.BuildConfig
import me.ash.reader.infrastructure.backup.ConfigurationBackupService

data class EditionSyncRestoreResult(
    val reading: EditionSyncReadingRestoreResult,
    val restoredConfiguration: Boolean,
)

private data class EditionSyncRollbackSnapshot(
    val configurationBackupJson: String,
    val configurationBackupPassword: String,
    val reading: EditionSyncReadingSnapshot,
)

/** Standard / LLM 共用的同步 Bundle 创建、检查与恢复入口。 */
@Singleton
class EditionSyncService @Inject constructor(
    private val configurationBackupService: ConfigurationBackupService,
    private val readingSnapshotService: EditionSyncReadingSnapshotService,
    private val accountResolver: EditionSyncAccountResolver,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * 创建完整公共数据快照。
     *
     * ConfigurationBackupService 继续负责规则/偏好/AI/翻译以及 API Key；随机密码只封装在外层同步 Bundle 内，
     * Bundle 随后还会由 [EditionSyncCrypto] 作为整体加密，因此临时文件不会出现明文 Secret。
     */
    suspend fun exportBundle(includeSecrets: Boolean = false): String {
        val backupPassword = randomTransferPassword()
        val configuration =
            configurationBackupService.exportBackup(
                includeSecrets = includeSecrets,
                password = backupPassword,
            )
        val reading = readingSnapshotService.exportCurrentAccount()
        readingSnapshotService.validate(reading)
        return json.encodeToString(
            EditionSyncBundle(
                sourceEdition = BuildConfig.EDITION,
                sourcePackageName = BuildConfig.APPLICATION_ID,
                sourceVersion = BuildConfig.VERSION_NAME,
                createdAtEpochMillis = System.currentTimeMillis(),
                configurationBackupJson = configuration,
                configurationBackupPassword = backupPassword,
                reading = reading,
            )
        )
    }

    /** 只做解析和完整性检查，不修改目标 App。 */
    fun inspectBundle(content: String): EditionSyncBundle {
        val bundle = json.decodeFromString<EditionSyncBundle>(content)
        require(bundle.schemaVersion == EditionSyncBundle.CURRENT_SCHEMA_VERSION) {
            "不支持的 Edition sync 版本：${bundle.schemaVersion}"
        }
        require(bundle.appName == EditionSyncBundle.APP_NAME) { "这不是 OrigRead Edition sync 数据" }
        require(bundle.sourceEdition != BuildConfig.EDITION) { "不能把同一 Edition 的直传包导回自身" }
        require(bundle.sourcePackageName == EditionSyncContract.peerPackageName()) { "同步数据来源包与当前 Edition 不匹配" }
        require(bundle.configurationBackupPassword.length >= 6) { "同步配置凭据块缺少有效密码" }
        configurationBackupService.inspectBackup(bundle.configurationBackupJson)
        readingSnapshotService.validate(bundle.reading)
        return bundle
    }

    /**
     * 用户在目标 Edition 明确确认后执行恢复。
     * 先解析全部静态数据，再为目标现状创建仅驻留内存的回滚快照；随后解析/创建目标账户并恢复公共配置和完整阅读状态。
     * 任一步骤抛错时会尽力恢复目标账户同步前的配置、Secret 与阅读主库；正常成功路径仍使用非破坏 merge。
     */
    suspend fun restoreBundle(content: String): EditionSyncRestoreResult {
        val bundle = inspectBundle(content)
        val previousAccountRollback = createRollbackSnapshot()
        val resolution = accountResolver.resolveOrCreate(bundle.reading.sourceAccount)
        val targetAccountRollback =
            if (resolution.created) {
                null
            } else {
                createRollbackSnapshot()
            }

        return try {
            configurationBackupService.restoreBackup(
                content = bundle.configurationBackupJson,
                password = bundle.configurationBackupPassword,
            )
            val readingResult = readingSnapshotService.restoreCurrentAccount(bundle.reading)
            EditionSyncRestoreResult(
                reading = readingResult,
                restoredConfiguration = true,
            )
        } catch (restoreError: Throwable) {
            runCatching {
                rollbackFailedRestore(
                    resolution = resolution,
                    previousAccountRollback = previousAccountRollback,
                    targetAccountRollback = targetAccountRollback,
                )
            }.exceptionOrNull()?.let(restoreError::addSuppressed)
            throw restoreError
        }
    }

    /** 创建包含当前账户、全局配置及 Secret 的内存回滚点；不会生成用户可见文件。 */
    private suspend fun createRollbackSnapshot(): EditionSyncRollbackSnapshot {
        val password = randomTransferPassword()
        return EditionSyncRollbackSnapshot(
            configurationBackupJson =
                configurationBackupService.exportBackup(
                    includeSecrets = true,
                    password = password,
                ),
            configurationBackupPassword = password,
            reading = readingSnapshotService.exportCurrentAccount(),
        )
    }

    /**
     * 对失败同步执行补偿回滚。
     *
     * 已有目标账户需要精确还原该账户；若同步临时创建了新账户，则先切回原账户并恢复原账户状态，再删除临时账户。
     */
    private suspend fun rollbackFailedRestore(
        resolution: EditionSyncAccountResolution,
        previousAccountRollback: EditionSyncRollbackSnapshot,
        targetAccountRollback: EditionSyncRollbackSnapshot?,
    ) {
        if (resolution.created) {
            accountResolver.switchTo(resolution.previousAccountId)
            restoreRollbackSnapshot(previousAccountRollback)
            accountResolver.deleteCreatedAccount(resolution)
            accountResolver.switchTo(resolution.previousAccountId)
            return
        }

        restoreRollbackSnapshot(requireNotNull(targetAccountRollback) { "Edition sync 缺少目标账户回滚快照" })
        accountResolver.restoreOriginalAccount(resolution)
        accountResolver.switchTo(resolution.previousAccountId)
    }

    /** 配置先恢复，再用精确阅读快照清掉失败 merge 可能新增的 Group/Feed/Article。 */
    private suspend fun restoreRollbackSnapshot(snapshot: EditionSyncRollbackSnapshot) {
        configurationBackupService.restoreBackup(
            content = snapshot.configurationBackupJson,
            password = snapshot.configurationBackupPassword,
        )
        readingSnapshotService.replaceCurrentAccount(snapshot.reading)
    }

    private fun randomTransferPassword(): String {
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
