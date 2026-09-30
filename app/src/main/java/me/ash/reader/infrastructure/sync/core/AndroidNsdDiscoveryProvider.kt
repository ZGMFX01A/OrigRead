package me.ash.reader.infrastructure.sync.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * Android NSD/mDNS discovery provider.
 *
 * 遵循 R11-A 与 R11-B 规范：
 * 1. 发现仅作为候选节点发现，绝不等同于信任与授权；
 * 2. 串行调度 NsdManager.resolveService，防御 Android 旧版本并发解析触发 FAILURE_ALREADY_ACTIVE；
 * 3. 严格管理 DiscoveryListener 与 ResolveListener 生命周期，避免泄漏；
 * 4. 感知并检查 ACCESS_LOCAL_NETWORK 权限。
 */
class AndroidNsdDiscoveryProvider(private val context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val legacyResolveInFlight = AtomicBoolean(false)
    private enum class ResolveAttempt { SUCCESS, FAILED, BLOCKED }

    private fun canStopServiceResolution(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7

    private fun checkPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 37 || context.applicationInfo.targetSdkVersion < 37) {
            return true
        }
        return try {
            val res = ContextCompat.checkSelfPermission(context, "android.permission.ACCESS_LOCAL_NETWORK")
            res == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun discover(timeoutMs: Long = 2_000L): AndroidSyncDiscoveryResult {
        if (!checkPermission()) {
            return AndroidSyncDiscoveryResult(
                peers = emptyList(),
                diagnosticCode = "PERMISSION_DENIED",
                diagnosticMessage = "Local network permission (ACCESS_LOCAL_NETWORK) is denied",
            )
        }

        if (nsdManager == null) {
            return AndroidSyncDiscoveryResult(
                peers = emptyList(),
                diagnosticCode = "PEER_UNREACHABLE",
                diagnosticMessage = "NsdManager is not available on this device",
            )
        }

        val currentGen = activeGeneration.incrementAndGet()

        return suspendCancellableCoroutine { continuation ->
            val peers = ConcurrentHashMap<String, AndroidSyncDiscoveredPeer>()
            val serviceNameToPeerKey = ConcurrentHashMap<String, String>()
            val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
            val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val isFinished = AtomicBoolean(false)
            var discoveryListener: NsdManager.DiscoveryListener? = null
            var resolveWorkerJob: Job? = null

            val finish = { code: String?, message: String? ->
                if (isFinished.compareAndSet(false, true)) {
                    discoveryListener?.let { listener ->
                        runCatching { nsdManager.stopServiceDiscovery(listener) }
                    }
                    resolveQueue.close()
                    resolveWorkerJob?.cancel()
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
            }

            // 修复 C20: 串行 resolve worker 配合代际检查，防止并发与过期 resolve 回调冲突
            resolveWorkerJob = workerScope.launch {
                for (serviceInfo in resolveQueue) {
                    if (isFinished.get() || activeGeneration.get() != currentGen) break
                    if (!resolveSingleServiceWithRetry(serviceInfo, peers, serviceNameToPeerKey, currentGen)) break
                }
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    // 修复 C21: 精准映射 NsdManager 错误码，杜绝将内部错误一律误报为 PERMISSION_DENIED
                    val code = when (errorCode) {
                        NsdManager.FAILURE_INTERNAL_ERROR -> "INTERNAL_ERROR"
                        NsdManager.FAILURE_ALREADY_ACTIVE -> "ALREADY_ACTIVE"
                        NsdManager.FAILURE_MAX_LIMIT -> "MAX_LIMIT_REACHED"
                        else -> "DISCOVERY_FAILED"
                    }
                    finish(code, "Android NSD discovery failed to start: code $errorCode ($code)")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (isFinished.get() || activeGeneration.get() != currentGen) return
                    val type = serviceInfo.serviceType.orEmpty().lowercase()
                    if (type.contains("_origread-sync._tcp")) {
                        resolveQueue.trySend(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    serviceNameToPeerKey.remove(serviceInfo.serviceName.orEmpty())?.let { key ->
                        if (serviceNameToPeerKey.values.none { it == key }) {
                            peers.remove(key)
                        }
                    }
                }
            }

            try {
                nsdManager.discoverServices("_origread-sync._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                mainHandler.postDelayed({
                    if (peers.isEmpty()) {
                        finish("DISCOVERY_EMPTY", "No OrigRead Sync peer was found on the local network")
                    } else {
                        finish(null, null)
                    }
                }, timeoutMs.coerceIn(300L, 10_000L))
            } catch (error: SecurityException) {
                finish("PERMISSION_DENIED", error.message ?: "Local network permission denied")
            } catch (error: Throwable) {
                finish("PEER_UNREACHABLE", error.message ?: "NSD discovery failed")
            }

            continuation.invokeOnCancellation {
                finish("CANCELLED", "Discovery cancelled")
            }
        }
    }

    private suspend fun resolveSingleServiceWithRetry(
        serviceInfo: NsdServiceInfo,
        peers: ConcurrentHashMap<String, AndroidSyncDiscoveredPeer>,
        serviceNameToPeerKey: ConcurrentHashMap<String, String>,
        generation: Int,
    ): Boolean {
        var attempts = 0
        while (attempts < 2) {
            attempts++
            when (resolveSingleService(serviceInfo, peers, serviceNameToPeerKey, generation)) {
                ResolveAttempt.SUCCESS -> return true
                ResolveAttempt.BLOCKED -> return false
                ResolveAttempt.FAILED -> Unit
            }
            if (activeGeneration.get() != generation) return false
            delay(120L)
        }
        return true
    }

    private suspend fun resolveSingleService(
        serviceInfo: NsdServiceInfo,
        peers: ConcurrentHashMap<String, AndroidSyncDiscoveredPeer>,
        serviceNameToPeerKey: ConcurrentHashMap<String, String>,
        generation: Int,
    ): ResolveAttempt {
        if (activeGeneration.get() != generation) return ResolveAttempt.BLOCKED
        val legacyResolver = !canStopServiceResolution()
        if (legacyResolver && !legacyResolveInFlight.compareAndSet(false, true)) {
            // Pre-stopServiceResolution Android exposes one process-global resolver slot.
            // Never start a second system resolve while a timed-out request may still own it.
            return ResolveAttempt.BLOCKED
        }
        val deferred = CompletableDeferred<ResolveAttempt>()
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (legacyResolver) legacyResolveInFlight.set(false)
                deferred.complete(
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) ResolveAttempt.BLOCKED
                    else ResolveAttempt.FAILED,
                )
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (legacyResolver) legacyResolveInFlight.set(false)
                // 修复 C20: 若代际已过，主动丢弃迟到回调
                if (activeGeneration.get() != generation) {
                    deferred.complete(ResolveAttempt.BLOCKED)
                    return
                }
                val host = info.host?.hostAddress ?: info.host?.hostName
                if (!host.isNullOrBlank() && info.port in 1..65_535) {
                    val attributes = info.attributes.mapValues { (_, value) -> value.toString(Charsets.UTF_8) }
                    val deviceId = attributes["deviceId"] ?: info.serviceName
                    val tls = attributes["tls"] == "1"
                    val key = "nsd:$deviceId"
                    val candidate = AndroidSyncDiscoveredPeer(
                        endpointId = key,
                        deviceId = deviceId,
                        displayName = attributes["name"] ?: info.serviceName,
                        host = host,
                        port = info.port,
                        tls = tls,
                        syncSpaceIds = attributes["space"]?.split(',')?.filter(String::isNotBlank).orEmpty(),
                        fingerprint = attributes["fingerprint"],
                    )
                    peers.compute(key) { _, existing ->
                        if (existing == null || preferHost(candidate.host, existing.host)) candidate else existing
                    }
                    val serviceName = info.serviceName.orEmpty()
                    val previousKey = serviceNameToPeerKey.put(serviceName, key)
                    if (previousKey != null && previousKey != key && serviceNameToPeerKey.values.none { it == previousKey }) {
                        peers.remove(previousKey)
                    }
                }
                deferred.complete(ResolveAttempt.SUCCESS)
            }
        }

        return try {
            nsdManager?.resolveService(serviceInfo, listener)
            val result = withTimeoutOrNull(1_800L) {
                deferred.await()
            }
            if (result != null) {
                result
            } else {
                if (canStopServiceResolution()) {
                    runCatching { nsdManager?.stopServiceResolution(listener) }
                }
                // Legacy Android has no cancellation API. Keep legacyResolveInFlight=true until
                // the real callback arrives, so a later scan cannot overlap the still-live
                // platform resolver and trigger FAILURE_ALREADY_ACTIVE.
                ResolveAttempt.BLOCKED
            }
        } catch (error: CancellationException) {
            if (!legacyResolver) {
                runCatching { nsdManager?.stopServiceResolution(listener) }
            }
            // Legacy Android has no resolver cancellation API. Do not clear the in-flight guard:
            // the platform callback must release it when the still-live resolver actually ends.
            throw error
        } catch (_: Throwable) {
            if (!legacyResolver) {
                runCatching { nsdManager?.stopServiceResolution(listener) }
            }
            if (legacyResolver) legacyResolveInFlight.set(false)
            ResolveAttempt.FAILED
        }
    }

    private fun preferHost(candidate: String, current: String): Boolean {
        fun rank(host: String): Int =
            when {
                Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(host.substringBefore('%')) -> 0
                !host.contains(':') -> 1
                host.substringBefore('%').lowercase().startsWith("fe80:") -> 3
                else -> 2
            }
        val candidateRank = rank(candidate)
        val currentRank = rank(current)
        return candidateRank < currentRank ||
            (candidateRank == currentRank && candidate < current)
    }
}
