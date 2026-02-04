package com.example.ctrl.security

import java.net.Inet4Address
import java.net.InetAddress

sealed interface AllowRule {
    fun matches(ipv4: Int): Boolean
}

data class ExactIpv4Rule(val ipv4: Int) : AllowRule {
    override fun matches(ipv4: Int): Boolean = this.ipv4 == ipv4
}

data class CidrIpv4Rule(val network: Int, val prefix: Int) : AllowRule {
    private val mask: Int = if (prefix == 0) 0 else (-1 shl (32 - prefix))

    override fun matches(ipv4: Int): Boolean = (ipv4 and mask) == (network and mask)
}

object Allowlist {
    private val ipv4Literal = Regex("^([0-9]{1,3})(\\.([0-9]{1,3})){3}$")

    fun parseEntry(raw: String): AllowRule {
        val s = raw.trim()
        require(s.isNotEmpty()) { "Empty entry" }

        val parts = s.split("/", limit = 2)
        return if (parts.size == 1) {
            ExactIpv4Rule(parseIpv4(parts[0]))
        } else {
            val ip = parseIpv4(parts[0])
            val prefix = parts[1].toIntOrNull() ?: error("Invalid CIDR prefix")
            require(prefix in 0..32) { "CIDR prefix out of range" }
            CidrIpv4Rule(ip, prefix)
        }
    }

    fun normalizeEntry(raw: String): String {
        val s = raw.trim()
        val parts = s.split("/", limit = 2)
        val ip = ipv4ToString(parseIpv4(parts[0]))
        return if (parts.size == 1) ip else "$ip/${parts[1].trim()}"
    }

    fun parseRemoteIpv4(remoteHost: String): Int? {
        val s = remoteHost.trim()
        if (ipv4Literal.matches(s)) {
            return runCatching { parseIpv4(s) }.getOrNull()
        }

        // Avoid DNS lookups; only accept literal or numeric IP strings.
        val addr = runCatching { InetAddress.getByName(s) }.getOrNull() ?: return null
        val v4 = addr as? Inet4Address ?: return null
        return bytesToIpv4(v4.address)
    }

    fun parseIpv4(raw: String): Int {
        val s = raw.trim()
        require(ipv4Literal.matches(s)) { "Invalid IPv4 address" }
        val octets = s.split('.')
        require(octets.size == 4) { "Invalid IPv4 address" }
        val b0 = octets[0].toIntOrNull() ?: error("Invalid IPv4 address")
        val b1 = octets[1].toIntOrNull() ?: error("Invalid IPv4 address")
        val b2 = octets[2].toIntOrNull() ?: error("Invalid IPv4 address")
        val b3 = octets[3].toIntOrNull() ?: error("Invalid IPv4 address")
        require(b0 in 0..255 && b1 in 0..255 && b2 in 0..255 && b3 in 0..255) { "Invalid IPv4 address" }
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    fun ipv4ToString(ipv4: Int): String {
        val b0 = (ipv4 ushr 24) and 0xFF
        val b1 = (ipv4 ushr 16) and 0xFF
        val b2 = (ipv4 ushr 8) and 0xFF
        val b3 = ipv4 and 0xFF
        return "$b0.$b1.$b2.$b3"
    }

    private fun bytesToIpv4(bytes: ByteArray): Int {
        require(bytes.size == 4)
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }
}
