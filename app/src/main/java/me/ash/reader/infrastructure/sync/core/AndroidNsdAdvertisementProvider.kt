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
        return suspendCancellableCoroutine { continuation ->
            val serviceInfo =
                NsdServiceInfo().apply {
                    serviceName = displayName.take(63)
                    serviceType = "_origread-sync._tcp"
                    this.port = port
                    setAttribute("deviceId", deviceId)
                    setAttribute("name", displayName)
                    setAttribute("tls", if (tls) "1" else "0")
                    if (syncSpaceIds.isNotEmpty()) setAttribute("space", syncSpaceIds.take(8).joinToString(","))
                    if (!fingerprint.isNullOrBlank()) setAttribute("fingerprint", fingerprint)
                }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            registrationListener = listener
            try {
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: SecurityException) {
                registrationListener = null
                if (continuation.isActive) continuation.resume(false)
            } catch (_: Throwable) {
                registrationListener = null
                if (continuation.isActive) continuation.resume(false)
            }
            continuation.invokeOnCancellation { unregister() }
        }
    }

    fun unregister() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
    }
}
