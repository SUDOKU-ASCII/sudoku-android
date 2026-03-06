package com.futaiii.sudodroid.net

import com.futaiii.sudodroid.data.IpMode
import com.futaiii.sudodroid.data.NodeConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressResolverTest {
    @After
    fun tearDown() {
        ServerAddressResolverRuntime.secureProvider = defaultSecureServerAddressProvider
    }

    @Test
    fun `hostname resolution prefers secure provider when available`() {
        var called = false
        ServerAddressResolverRuntime.secureProvider = SecureServerAddressProvider { host, port, ipMode ->
            called = true
            assertEquals("example.com", host)
            assertEquals(443, port)
            assertEquals(IpMode.DEFAULT, ipMode)
            ResolvedServerAddress(
                host = "1.2.3.4",
                port = port,
                serverAddress = "1.2.3.4:$port",
                sniHost = host
            )
        }

        val resolved = ServerAddressResolver.resolve(
            NodeConfig(host = "example.com", port = 443),
            IpMode.DEFAULT
        )

        assertTrue(called)
        assertEquals("1.2.3.4", resolved.host)
        assertEquals("1.2.3.4:443", resolved.serverAddress)
        assertEquals("example.com", resolved.sniHost)
    }

    @Test
    fun `ip literals bypass secure provider`() {
        var called = false
        ServerAddressResolverRuntime.secureProvider = SecureServerAddressProvider { _, _, _ ->
            called = true
            null
        }

        val resolved = ServerAddressResolver.resolve(
            NodeConfig(host = "8.8.8.8", port = 53),
            IpMode.DEFAULT
        )

        assertEquals(false, called)
        assertEquals("8.8.8.8", resolved.host)
        assertEquals("8.8.8.8:53", resolved.serverAddress)
        assertNull(resolved.sniHost)
    }

    @Test
    fun `resolver falls back when secure provider throws`() {
        ServerAddressResolverRuntime.secureProvider = SecureServerAddressProvider { _, _, _ ->
            error("boom")
        }

        val resolved = ServerAddressResolver.resolve(
            NodeConfig(host = "localhost", port = 8080),
            IpMode.DEFAULT
        )

        assertEquals(8080, resolved.port)
        assertTrue(resolved.serverAddress.endsWith(":8080"))
    }
}
