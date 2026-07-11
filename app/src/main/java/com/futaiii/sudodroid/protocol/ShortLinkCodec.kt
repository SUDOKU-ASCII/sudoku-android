package com.futaiii.sudodroid.protocol

import com.futaiii.sudodroid.data.AeadMode
import com.futaiii.sudodroid.data.AsciiMode
import com.futaiii.sudodroid.data.DEFAULT_PAC_RULE_URLS
import com.futaiii.sudodroid.data.HttpMaskMode
import com.futaiii.sudodroid.data.HttpMaskMultiplex
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.NodeInputValidator
import com.futaiii.sudodroid.data.ProxyMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.Charset
import java.util.Base64

object ShortLinkCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromLink(link: String): NodeConfig {
        val trimmedLink = link.trim()
        val encoded = if (trimmedLink.startsWith("sudoku://", ignoreCase = true)) {
            trimmedLink.substringAfter("://").trim()
        } else {
            trimmedLink
        }
        require(encoded.isNotEmpty()) { "short link is empty" }
        val raw = decodeBase64Flexible(encoded)
        val payload = json.decodeFromString<Payload>(raw.toString(Charset.forName("UTF-8")))
        val sanitizedHost = payload.host.trim().removeSurrounding("[", "]")
        val serverPort = payload.port.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("short link has invalid server port")
        val key = payload.key?.trim().orEmpty()
        require(sanitizedHost.isNotBlank() && key.isNotEmpty()) {
            "short link missing required fields"
        }

        val ascii = decodeAscii(payload.ascii)
        val aead = when (payload.aead?.lowercase()) {
            null, "" -> AeadMode.NONE
            else -> AeadMode.fromWire(payload.aead)
        }
        val local = when (val port = payload.mixPort) {
            null, 0 -> 1080
            else -> port.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("short link has invalid local proxy port")
        }
        val enablePureDownlink = payload.packedDownlink?.let { !it } ?: true
        val primaryCustomTable = payload.customTable?.trim().orEmpty().lowercase()
        if (primaryCustomTable.isNotEmpty()) {
            NodeInputValidator.requireValidCustomTablePattern(primaryCustomTable)
        }
        val listedTables = payload.customTables
            .flatMap { NodeInputValidator.parseCustomTablePatterns(it) }
            .map { it.lowercase() }
        listedTables.forEach { NodeInputValidator.requireValidCustomTablePattern(it) }
        val customTables = if (listedTables.isNotEmpty()) {
            listedTables
        } else {
            primaryCustomTable.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        }
        val effectivePrimaryTable = primaryCustomTable.ifEmpty { customTables.firstOrNull().orEmpty() }
        val httpMaskMode = HttpMaskMode.fromWire(payload.httpMaskMode)
        val httpMaskHost = payload.httpMaskHost?.trim().orEmpty()
        val httpMaskPathRoot = NodeInputValidator.normalizeHttpMaskPathRoot(payload.httpMaskPath)
        val httpMaskMultiplex = HttpMaskMultiplex.fromWire(payload.httpMaskMux)

        return NodeConfig(
            name = sanitizedHost,
            host = sanitizedHost,
            port = serverPort,
            key = key,
            asciiMode = ascii,
            aead = aead,
            enablePureDownlink = enablePureDownlink,
            localPort = local,
            proxyMode = ProxyMode.PAC,
            ruleUrls = DEFAULT_PAC_RULE_URLS,
            customTable = effectivePrimaryTable,
            customTables = customTables,
            disableHttpMask = payload.disableHttpMask,
            httpMaskMode = httpMaskMode,
            httpMaskTls = payload.httpMaskTls,
            httpMaskHost = httpMaskHost,
            httpMaskPathRoot = httpMaskPathRoot,
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
            httpMaskMode = node.httpMaskMode.wireValue,
            httpMaskTls = node.httpMaskTls,
            httpMaskHost = node.httpMaskHost.trim().takeIf { it.isNotBlank() },
            httpMaskPath = NodeInputValidator.normalizeHttpMaskPathRoot(node.httpMaskPathRoot).takeIf { it.isNotBlank() },
            httpMaskMux = node.httpMaskMultiplex.wireValue.takeUnless {
                it == HttpMaskMultiplex.OFF.wireValue
            }
        )
        val data = json.encodeToString(Payload.serializer(), payload)
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(data.toByteArray())
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
        @SerialName("hx") val httpMaskMux: String? = null,
        @SerialName("hy") val httpMaskPath: String? = null
    )
}

private fun encodeAscii(mode: AsciiMode): String {
    return when (mode) {
        AsciiMode.PREFER_ASCII -> "ascii"
        AsciiMode.PREFER_ENTROPY -> "entropy"
        AsciiMode.UP_ASCII_DOWN_ENTROPY -> AsciiMode.UP_ASCII_DOWN_ENTROPY.wireValue
        AsciiMode.UP_ENTROPY_DOWN_ASCII -> AsciiMode.UP_ENTROPY_DOWN_ASCII.wireValue
    }
}

private fun decodeAscii(raw: String?): AsciiMode {
    return AsciiMode.fromWire(raw)
}

private fun decodeBase64Flexible(encoded: String): ByteArray {
    val sanitized = encoded.filterNot(Char::isWhitespace)
    val padded = padBase64(sanitized)
    return runCatching { Base64.getUrlDecoder().decode(padded) }
        .recoverCatching { Base64.getDecoder().decode(padded) }
        .recoverCatching {
            val normalized = padded.replace('-', '+').replace('_', '/')
            Base64.getDecoder().decode(normalized)
        }
        .getOrElse { throw IllegalArgumentException("invalid short link payload", it) }
}

private fun padBase64(raw: String): String {
    val remainder = raw.length % 4
    return if (remainder == 0) raw else raw + "=".repeat(4 - remainder)
}
