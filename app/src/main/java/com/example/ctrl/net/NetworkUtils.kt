package com.example.ctrl.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

object NetworkUtils {
    private const val CACHE_TTL_MS: Long = 5_000
    private val lastFetchMs = AtomicLong(0)
    @Volatile
    private var cachedLocalIpv4: Set<String> = emptySet()

    fun localIpv4Addresses(): Set<String> {
        val now = System.currentTimeMillis()
        val last = lastFetchMs.get()
        if (now - last < CACHE_TTL_MS) return cachedLocalIpv4
        if (!lastFetchMs.compareAndSet(last, now)) return cachedLocalIpv4

        val ips = mutableSetOf<String>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptySet()
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                val v4 = addr as? Inet4Address ?: continue
                val host = v4.hostAddress ?: continue
                if (host.isNotBlank()) ips.add(host)
            }
        }
        ips.add("127.0.0.1")

        cachedLocalIpv4 = ips
        return cachedLocalIpv4
    }

    fun isValidHostHeader(hostHeader: String?, port: Int): Boolean {
        if (hostHeader.isNullOrBlank()) return true
        val s = hostHeader.trim()

        // Ktor may include the port, e.g. "192.168.1.10:8787".
        val hostPart = s.substringBefore(":").trim()
        val portPart = s.substringAfter(":", missingDelimiterValue = "").trim()
        if (portPart.isNotEmpty()) {
            val parsed = portPart.toIntOrNull() ?: return false
            if (parsed != port) return false
        }

        return localIpv4Addresses().contains(hostPart)
    }
}
