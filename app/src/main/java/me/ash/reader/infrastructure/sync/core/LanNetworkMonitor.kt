package me.ash.reader.infrastructure.sync.core

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.PermissionChecker
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * 局域网网络诊断与环境状态。
 *
 * @property hasLocalNetworkPermission 是否具备局域网访问权限（Android 17+ ACCESS_LOCAL_NETWORK）
 * @property networkType 当前网络类型：WIFI, ETHERNET, CELLULAR, VPN, NONE
 * @property isWifiOrEthernet 当前是否连接了 Wi-Fi 或以太网
 * @property isVpnActive 是否激活了 VPN
 * @property isLocalNetworkAvailable 局域网传输是否可用
 * @property ipv4Addresses 本机活跃局域网 IPv4 地址列表
 * @property ipv6Addresses 本机活跃局域网 IPv6 地址列表
 * @property selectedInterface 选中的主网络接口名称
 * @property diagnosticMessage 诊断说明或告警
 */
data class LanNetworkDiagnostics(
    val hasLocalNetworkPermission: Boolean = true,
    val networkType: String = "NONE",
    val isWifiOrEthernet: Boolean = false,
    val isVpnActive: Boolean = false,
    val isLocalNetworkAvailable: Boolean = false,
    val ipv4Addresses: List<String> = emptyList(),
    val ipv6Addresses: List<String> = emptyList(),
    val selectedInterface: String? = null,
    val diagnosticMessage: String? = null,
)

/**
 * 局域网网络生命周期与环境状态监听器。
 *
 * 遵循 R11-B 规范：
 * 1. 监听 ConnectivityManager 与 NetworkCallback，感知网络切换（Wi-Fi ↔ Mobile）、VPN 启闭及网络断开；
 * 2. 检查 Android 17+ ACCESS_LOCAL_NETWORK 运行时权限；
 * 3. 当离开局域网时主动触发状态变更与 Peer 清理，避免离线后仍虚假展示在线。
 */
@Singleton
class LanNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _diagnostics = MutableStateFlow(refreshDiagnostics())
    val diagnostics: StateFlow<LanNetworkDiagnostics> = _diagnostics.asStateFlow()
    private val _appForeground = MutableStateFlow(sampleAppForeground())
    val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val processObserver =
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _appForeground.value = true
                scope.launch { updateDiagnostics() }
            }

            override fun onStop(owner: LifecycleOwner) {
                _appForeground.value = false
                scope.launch { updateDiagnostics() }
            }
        }

    init {
        val processLifecycle = ProcessLifecycleOwner.get().lifecycle
        _appForeground.value = sampleAppForeground()
        processLifecycle.addObserver(processObserver)
        startMonitoring()
    }

    private fun sampleAppForeground(): Boolean {
        val lifecycleForeground =
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (lifecycleForeground) return true

        val processInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        return processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /**
     * 检查当前应用是否拥有局域网访问权限。
     * Android 17 的旧 target 通常隐式授权，但用户撤销或历史错误声明仍可能留下 AppOps 拒绝状态。
     * 同时检查权限与 AppOps；低于 API 37 没有这项权限，保持既有系统行为。
     */
    fun checkLocalNetworkPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 37) {
            return true
        }
        val permissionName = "android.permission.ACCESS_LOCAL_NETWORK"
        return try {
            PermissionChecker.checkSelfPermission(context, permissionName) == PermissionChecker.PERMISSION_GRANTED
        } catch (failure: Throwable) {
            // 系统权限检查异常明确记录，避免把检查失败伪装为已授权。
            android.util.Log.e("OrigReadSync", "Local network permission check failed", failure)
            false
        }
    }

    /**
     * 开始监听网络状态变更。
     */
    fun startMonitoring() {
        if (connectivityManager == null || networkCallback != null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { updateDiagnostics() }
            }

            override fun onLost(network: Network) {
                scope.launch { updateDiagnostics() }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scope.launch { updateDiagnostics() }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                scope.launch { updateDiagnostics() }
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Throwable) {
            // 降级使用普通 registerDefaultNetworkCallback
            try {
                connectivityManager.registerDefaultNetworkCallback(callback)
                networkCallback = callback
            } catch (_: Throwable) {
                // 忽略非致命注册异常
            }
        }
    }

    /**
     * 停止网络监听。
     */
    fun stopMonitoring() {
        networkCallback?.let {
            runCatching { connectivityManager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }

    /**
     * 异步更新网络诊断。
     */
    suspend fun updateDiagnostics(): LanNetworkDiagnostics {
        // The monitor can be first instantiated after ProcessLifecycleOwner has already crossed
        // STARTED, so relying only on observer callbacks can leave the cached foreground bit
        // stale at false. Re-sample the authoritative lifecycle state whenever LAN diagnostics
        // are refreshed (toggle, permission return, network change).
        _appForeground.value = sampleAppForeground()
        val diag = refreshDiagnostics()
        _diagnostics.value = diag
        return diag
    }

    fun currentLanNetwork(): Network? {
        val manager = connectivityManager ?: return null
        val candidates = lanNetworks(manager)
        val active = manager.activeNetwork
        return candidates.firstOrNull { it == active } ?: candidates.firstOrNull()
    }

    fun currentLanNetworkForHost(host: String): Network? {
        val manager = connectivityManager ?: return null
        val candidates = lanNetworks(manager)
        val normalized = host.trim().removePrefix("[").removeSuffix("]").replace("%25", "%")
        val addressPart = normalized.substringBefore('%')
        val literal = when {
            IPV4_LITERAL.matches(addressPart) -> runCatching { InetAddress.getByName(addressPart) }.getOrNull()
            addressPart.contains(':') -> runCatching { InetAddress.getByName(addressPart) }.getOrNull()
            else -> null
        }
        if (literal is Inet4Address) {
            candidates.firstOrNull { network ->
                manager.getLinkProperties(network)?.linkAddresses.orEmpty().any { local ->
                    val localAddress = local.address
                    localAddress is Inet4Address && sameIpv4Subnet(localAddress.address, literal.address, local.prefixLength)
                }
            }?.let { return it }
        }
        if (literal is Inet6Address && literal.isLinkLocalAddress) {
            val zone = normalized.substringAfter('%', "")
            candidates.firstOrNull { network ->
                val props = manager.getLinkProperties(network) ?: return@firstOrNull false
                val zoneMatches = zone.isBlank() || props.interfaceName == zone ||
                    props.linkAddresses.any { link ->
                        val address = link.address as? Inet6Address ?: return@any false
                        address.scopeId > 0 && address.scopeId.toString() == zone
                    }
                zoneMatches && props.linkAddresses.any { (it.address as? Inet6Address)?.isLinkLocalAddress == true }
            }?.let { return it }
        }
        return currentLanNetwork()
    }

    fun bindLanClient(baseClient: OkHttpClient, remoteHost: String? = null): OkHttpClient {
        val network = remoteHost?.let(::currentLanNetworkForHost) ?: currentLanNetwork() ?: return baseClient
        return baseClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns { hostname -> network.getAllByName(hostname).toList() }
            .build()
    }

    /**
     * 刷新并计算当前网络环境与诊断信息。
     */
    fun refreshDiagnostics(): LanNetworkDiagnostics {
        val hasPermission = checkLocalNetworkPermission()
        val activeNetwork = connectivityManager?.activeNetwork
        val activeCaps = connectivityManager?.getNetworkCapabilities(activeNetwork)
        val allNetworks = runCatching { connectivityManager?.allNetworks?.toList().orEmpty() }.getOrDefault(emptyList())
        val lanNetworks = connectivityManager?.let(::lanNetworks).orEmpty()
        val lanNetwork = currentLanNetwork()
        val lanCaps = connectivityManager?.getNetworkCapabilities(lanNetwork)
        val lanLinkProperties = connectivityManager?.getLinkProperties(lanNetwork)

        val isVpn =
            allNetworks.any { network ->
                connectivityManager?.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } || activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val isWifi = lanCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = lanCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val isCellular = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        val netType = when {
            isWifi -> "WIFI"
            isEthernet -> "ETHERNET"
            isCellular -> "CELLULAR"
            isVpn -> "VPN"
            activeCaps != null -> "OTHER"
            else -> "NONE"
        }

        val isWifiOrEth = isWifi || isEthernet

        val ipv4List = linkedSetOf<String>()
        val ipv6List = linkedSetOf<String>()
        val primaryInterface = lanLinkProperties?.interfaceName
        lanNetworks.forEach { network ->
            connectivityManager?.getLinkProperties(network)?.linkAddresses.orEmpty().forEach { linkAddress ->
                val addr = linkAddress.address
                if (addr.isLoopbackAddress) return@forEach
                when (addr) {
                    is Inet4Address -> addr.hostAddress?.let(ipv4List::add)
                    is Inet6Address -> {
                        addr.hostAddress?.let(ipv6List::add)
                    }
                }
            }
        }

        val isLocalAvailable = hasPermission && isWifiOrEth && (ipv4List.isNotEmpty() || ipv6List.isNotEmpty())

        val diagMessage = when {
            !hasPermission -> "Local network permission (ACCESS_LOCAL_NETWORK) is denied"
            !isWifiOrEth && !isVpn -> "Not connected to Wi-Fi or Ethernet. LAN sync is unavailable"
            ipv4List.isEmpty() && ipv6List.isEmpty() -> "No valid local network address found"
            isVpn -> "VPN is active; multicast mDNS discovery may be isolated by VPN routing"
            else -> null
        }

        return LanNetworkDiagnostics(
            hasLocalNetworkPermission = hasPermission,
            networkType = netType,
            isWifiOrEthernet = isWifiOrEth,
            isVpnActive = isVpn,
            isLocalNetworkAvailable = isLocalAvailable,
            ipv4Addresses = ipv4List.toList().sorted(),
            ipv6Addresses = ipv6List.toList().sorted(),
            selectedInterface = primaryInterface,
            diagnosticMessage = diagMessage,
        )
    }

    private fun lanNetworks(manager: ConnectivityManager): List<Network> =
        runCatching {
            manager.allNetworks
                .filter { network ->
                    val caps = manager.getNetworkCapabilities(network) ?: return@filter false
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                }
                .sortedWith(
                    compareBy<Network> { network ->
                        val caps = manager.getNetworkCapabilities(network)
                        when {
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 0
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 1
                            else -> 2
                        }
                    }.thenBy { network -> manager.getLinkProperties(network)?.interfaceName.orEmpty() }
                        .thenBy { it.toString() }
                )
        }.getOrDefault(emptyList())

    private fun sameIpv4Subnet(local: ByteArray, remote: ByteArray, prefixLength: Int): Boolean {
        if (local.size != 4 || remote.size != 4 || prefixLength !in 0..32) return false
        var bits = prefixLength
        for (index in local.indices) {
            val take = minOf(8, bits)
            if (take == 0) return true
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((local[index].toInt() and mask) != (remote[index].toInt() and mask)) return false
            bits -= take
        }
        return true
    }

    private companion object {
        val IPV4_LITERAL = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    }
}
