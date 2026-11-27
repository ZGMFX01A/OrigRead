package me.ash.reader.infrastructure.editionsync

import com.google.gson.JsonParser
import java.util.Base64
import me.ash.reader.domain.model.account.security.DESUtils

/** 账户凭据只在已加密的直传包内部可移植；接收后立即使用目标设备 Keystore 重加密。 */
internal object EditionSyncCredentialTransfer {
    private const val transportPrefix = "edition-sync:credential-json:"

    fun encryptBundle(content: String): EditionSyncCrypto.EncryptedPayload {
        val portable = rewriteCredential(content) { ciphertext ->
            val cleartext = DESUtils.decrypt(ciphertext.ifEmpty { DESUtils.empty })
            require(JsonParser.parseString(cleartext).isJsonObject) { "账户凭据不是 JSON 对象" }
            transportPrefix + Base64.getEncoder().encodeToString(cleartext.toByteArray(Charsets.UTF_8))
        }
        return EditionSyncCrypto.encrypt(portable.toByteArray(Charsets.UTF_8))
    }

    fun decryptBundle(ciphertext: ByteArray, keyBase64: String, ivBase64: String): String {
        val authenticated = EditionSyncCrypto.decrypt(ciphertext, keyBase64, ivBase64).toString(Charsets.UTF_8)
        return rewriteCredential(authenticated) { value ->
            val cleartext = if (value.startsWith(transportPrefix)) {
                Base64.getDecoder().decode(value.removePrefix(transportPrefix)).toString(Charsets.UTF_8)
            } else {
                // 兼容旧 Edition 的 DES 直传包；不能把另一设备的本地 v2 密文当成本机密文保存。
                require(DESUtils.isLegacyCiphertext(value)) { "同步账户凭据仍绑定来源 Keystore，请更新来源 Edition" }
                DESUtils.decrypt(value.ifEmpty { DESUtils.empty })
            }
            require(JsonParser.parseString(cleartext).isJsonObject) { "同步账户凭据不是 JSON 对象" }
            DESUtils.encrypt(cleartext)
        }
    }

    fun credentialsEqual(local: String?, incoming: String?): Boolean =
        JsonParser.parseString(DESUtils.decrypt(local?.ifEmpty { DESUtils.empty } ?: DESUtils.empty)) ==
            JsonParser.parseString(DESUtils.decrypt(incoming?.ifEmpty { DESUtils.empty } ?: DESUtils.empty))

    private fun rewriteCredential(content: String, transform: (String) -> String): String {
        val root = JsonParser.parseString(content).asJsonObject
        val account = root.getAsJsonObject("reading").getAsJsonObject("sourceAccount")
        val credential = account.get("securityKey")
        if (credential == null || credential.isJsonNull) return content
        require(credential.isJsonPrimitive && credential.asJsonPrimitive.isString) { "同步账户凭据格式无效" }
        account.addProperty("securityKey", transform(credential.asString))
        return root.toString()
    }
}
