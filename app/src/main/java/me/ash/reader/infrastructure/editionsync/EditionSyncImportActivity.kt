package me.ash.reader.infrastructure.editionsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.R

/**
 * 另一 Edition 的显式同步接收页。
 *
 * 不声明通用 Intent Filter；只接受显式 Component 调用，并在读取 URI 前再次校验预期包名、FileProvider authority 与签名。
 */
@AndroidEntryPoint
class EditionSyncImportActivity : AppCompatActivity() {
    @Inject lateinit var editionSyncService: EditionSyncService

    private var pendingPlaintext: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { loadAndConfirm(intent) }
    }

    private suspend fun loadAndConfirm(intent: Intent) {
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    val sourcePackage =
                        intent.getStringExtra(EditionSyncContract.EXTRA_SOURCE_PACKAGE)
                            ?: error("同步请求缺少来源包")
                    validateSource(sourcePackage, intent)
                    val uri = requireNotNull(intent.data) { "同步请求缺少数据 URI" }
                    val key =
                        intent.getStringExtra(EditionSyncContract.EXTRA_AES_KEY)
                            ?: error("同步请求缺少加密 Key")
                    val iv =
                        intent.getStringExtra(EditionSyncContract.EXTRA_AES_IV)
                            ?: error("同步请求缺少加密 IV")
                    val ciphertext =
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("无法读取 Edition sync 数据")
                    val plaintext =
                        EditionSyncCrypto.decrypt(ciphertext, key, iv).toString(Charsets.UTF_8)
                    val bundle = editionSyncService.inspectBundle(plaintext)
                    plaintext to bundle
                }
            }

        result.fold(
            onSuccess = { (plaintext, bundle) ->
                pendingPlaintext = plaintext
                showConfirmation(bundle)
            },
            onFailure = ::finishWithError,
        )
    }

    private fun validateSource(sourcePackage: String, intent: Intent) {
        require(sourcePackage == EditionSyncContract.peerPackageName()) { "来源不是当前 Edition 对应的 OrigRead 包" }
        val uri = requireNotNull(intent.data) { "同步请求缺少 URI" }
        require(uri.authority == "$sourcePackage.fileprovider") { "同步 URI authority 与来源包不匹配" }
        require(
            packageManager.checkSignatures(packageName, sourcePackage) == PackageManager.SIGNATURE_MATCH
        ) {
            "来源 OrigRead Edition 与当前 App 签名不一致"
        }
    }

    private fun showConfirmation(bundle: EditionSyncBundle) {
        val reading = bundle.reading
        AlertDialog.Builder(this)
            .setTitle(R.string.edition_sync_receive_title)
            .setMessage(
                getString(
                    R.string.edition_sync_receive_desc,
                    reading.sourceAccount.name,
                    reading.feeds.size,
                    reading.articles.size,
                )
            )
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.edition_sync_confirm) { _, _ -> restorePending() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** 用户确认后才执行任何目标数据写入。 */
    private fun restorePending() {
        val content = pendingPlaintext ?: return finishWithError(IllegalStateException("同步数据已失效"))
        pendingPlaintext = null
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { editionSyncService.restoreBundle(content) }
                }
            result.fold(
                onSuccess = { restored ->
                    val reading = restored.reading
                    val message =
                        getString(
                            R.string.edition_sync_success,
                            reading.restoredFeeds,
                            reading.restoredArticles,
                        )
                    Toast.makeText(this@EditionSyncImportActivity, message, Toast.LENGTH_LONG).show()
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(EditionSyncContract.EXTRA_RESULT_MESSAGE, message),
                    )
                    finish()
                },
                onFailure = ::finishWithError,
            )
        }
    }

    private fun finishWithError(error: Throwable) {
        val message = error.message?.takeIf(String::isNotBlank) ?: getString(R.string.edition_sync_failed)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(
            Activity.RESULT_CANCELED,
            Intent().putExtra(EditionSyncContract.EXTRA_RESULT_MESSAGE, message),
        )
        finish()
    }
}
