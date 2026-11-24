package org.prooftray.app.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class CameraEndpoint(val name: String, val host: String, val port: Int = 15740, val make: CameraMake = CameraMake.OTHER)

@Suppress("DEPRECATION")
internal class CameraNetwork(context: Context, private val scope: CoroutineScope) {
    private data class Discovery(val generation: Int, val service: NsdServiceInfo)
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    @Volatile private var requested: ConnectivityManager.NetworkCallback? = null
    @Volatile private var watched: ConnectivityManager.NetworkCallback? = null
    @Volatile private var discoveryGeneration = 0
    private var discoveryJob: Job? = null
    private var sonyDiscovery: Job? = null
    private val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
    private val mutableEndpoints = MutableStateFlow<List<CameraEndpoint>>(emptyList())
    val endpoints = mutableEndpoints.asStateFlow()
    private val toResolve = Channel<Discovery>(32)

    init {
        scope.launch {
            for (candidate in toResolve) {
                if (candidate.generation != discoveryGeneration) continue
                runCatching {
                    val result = CompletableDeferred<NsdServiceInfo>()
                    nsd.resolveService(candidate.service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) { result.completeExceptionally(IOException("mDNS 解析失败 $code")) }
                        override fun onServiceResolved(info: NsdServiceInfo) { result.complete(info) }
                    })
                    val info = withTimeoutOrNull(8000) { result.await() } ?: return@runCatching
                    if (candidate.generation != discoveryGeneration) return@runCatching
                    val host = info.host?.hostAddress ?: return@runCatching
                    if (info.port !in 1..65535) return@runCatching
                    addEndpoint(candidate.generation, CameraEndpoint(info.serviceName, host, info.port))
                }
            }
        }
    }

    fun wifi(): Network {
        val candidates = manager.allNetworks.filter {
            val capabilities = manager.getNetworkCapabilities(it)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        manager.activeNetwork?.takeIf { it in candidates }?.let { return it }
        return candidates.singleOrNull() ?: error(if (candidates.isEmpty()) {
            "手机尚未连接 Wi-Fi；请连接相机热点，或让手机与相机进入同一局域网"
        } else "手机存在多个 Wi-Fi 网络，请使用自动连接相机热点入口")
    }

    suspend fun awaitAddress(network: Network) {
        withTimeoutOrNull(6000) {
            while (true) {
                if (manager.getLinkProperties(network)?.linkAddresses?.any { it.address is Inet4Address } == true) return@withTimeoutOrNull true
                delay(100)
            }
        } ?: throw IOException("Wi-Fi 已响应，但尚未分配 IPv4 地址，请重试")
    }

    suspend fun hotspotAddress(network: Network, preferDhcp: Boolean): String {
        repeat(24) {
            val props = manager.getLinkProperties(network)
            if (props != null) {
                val dhcp = when {
                    !preferDhcp -> null
                    Build.VERSION.SDK_INT >= 30 -> props.dhcpServerAddress
                    else -> matchingDhcpServer(props)
                }
                val gateway = props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
                listOfNotNull(dhcp, gateway).firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress }
                    ?.hostAddress?.let { return it }
            }
            delay(200)
        }
        throw IOException("已连上热点，但尚未取得机身地址，请重试或在手动连接中填写相机 IP")
    }

    private fun matchingDhcpServer(props: LinkProperties): InetAddress? {
        // Android 10 exposes a global lease. Use it only when its client address is on this particular Network.
        val lease = wifiManager?.dhcpInfo ?: return null
        fun ipv4(value: Int): InetAddress = InetAddress.getByAddress(ByteArray(4) { (value ushr (8 * it)).toByte() })
        return if (lease.serverAddress != 0 && props.linkAddresses.any { it.address == ipv4(lease.ipAddress) }) ipv4(lease.serverAddress) else null
    }

    fun hotspotSecurity(ssid: String, bssid: String?): CameraWifiSecurity {
        val scan = runCatching { wifiManager?.scanResults }.getOrNull()
            ?.firstOrNull { it.SSID == ssid && (bssid == null || it.BSSID.equals(bssid, true)) }
        val capabilities = scan?.capabilities.orEmpty()
        return when {
            "SAE" in capabilities && "PSK" in capabilities -> CameraWifiSecurity.WPA2_WPA3
            "SAE" in capabilities -> CameraWifiSecurity.WPA3
            else -> CameraWifiSecurity.WPA2
        }
    }

    fun checkApSecurity(security: CameraWifiSecurity) {
        val wifi = checkNotNull(wifiManager) { "手机未提供 Wi-Fi 服务" }
        check(wifi.isWifiEnabled) { "请先开启手机 Wi-Fi，再连接相机热点" }
        val requiresSae = security == CameraWifiSecurity.WPA3 || (security == CameraWifiSecurity.WPA2_WPA3 && Build.VERSION.SDK_INT == 29)
        check(!requiresSae || wifi.isWpa3SaeSupported) { "手机不支持该热点的加密方式，请在机身选择 WPA2 后重试" }
    }

    suspend fun requestAp(ssid: String, password: String, security: CameraWifiSecurity,
                          onNetworkLost: (Throwable) -> Unit, bssid: String? = null): Network {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        checkApSecurity(security)
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            bssid?.let { setBssid(MacAddress.fromString(it)) }
            when (security) {
                CameraWifiSecurity.OPEN -> Unit
                CameraWifiSecurity.WPA2 -> setWpa2Passphrase(password)
                CameraWifiSecurity.WPA3 -> setWpa3Passphrase(password)
                CameraWifiSecurity.WPA2_WPA3 -> {
                    if (wifiManager?.isWpa3SaeSupported == true) setWpa3Passphrase(password) else setWpa2Passphrase(password)
                }
            }
        }.build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        val available = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (requested === this) available.complete(network) }
            override fun onUnavailable() {
                if (requested === this) available.completeExceptionally(IOException("相机热点连接未获准或未出现，可重试或手动连接"))
            }
            override fun onLost(network: Network) {
                if (requested !== this) return
                val error = IOException("相机 Wi-Fi 网络已断开")
                if (!available.completeExceptionally(error)) onNetworkLost(error)
            }
        }
        try {
            synchronized(this) {
                caller.ensureActive()
                check(requested == null) { "上一次系统热点连接仍在进行" }
                requested = callback
                manager.requestNetwork(request, callback)
            }
            return withTimeoutOrNull(180_000) { available.await() } ?: throw IOException("等待 Android 连接机身热点超时，请在前台重试")
        } catch (error: Exception) {
            synchronized(this) {
                if (requested === callback) requested = null
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
            if (error is SecurityException) throw IOException("Android 未允许连接热点，请在应用设置中允许附近设备权限后重试", error)
            throw error
        }
    }

    @Synchronized
    fun watch(network: Network, onNetworkLost: (Throwable) -> Unit) {
        watched?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (watched === this && lost == network) onNetworkLost(IOException("所选 Wi-Fi 网络已断开"))
            }
        }
        watched = callback
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
    }

    suspend fun findPairedSony(id: UUID): Pair<Network, String>? {
        val route = runCatching { wifi() }.getOrNull() ?: return null
        var match: String? = null
        try {
            discoverSonyCameras(route) { host, udn ->
                if (udn == id) match = host
                udn == id
            }
        } catch (_: IOException) { return null } catch (_: SecurityException) { return null }
        return match?.let { route to it }
    }

    @Synchronized
    private fun addEndpoint(generation: Int, endpoint: CameraEndpoint) {
        if (generation != discoveryGeneration) return
        mutableEndpoints.update { items ->
            val previous = items.firstOrNull { it.host == endpoint.host && it.port == endpoint.port }
            val merged = if (previous == null) endpoint else endpoint.copy(
                name = if (endpoint.make == CameraMake.SONY) previous.name else endpoint.name,
                make = if (endpoint.make == CameraMake.OTHER) previous.make else endpoint.make
            )
            (items.filterNot { it == previous } + merged).takeLast(30)
        }
    }

    @Synchronized
    fun discover() {
        stopDiscovery()
        mutableEndpoints.value = emptyList()
        val generation = discoveryGeneration
        sonyDiscovery = scope.launch {
            try {
                discoverSonyCameras(wifi()) { host, _ ->
                    addEndpoint(generation, CameraEndpoint("Sony α", host, make = CameraMake.SONY))
                    false
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: IOException) { /* The manual address field remains available on networks blocking SSDP. */ }
            catch (_: IllegalStateException) { /* No unique Wi-Fi network selected. */ }
            catch (_: SecurityException) { /* Local network discovery is unavailable. */ }
        }
        for (type in listOf("_ptp._tcp.", "_nikon._tcp.")) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (generation == discoveryGeneration) toResolve.trySend(Discovery(generation, info))
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    if (generation == discoveryGeneration) mutableEndpoints.update { it.filterNot { item -> item.name == info.serviceName } }
                }
                override fun onStartDiscoveryFailed(type: String, error: Int) = Unit
                override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            }
            discoveries += listener
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        discoveryJob = scope.launch { delay(15000); synchronized(this@CameraNetwork) { if (generation == discoveryGeneration) stopDiscovery() } }
    }

    @Synchronized
    fun stopDiscovery() {
        discoveryGeneration++
        sonyDiscovery?.cancel()
        sonyDiscovery = null
        discoveryJob?.cancel()
        discoveryJob = null
        discoveries.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveries.clear()
    }

    @Synchronized
    fun release() {
        val oldRequest = requested
        val oldWatch = watched
        requested = null
        watched = null
        oldRequest?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        oldWatch?.let { runCatching { manager.unregisterNetworkCallback(it) } }
    }
}
