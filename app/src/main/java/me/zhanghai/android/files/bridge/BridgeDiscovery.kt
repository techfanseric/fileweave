package me.zhanghai.android.files.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** Bonjour TXT records are untrusted hints; TLS pinning still authenticates every connection. */
class BridgeDiscovery(context: Context, private val found: (MacEndpoint) -> Unit,
                      private val lost: (String) -> Unit, private val failed: () -> Unit) {
    data class MacEndpoint(val name: String, val host: String, val port: Int, val pin: String) {
        fun config(token: String) = BridgeConfig(host, port, pin, token)
    }
    private val manager = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val seen = mutableSetOf<String>()
    private var closed = false
    private var resolving: NsdManager.ResolveListener? = null
    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { if (!closed) { failed(); stop() } } }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onServiceFound(service: NsdServiceInfo) { main.post {
            if (!closed && seen.size < 32 && seen.add(service.serviceName)) { queue.addLast(service); resolveNext() }
        } }
        override fun onServiceLost(service: NsdServiceInfo) { main.post {
            if (!closed) { seen.remove(service.serviceName); queue.removeAll { it.serviceName == service.serviceName }; lost(service.serviceName) }
        } }
    }
    fun start() {
        try { manager.discoverServices("_fileweave._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (_: RuntimeException) { failed(); stop() }
    }
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (closed || resolving != null || queue.isEmpty()) return
        val service = queue.removeFirst()
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { main.post {
                resolving = null
                if (!closed) { seen.remove(info.serviceName); resolveNext() }
            } }
            override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                resolving = null
                if (!closed && seen.contains(info.serviceName)) {
                    val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                    // The current Mac server listens on IPv4; do not pick a VPN/link-local IPv6 address.
                    val host = addresses.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.hostAddress
                    val pin = info.attributes["pin"]?.toString(Charsets.UTF_8)
                    val version = info.attributes["v"]?.toString(Charsets.UTF_8)
                    if (host != null && pin != null && pin.matches(Regex("[a-f0-9]{64}")) && version == "1" && info.port in 1..65535)
                        found(MacEndpoint(info.serviceName, host, info.port, pin))
                    resolveNext()
                }
            } }
        }
        resolving = callback
        try { manager.resolveService(service, callback) }
        catch (_: RuntimeException) { resolving = null; resolveNext() }
    }
    fun stop() {
        closed = true
        queue.clear()
        try { manager.stopServiceDiscovery(discovery) } catch (_: RuntimeException) { }
        if (Build.VERSION.SDK_INT >= 34) resolving?.let {
            try { manager.stopServiceResolution(it) } catch (_: RuntimeException) { }
        }
        resolving = null
    }
}
