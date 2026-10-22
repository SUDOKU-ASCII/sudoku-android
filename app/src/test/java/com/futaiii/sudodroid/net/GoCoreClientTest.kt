package com.futaiii.sudodroid.net

import com.futaiii.sudodroid.data.AeadMode
import com.futaiii.sudodroid.data.DEFAULT_PAC_RULE_URLS
import com.futaiii.sudodroid.data.GlobalProxySettings
import com.futaiii.sudodroid.data.HttpMaskMultiplex
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.ProxyMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoCoreClientTest {
    @Test
    fun buildConfigJson_allowsPackedDownlinkWithoutAead() {
        val configJson = GoCoreClient.buildConfigJson(
            NodeConfig(
                host = "example.com",
                port = 443,
                key = "test-key",
                aead = AeadMode.NONE,
                enablePureDownlink = false
            )
        )

        val payload = Json.parseToJsonElement(configJson).jsonObject

        assertEquals("none", payload.getValue("aead").jsonPrimitive.content)
        assertEquals("false", payload.getValue("enable_pure_downlink").jsonPrimitive.content)
    }

    @Test
    fun buildConfigJson_usesUpdatedDefaultPacRules() {
        val configJson = GoCoreClient.buildConfigJson(
            NodeConfig(
                host = "example.com",
                port = 443,
                key = "test-key"
            )
        )

        val payload = Json.parseToJsonElement(configJson).jsonObject
        val ruleUrls = payload.getValue("rule_urls").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(DEFAULT_PAC_RULE_URLS, ruleUrls)
    }

    @Test
    fun buildConfigJson_placesMultiplexAtTopLevelForRawTcp() {
        val configJson = GoCoreClient.buildConfigJson(
            NodeConfig(
                host = "example.com",
                port = 443,
                key = "test-key",
                disableHttpMask = true,
                httpMaskMultiplex = HttpMaskMultiplex.ON
            )
        )

        val payload = Json.parseToJsonElement(configJson).jsonObject
        val httpMask = payload.getValue("httpmask").jsonObject

        assertEquals("on", payload.getValue("multiplex").jsonPrimitive.content)
        assertFalse(httpMask.containsKey("multiplex"))
    }

    @Test
    fun buildConfigJson_preservesExplicitProxyRulePrefixes() {
        val ruleUrls = listOf(
            "-https://example.com/proxy-dash.yaml",
            "_https://example.com/proxy-underscore.yaml"
        )
        val configJson = GoCoreClient.buildConfigJson(
            NodeConfig(host = "example.com", port = 443, key = "test-key"),
            GlobalProxySettings(proxyMode = ProxyMode.PAC, ruleUrls = ruleUrls)
        )

        val payload = Json.parseToJsonElement(configJson).jsonObject
        val encodedRules = payload.getValue("rule_urls").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(ruleUrls, encodedRules)
    }
    @Test
    fun buildConfigJson_exposesControllerWhenEndpointGiven() {
        val configJson = GoCoreClient.buildConfigJson(
            NodeConfig(name = "Tokyo", host = "example.com", port = 443, key = "test-key"),
            GlobalProxySettings(),
            ControllerEndpoint(port = 19090, secret = "abc123")
        )

        val payload = Json.parseToJsonElement(configJson).jsonObject

        assertEquals("127.0.0.1:19090", payload.getValue("external_controller").jsonPrimitive.content)
        assertEquals("abc123", payload.getValue("secret").jsonPrimitive.content)
        assertEquals("Tokyo", payload.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun buildConfigJson_leavesControllerOffWithoutEndpoint() {
        val configJson = GoCoreClient.buildConfigJson(
            NodeConfig(host = "example.com", port = 443, key = "test-key")
        )

        val payload = Json.parseToJsonElement(configJson).jsonObject

        assertEquals("", payload.getValue("external_controller").jsonPrimitive.content)
        assertEquals("", payload.getValue("secret").jsonPrimitive.content)
    }

    @Test
    fun buildConfigJson_keepsRuleUrlsInEveryModeSoLiveSwitchToPacWorks() {
        ProxyMode.entries.forEach { mode ->
            val configJson = GoCoreClient.buildConfigJson(
                NodeConfig(host = "example.com", port = 443, key = "test-key"),
                GlobalProxySettings(proxyMode = mode)
            )
            val payload = Json.parseToJsonElement(configJson).jsonObject
            val ruleUrls = payload.getValue("rule_urls").jsonArray.map { it.jsonPrimitive.content }

            assertEquals("rule_urls missing for $mode", DEFAULT_PAC_RULE_URLS, ruleUrls)
            assertEquals(mode.wireValue, payload.getValue("proxy_mode").jsonPrimitive.content)
        }
    }

    @Test
    fun controllerEndpoint_randomIsLoopbackWithFreshSecret() {
        val first = ControllerEndpoint.random()
        val second = ControllerEndpoint.random()

        assertTrue(first.address.startsWith("127.0.0.1:"))
        assertEquals(32, first.secret.length)
        assertFalse(first.secret == second.secret)
    }

    @Test
    fun controllerApi_mapsPacToRuleOnTheWire() {
        assertEquals("rule", ControllerApi.wireMode(ProxyMode.PAC))
        assertEquals("global", ControllerApi.wireMode(ProxyMode.GLOBAL))
        assertEquals("direct", ControllerApi.wireMode(ProxyMode.DIRECT))
    }

    @Test
    fun probeResult_reportsOneRoundTripOutOfEight() {
        fun rtt(ms: Long) = GoCoreClient.LatencyProbeResult(latencyMs = ms, connectOk = true).rttMs
        assertEquals(100L, rtt(800))
        assertEquals(13L, rtt(100))
        assertEquals(1L, rtt(2))
        assertEquals(-1L, rtt(-1))
    }
}
