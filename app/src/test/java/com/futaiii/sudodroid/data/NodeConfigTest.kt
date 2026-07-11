package com.futaiii.sudodroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeConfigTest {
    @Test
    fun defaultPacRuleUrls_matchSudokuCoreDefaults() {
        assertEquals(
            listOf(
                "https://fastly.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/ChinaMaxNoIP/ChinaMaxNoIP.list",
                "https://fastly.jsdelivr.net/gh/fernvenue/chn-cidr-list@master/ipv4.yaml",
                "https://fastly.jsdelivr.net/gh/fernvenue/chn-cidr-list@master/ipv6.yaml",
                "!https://gcore.jsdelivr.net/gh/TG-Twilight/AWAvenue-Ads-Rule@main/Filters/AWAvenue-Ads-Rule-Clash.yaml"
            ),
            DEFAULT_PAC_RULE_URLS
        )
    }

    @Test
    fun asciiPreferences_roundTripToWireModes() {
        val cases = listOf(
            Triple(AsciiPreference.ASCII, AsciiPreference.ASCII, AsciiMode.PREFER_ASCII),
            Triple(AsciiPreference.ASCII, AsciiPreference.ENTROPY, AsciiMode.UP_ASCII_DOWN_ENTROPY),
            Triple(AsciiPreference.ENTROPY, AsciiPreference.ASCII, AsciiMode.UP_ENTROPY_DOWN_ASCII),
            Triple(AsciiPreference.ENTROPY, AsciiPreference.ENTROPY, AsciiMode.PREFER_ENTROPY)
        )

        cases.forEach { (uplink, downlink, expectedMode) ->
            val mode = AsciiMode.fromPreferences(uplink, downlink)

            assertEquals(expectedMode, mode)
            assertEquals(uplink, mode.uplinkPreference())
            assertEquals(downlink, mode.downlinkPreference())
        }
    }
}
