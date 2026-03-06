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

internal fun interface SecureServerAddressProvider {
    fun resolve(host: String, port: Int, ipMode: IpMode): ResolvedServerAddress?
}

internal val defaultSecureServerAddressProvider = SecureServerAddressProvider { host, port, ipMode ->
    GoCoreClient.resolveServerAddress(host, port, ipMode)
}

internal object ServerAddressResolverRuntime {
    @Volatile
    var secureProvider: SecureServerAddressProvider = defaultSecureServerAddressProvider
}

object ServerAddressResolver {
    fun resolve(node: NodeConfig, ipMode: IpMode = node.ipMode): ResolvedServerAddress {
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

        ServerAddressResolverRuntime.secureProvider.resolve(host, port, ipMode)?.let { secure ->
            return secure
        }

        // Compatibility fallback for older sudoku.aar builds that do not expose
        // the secure resolver entrypoint yet.
        val addresses = InetAddress.getAllByName(host).toList()
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        val ipv6 = addresses.filterIsInstance<Inet6Address>()

        val picked = when (ipMode) {
            // Keep "Default" aligned with what most users expect from other clients:
            // prefer IPv4 when available, otherwise fall back to IPv6.
            IpMode.DEFAULT -> ipv4.firstOrNull() ?: ipv6.firstOrNull()
            IpMode.IPV4_ONLY -> ipv4.firstOrNull()
            IpMode.IPV6_PREFERRED -> ipv6.firstOrNull() ?: ipv4.firstOrNull()
        }

        val selected = picked ?: throw IllegalStateException(
            when (ipMode) {
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
