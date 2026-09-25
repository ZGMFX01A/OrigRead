package me.ash.reader.infrastructure.sync.core

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP 传输层请求签名规范与防重放工具类。
 *
 * 遵循 R11-R13 规范：双端在 HTTP(S) 传输层进行双向身份认证与防重放保护。
 * 请求者必须在头部附带设备 ID、毫秒时间戳、随机 Nonce 及基于私钥签名的 Base64 签名串。
 */
object SyncHttpAuthCanonicalizer {
    /** 发送方设备 ID 请求头名称 */
    const val HEADER_DEVICE_ID = "x-sync-device-id"

    /** 发送方时间戳（Unix 毫秒）请求头名称 */
    const val HEADER_TIMESTAMP = "x-sync-timestamp"

    /** 随机 Nonce 请求头名称 */
    const val HEADER_NONCE = "x-sync-nonce"

    /** ECDSA P-256 签名 Base64 请求头名称 */
    const val HEADER_SIGNATURE = "x-sync-signature"

    /** 允许的最大时钟偏差窗口（毫秒），默认 5 分钟 */
    const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L

    /** 空字节数组的 SHA-256 小写十六进制摘要值 */
    const val EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * 计算字节数组的 SHA-256 小写十六进制哈希字符串。
     *
     * @param bytes 待哈希的原始字节数组
     * @return 64 位十六进制小写哈希字符串
     */
    fun sha256Hex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return EMPTY_BODY_SHA256
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * 构建标准化的 HTTP 请求签名规范材料。
     *
     * 材料格式：
     * ```text
     * $method\n$path\n$timestamp\n$nonce\n$bodySha256
     * ```
     *
     * @param method HTTP 请求方法（大写，如 GET, POST）
     * @param path 请求的目标路径和查询参数（如 /v1/spaces/space-1/operations?ranges=%5B%5D）
     * @param timestamp 请求头中的时间戳字符串
     * @param nonce 请求头中的随机 Nonce 字符串
     * @param bodySha256 请求体的 SHA-256 小写十六进制摘要
     * @return 规范化的待签名字符串
     */
    fun canonicalSigningMaterial(
        method: String,
        path: String,
        timestamp: String,
        nonce: String,
        bodySha256: String,
    ): String = "${method.uppercase()}\n$path\n$timestamp\n$nonce\n$bodySha256"

    /**
     * 内存 Nonce 查重与防重放缓存。
     * 维护在有效时间窗口内已接收的 Nonce，并自动清理过期条目。
     *
     * @property expirationMs Nonce 的最大保留生命周期（毫秒）
     */
    class NonceCache(
        private val expirationMs: Long = 2 * MAX_CLOCK_SKEW_MS,
    ) {
        private val cache = ConcurrentHashMap<String, Long>()

        /**
         * 检查 Nonce 是否已被使用过。若未见过则记录并返回 true；若已见过则返回 false。
         *
         * @param nonce 请求中携带的 Nonce
         * @param now 当前时间戳（毫秒）
         * @return true 表示为新 Nonce，校验通过；false 表示检测到重放攻击
         */
        fun checkAndRecord(nonce: String, now: Long = System.currentTimeMillis()): Boolean {
            cleanupExpired(now)
            return cache.putIfAbsent(nonce, now) == null
        }

        /**
         * 清理超过有效时间窗口的过期 Nonce。
         *
         * @param now 当前时间戳（毫秒）
         */
        fun cleanupExpired(now: Long = System.currentTimeMillis()) {
            if (cache.size > 1000) {
                val cutoff = now - expirationMs
                cache.entries.removeIf { it.value < cutoff }
            }
        }

        /**
         * 清空缓存（主要用于测试重置）。
         */
        fun clear() {
            cache.clear()
        }
    }
}
