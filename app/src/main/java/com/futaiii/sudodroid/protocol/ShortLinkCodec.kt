package com.futaiii.sudodroid.protocol

import android.util.Base64
import com.futaiii.sudodroid.data.AeadMode
import com.futaiii.sudodroid.data.AsciiMode
import com.futaiii.sudodroid.data.MieruTransport
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.ProxyMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.Charset

object ShortLinkCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val defaultRuleUrls = listOf(
        "https://gh-proxy.org/https://raw.githubusercontent.com/blackmatrix7/ios_rule_script/master/rule/Clash/China/China.list",
        "https://gh-proxy.org/https://raw.githubusercontent.com/fernvenue/chn-cidr-list/master/ipv4.yaml"
    )

    fun fromLink(link: String): NodeConfig {
        val encoded = link.removePrefix("sudoku://").trim()
        val raw = decodeBase64Flexible(encoded)
        val payload = json.decodeFromString<Payload>(raw.toString(Charset.forName("UTF-8")))

        require(payload.host.isNotBlank() && payload.port > 0 && !payload.key.isNullOrBlank()) {
            "short link missing required fields"
        }

        val ascii = when (payload.ascii?.lowercase()) {
            "ascii", "prefer_ascii" -> AsciiMode.PREFER_ASCII
            else -> AsciiMode.PREFER_ENTROPY
        }

        val aead = when (payload.aead?.lowercase()) {
            null, "" -> AeadMode.NONE
            else -> AeadMode.fromWire(payload.aead)
        }
        val local = if (payload.mixPort == null || payload.mixPort == 0) 1080 else payload.mixPort

        return NodeConfig(
            name = payload.host,
            host = payload.host,
            port = payload.port,
            key = payload.key ?: "",
            asciiMode = ascii,
            aead = aead,
            localPort = local,
            proxyMode = ProxyMode.PAC,
            ruleUrls = defaultRuleUrls,
            enableMieru = payload.mieruPort != null && payload.mieruPort > 0,
            mieruPort = payload.mieruPort,
            mieruTransport = MieruTransport.TCP
        )
    }

    fun toLink(node: NodeConfig, advertiseHost: String? = null): String {
        val host = advertiseHost ?: node.host
        val payload = Payload(
            host = host,
            port = node.port,
            key = node.key,
            ascii = when (node.asciiMode) {
                AsciiMode.PREFER_ASCII -> "ascii"
                AsciiMode.PREFER_ENTROPY -> "entropy"
            },
            aead = node.aead.wireName,
            mixPort = node.localPort,
            mieruPort = if (node.enableMieru) node.mieruPort else null
        )
        val data = json.encodeToString(Payload.serializer(), payload)
        val encoded = Base64.encodeToString(
            data.toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return "sudoku://$encoded"
    }

    @Serializable
    private data class Payload(
        @SerialName("h") val host: String = "",
        @SerialName("p") val port: Int = 0,
        @SerialName("k") val key: String? = null,
        @SerialName("a") val ascii: String? = null,
        @SerialName("e") val aead: String? = null,
        @SerialName("m") val mixPort: Int? = null,
        @SerialName("mp") val mieruPort: Int? = null
    )
}

private fun decodeBase64Flexible(encoded: String): ByteArray {
    return try {
        Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        Base64.decode(encoded, Base64.DEFAULT)
    }
}
