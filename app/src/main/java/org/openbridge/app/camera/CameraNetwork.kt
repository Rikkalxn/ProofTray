package org.openbridge.app.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiNetworkSpecifier
import java.net.Inet4Address
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class CameraEndpoint(val name: String, val host: String, val port: Int = 15740)

@Suppress("DEPRECATION")
internal class CameraNetwork(context: Context, scope: CoroutineScope) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    @Volatile private var requested: ConnectivityManager.NetworkCallback? = null
    private val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
    private val mutableEndpoints = MutableStateFlow<List<CameraEndpoint>>(emptyList())
    val endpoints = mutableEndpoints.asStateFlow()
    private val toResolve = Channel<NsdServiceInfo>(32)

    init {
        scope.launch {
            for (service in toResolve) {
                runCatching {
                    val result = CompletableDeferred<NsdServiceInfo>()
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) { result.completeExceptionally(Exception("mDNS 解析失败 $code")) }
                        override fun onServiceResolved(info: NsdServiceInfo) { result.complete(info) }
                    })
                    val info = withTimeout(8000) { result.await() }
                    val host = info.host?.hostAddress ?: return@runCatching
                    val endpoint = CameraEndpoint(info.serviceName, host, info.port)
                    mutableEndpoints.update { (it.filterNot { old -> old.host == host && old.port == info.port } + endpoint).takeLast(30) }
                }
            }
        }
    }

    fun wifi(): Network = manager.allNetworks.firstOrNull { network ->
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: error("手机尚未连接 Wi‑Fi；AP 模式请先连接相机热点，STA 模式请与相机处于同一局域网")

    fun gateway(network: Network): String = manager.getLinkProperties(network)?.routes
        ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: "192.168.1.1"

    suspend fun requestAp(ssid: String, password: String): Network {
        release()
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        val available = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available.complete(network) }
            override fun onUnavailable() { available.completeExceptionally(IllegalStateException("未能连接相机热点，或系统连接请求已取消")) }
        }
        requested = callback
        try {
            manager.requestNetwork(request, callback, 45000)
            return withTimeout(50000) { available.await() }
        } catch (error: Exception) {
            runCatching { manager.unregisterNetworkCallback(callback) }
            if (requested === callback) requested = null
            throw error
        }
    }

    fun discover() {
        stopDiscovery()
        mutableEndpoints.value = emptyList()
        for (type in listOf("_ptp._tcp.", "_nikon._tcp.")) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onServiceFound(info: NsdServiceInfo) { toResolve.trySend(info) }
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onStartDiscoveryFailed(type: String, error: Int) = Unit
                override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            }
            discoveries += listener
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stopDiscovery() {
        discoveries.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveries.clear()
    }

    fun release() {
        requested?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        requested = null
    }
}
