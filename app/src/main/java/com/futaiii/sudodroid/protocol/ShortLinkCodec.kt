package com.futaiii.sudodroid.protocol

import android.util.Base64
import com.futaiii.sudodroid.data.AeadMode
import com.futaiii.sudodroid.data.AsciiMode
import com.futaiii.sudodroid.data.HttpMaskMode
import com.futaiii.sudodroid.data.HttpMaskMultiplex
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

        val ascii = decodeAscii(payload.ascii)
        val aead = when (payload.aead?.lowercase()) {
            null, "" -> AeadMode.NONE
            else -> AeadMode.fromWire(payload.aead)
        }
        val local = if (payload.mixPort == null || payload.mixPort == 0) 1080 else payload.mixPort
        val enablePureDownlink = payload.packedDownlink?.let { !it } ?: true
        val primaryCustomTable = payload.customTable?.trim().orEmpty()
        val listedTables = payload.customTables
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val customTables = if (listedTables.isNotEmpty()) {
            listedTables
        } else {
            primaryCustomTable.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        }
        val effectivePrimaryTable = primaryCustomTable.ifEmpty { customTables.firstOrNull().orEmpty() }
        val httpMaskMode = HttpMaskMode.fromWire(payload.httpMaskMode)
        val httpMaskHost = payload.httpMaskHost?.trim().orEmpty()
        val httpMaskMultiplex = if (payload.disableHttpMask || httpMaskMode == HttpMaskMode.LEGACY) {
            HttpMaskMultiplex.OFF
        } else {
            HttpMaskMultiplex.fromWire(payload.httpMaskMux)
        }
        val sanitizedHost = payload.host.trim().removeSurrounding("[", "]")

        return NodeConfig(
            name = sanitizedHost,
            host = sanitizedHost,
            port = payload.port,
            key = payload.key ?: "",
            asciiMode = ascii,
            aead = aead,
            enablePureDownlink = enablePureDownlink,
            localPort = local,
            proxyMode = ProxyMode.PAC,
            ruleUrls = defaultRuleUrls,
            customTable = effectivePrimaryTable,
            customTables = customTables,
            disableHttpMask = payload.disableHttpMask,
            httpMaskMode = httpMaskMode,
            httpMaskTls = payload.httpMaskTls,
            httpMaskHost = httpMaskHost,
            httpMaskMultiplex = httpMaskMultiplex
        )
    }

    fun toLink(node: NodeConfig, advertiseHost: String? = null): String {
        val host = (advertiseHost ?: node.host).trim().removeSurrounding("[", "]")
        val normalizedCustomTables = node.customTables
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val primaryCustomTable = normalizedCustomTables.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: node.customTable.trim().takeIf { it.isNotBlank() }
        val payload = Payload(
            host = host,
            port = node.port,
            key = node.key,
            ascii = encodeAscii(node.asciiMode),
            aead = node.aead.wireName,
            mixPort = node.localPort,
            packedDownlink = !node.enablePureDownlink,
            customTable = primaryCustomTable,
            customTables = normalizedCustomTables.takeIf { it.isNotEmpty() } ?: emptyList(),
            disableHttpMask = node.disableHttpMask,
            httpMaskMode = node.httpMaskMode.wireValue.takeUnless { node.httpMaskMode == HttpMaskMode.LEGACY },
            httpMaskTls = node.httpMaskTls,
            httpMaskHost = node.httpMaskHost.trim().takeIf { it.isNotBlank() },
            httpMaskMux = node.httpMaskMultiplex.wireValue.takeUnless {
                node.disableHttpMask || it == HttpMaskMultiplex.OFF.wireValue
            }
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
        @SerialName("x") val packedDownlink: Boolean? = null,
        @SerialName("t") val customTable: String? = null,
        @SerialName("ts") val customTables: List<String> = emptyList(),
        @SerialName("hd") val disableHttpMask: Boolean = false,
        @SerialName("hm") val httpMaskMode: String? = null,
        @SerialName("ht") val httpMaskTls: Boolean = false,
        @SerialName("hh") val httpMaskHost: String? = null,
        @SerialName("hx") val httpMaskMux: String? = null
    )
}

private fun encodeAscii(mode: AsciiMode): String {
    return when (mode) {
        AsciiMode.PREFER_ASCII -> "ascii"
        AsciiMode.PREFER_ENTROPY -> "entropy"
    }
}

private fun decodeAscii(raw: String?): AsciiMode {
    return when (raw?.lowercase()) {
        "ascii", "prefer_ascii" -> AsciiMode.PREFER_ASCII
        else -> AsciiMode.PREFER_ENTROPY
    }
}

private fun decodeBase64Flexible(encoded: String): ByteArray {
    return try {
        Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        Base64.decode(encoded, Base64.DEFAULT)
    }
}
