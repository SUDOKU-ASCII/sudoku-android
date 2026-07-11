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
}
