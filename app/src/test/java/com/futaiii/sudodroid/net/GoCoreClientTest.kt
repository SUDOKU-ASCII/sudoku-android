package com.futaiii.sudodroid.net

import com.futaiii.sudodroid.data.AeadMode
import com.futaiii.sudodroid.data.DEFAULT_PAC_RULE_URLS
import com.futaiii.sudodroid.data.NodeConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
}
