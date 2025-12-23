package com.futaiii.sudodroid.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

@Serializable
data class NodeConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 1080,
    val key: String = "",
    @SerialName("ascii")
    val asciiMode: AsciiMode = AsciiMode.PREFER_ENTROPY,
    @SerialName("custom_table")
    val customTable: String = "",
    @SerialName("custom_tables")
    val customTables: List<String> = emptyList(),
    @SerialName("aead")
    val aead: AeadMode = AeadMode.CHACHA20_POLY1305,
    val enablePureDownlink: Boolean = true,
    val paddingMin: Int = 5,
    val paddingMax: Int = 15,
    val localPort: Int = 1080,
    val proxyMode: ProxyMode = ProxyMode.GLOBAL,
    val ruleUrls: List<String> = emptyList(),
    @SerialName("ip_mode")
    val ipMode: IpMode = IpMode.DEFAULT,
    @SerialName("disable_http_mask")
    val disableHttpMask: Boolean = false,
    @SerialName("http_mask_mode")
    val httpMaskMode: HttpMaskMode = HttpMaskMode.LEGACY,
    @SerialName("http_mask_tls")
    val httpMaskTls: Boolean = false,
    @SerialName("http_mask_host")
    val httpMaskHost: String = "",
    val enableMieru: Boolean = false,
    val mieruPort: Int? = null,
    val mieruTransport: MieruTransport = MieruTransport.TCP,
    val mieruMtu: Int = 1400,
    val mieruMultiplexing: MieruMultiplexing = MieruMultiplexing.HIGH,
    val mieruUsername: String? = null,
    val mieruPassword: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class AsciiMode(val wireValue: String, val description: String) {
    @SerialName("prefer_ascii")
    PREFER_ASCII("prefer_ascii", "Prefer ASCII"),

    @SerialName("prefer_entropy")
    PREFER_ENTROPY("prefer_entropy", "Prefer low entropy");
}

@Serializable
enum class AeadMode(val wireName: String) {
    @SerialName("aes-128-gcm")
    AES_128_GCM("aes-128-gcm"),
    @SerialName("chacha20-poly1305")
    CHACHA20_POLY1305("chacha20-poly1305"),
    @SerialName("none")
    NONE("none");

    companion object {
        fun fromWire(raw: String?): AeadMode = when (raw?.lowercase()) {
            "aes-128-gcm" -> AES_128_GCM
            "chacha20-poly1305" -> CHACHA20_POLY1305
            else -> NONE
        }
    }
}

@Serializable
enum class ProxyMode(val wireValue: String, val label: String) {
    @SerialName("global")
    GLOBAL("global", "Global"),
    @SerialName("direct")
    DIRECT("direct", "Direct"),
    @SerialName("pac")
    PAC("pac", "PAC");
}

@Serializable
enum class IpMode(val label: String) {
    @SerialName("default")
    DEFAULT("Default (IPv4)"),
    @SerialName("ipv4_only")
    IPV4_ONLY("IPv4 only"),
    @SerialName("ipv6_preferred")
    IPV6_PREFERRED("IPv6 preferred");
}

@Serializable(with = HttpMaskModeSerializer::class)
enum class HttpMaskMode(val wireValue: String, val label: String) {
    LEGACY("legacy", "Legacy"),
    AUTO("auto", "Auto"),
    STREAM("stream", "Stream"),
    POLL("poll", "Poll");

    companion object {
        fun fromWire(raw: String?): HttpMaskMode = when (raw?.lowercase()) {
            "auto" -> AUTO
            "stream", "xhttp" -> STREAM
            "poll", "pht" -> POLL
            else -> LEGACY
        }
    }
}

object HttpMaskModeSerializer : KSerializer<HttpMaskMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("HttpMaskMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: HttpMaskMode) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): HttpMaskMode {
        return HttpMaskMode.fromWire(decoder.decodeString())
    }
}

@Serializable
enum class MieruTransport(val wireValue: String) {
    @SerialName("TCP")
    TCP("TCP"),
    @SerialName("UDP")
    UDP("UDP");
}

@Serializable
enum class MieruMultiplexing(val wireValue: String) {
    @SerialName("HIGH")
    HIGH("HIGH"),
    @SerialName("MIDDLE")
    MIDDLE("MIDDLE"),
    @SerialName("LOW")
    LOW("LOW");
}
