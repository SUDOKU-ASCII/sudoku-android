package com.futaiii.sudodroid.net

import com.futaiii.sudodroid.data.IpMode
import com.futaiii.sudodroid.data.NodeConfig
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

data class ResolvedServerAddress(
    val host: String,
    val port: Int,
    val serverAddress: String,
    val sniHost: String?
)

object ServerAddressResolver {
    fun resolve(node: NodeConfig): ResolvedServerAddress {
        val host = stripHost(node.host)
        val port = node.port
        val isLiteral = isIpv4Literal(host) || isIpv6Literal(host)

        if (isLiteral) {
            return ResolvedServerAddress(
                host = host,
                port = port,
                serverAddress = joinHostPort(host, port),
                sniHost = null
            )
        }

        val addresses = InetAddress.getAllByName(host).toList()
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        val ipv6 = addresses.filterIsInstance<Inet6Address>()

        val picked = when (node.ipMode) {
            // Keep "Default" aligned with what most users expect from other clients:
            // prefer IPv4 when available, otherwise fall back to IPv6.
            IpMode.DEFAULT -> ipv4.firstOrNull() ?: ipv6.firstOrNull()
            IpMode.IPV4_ONLY -> ipv4.firstOrNull()
            IpMode.IPV6_PREFERRED -> ipv6.firstOrNull() ?: ipv4.firstOrNull()
        }

        val selected = picked ?: throw IllegalStateException(
            when (node.ipMode) {
                IpMode.DEFAULT -> "No IPv4/IPv6 address found for $host"
                IpMode.IPV4_ONLY -> "No IPv4 address found for $host"
                IpMode.IPV6_PREFERRED -> "No IPv4/IPv6 address found for $host"
            }
        )

        val resolvedHost = selected.hostAddress
        return ResolvedServerAddress(
            host = resolvedHost,
            port = port,
            serverAddress = joinHostPort(resolvedHost, port),
            sniHost = host
        )
    }
}

private fun stripHost(host: String): String {
    return host.trim().removeSurrounding("[", "]")
}

private fun joinHostPort(host: String, port: Int): String {
    return if (host.contains(":")) {
        "[$host]:$port"
    } else {
        "$host:$port"
    }
}

private fun isIpv4Literal(host: String): Boolean {
    val parts = host.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val value = part.toIntOrNull() ?: return false
        value in 0..255 && part == value.toString()
    }
}

private fun isIpv6Literal(host: String): Boolean {
    return host.contains(":")
}
