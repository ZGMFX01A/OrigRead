package me.ash.reader.infrastructure.sync.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * DNS-SD advertisement counterpart to [AndroidNsdDiscoveryProvider]. Discovery metadata is kept
 * intentionally small and contains no access token; pairing/authentication happens after selection.
 */
class AndroidNsdAdvertisementProvider(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private var registrationListener: NsdManager.RegistrationListener? = null
    /** 当前广播的临时标识，仅用于从附近设备列表排除本机。 */
    var discoveryId: String? = null
        private set

    suspend fun register(
        port: Int,
        deviceId: String,
        displayName: String,
        syncSpaceIds: List<String> = emptyList(),
        tls: Boolean = false,
        fingerprint: String? = null,
    ): Boolean {
        require(port in 1..65_535) { "LAN Sync port must be valid" }
        require(deviceId.isNotBlank() && displayName.isNotBlank()) { "LAN Sync device metadata must not be blank" }
        unregister()
        // 遵循 B23 规范：局域网广播仅广播匿名随机临时 discoveryId，不泄露稳定持久 deviceId 或 spaceId
        val randomDiscoveryId = "and-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        discoveryId = randomDiscoveryId
        val genericName = "OrigRead Android"
        return suspendCancellableCoroutine { continuation ->
            val serviceInfo =
                NsdServiceInfo().apply {
                    serviceName = genericName
                    serviceType = "_origread-sync._tcp"
                    this.port = port
                    setAttribute("deviceId", randomDiscoveryId)
                    setAttribute("name", genericName)
                    setAttribute("tls", if (tls) "1" else "0")
                }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    // 保留系统错误码，区分权限、接口不可用与服务注册冲突。
                    android.util.Log.e("OrigReadSync", "NSD registration failed: $errorCode")
                    if (registrationListener === this) registrationListener = null
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    if (registrationListener === this) registrationListener = null
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            registrationListener = listener
            try {
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (failure: SecurityException) {
                // 权限失败必须保留原始原因，不能只留下笼统的注册失败提示。
                android.util.Log.e("OrigReadSync", "NSD registration permission failure", failure)
                registrationListener = null
                if (continuation.isActive) continuation.resume(false)
            } catch (failure: Throwable) {
                // 系统 API 拒绝参数或注册异常时记录完整堆栈，调用方仍收到失败。
                android.util.Log.e("OrigReadSync", "NSD registration failure", failure)
                registrationListener = null
                if (continuation.isActive) continuation.resume(false)
            }
            continuation.invokeOnCancellation {
                if (registrationListener === listener) {
                    runCatching { nsdManager.unregisterService(listener) }
                    registrationListener = null
                }
            }
        }
    }

    fun unregister() {
        discoveryId = null
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
    }
}
