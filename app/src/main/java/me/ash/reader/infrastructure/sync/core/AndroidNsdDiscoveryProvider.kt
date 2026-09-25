package me.ash.reader.infrastructure.sync.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class AndroidSyncDiscoveredPeer(
    val endpointId: String,
    val deviceId: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val syncSpaceIds: List<String>,
    val fingerprint: String?,
)

data class AndroidSyncDiscoveryResult(
    val peers: List<AndroidSyncDiscoveredPeer>,
    val diagnosticCode: String? = null,
    val diagnosticMessage: String? = null,
)

/** Android NSD/mDNS discovery. Pairing is still public-key/SAS based; discovery never authorizes a peer. */
class AndroidNsdDiscoveryProvider(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun discover(timeoutMs: Long = 1_500L): AndroidSyncDiscoveryResult =
        suspendCancellableCoroutine { continuation ->
            val peers = linkedMapOf<String, AndroidSyncDiscoveredPeer>()
            var listener: NsdManager.DiscoveryListener? = null
            val finish = { code: String?, message: String? ->
                listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
                if (continuation.isActive) {
                    continuation.resume(
                        AndroidSyncDiscoveryResult(
                            peers = peers.values.sortedBy { it.endpointId },
                            diagnosticCode = code,
                            diagnosticMessage = message,
                        )
                    )
                }
            }
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val attributes = serviceInfo.attributes.mapValues { (_, value) -> value.toString(Charsets.UTF_8) }
                    val deviceId = attributes["deviceId"] ?: serviceInfo.serviceName
                    val protocol = attributes["tls"] == "1"
                    peers["nsd:$deviceId:${serviceInfo.host.hostAddress}:${serviceInfo.port}"] =
                        AndroidSyncDiscoveredPeer(
                            endpointId = "nsd:$deviceId:${serviceInfo.host.hostAddress}:${serviceInfo.port}",
                            deviceId = deviceId,
                            displayName = attributes["name"] ?: serviceInfo.serviceName,
                            host = serviceInfo.host.hostAddress ?: serviceInfo.host.hostName,
                            port = serviceInfo.port,
                            tls = protocol,
                            syncSpaceIds = attributes["space"]?.split(',')?.filter(String::isNotBlank).orEmpty(),
                            fingerprint = attributes["fingerprint"],
                        )
                }
            }
            listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) =
                    finish("PERMISSION_DENIED", "Android NSD discovery failed: $errorCode")

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.startsWith("_origread-sync._tcp")) {
                        runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    peers.entries.removeIf { it.value.displayName == serviceInfo.serviceName }
                }
            }
            try {
                nsdManager.discoverServices("_origread-sync._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
                mainHandler.postDelayed({
                    if (peers.isEmpty()) finish("DISCOVERY_EMPTY", "No OrigRead Sync peer was found on the local network")
                    else finish(null, null)
                }, timeoutMs.coerceIn(250L, 10_000L))
            } catch (error: SecurityException) {
                finish("PERMISSION_DENIED", error.message ?: "Local network permission denied")
            } catch (error: Throwable) {
                finish("PEER_UNREACHABLE", error.message ?: "NSD discovery failed")
            }
            continuation.invokeOnCancellation { listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } } }
        }
}
