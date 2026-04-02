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

val DEFAULT_PAC_RULE_URLS: List<String> = listOf(
    "https://fastly.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/ChinaMaxNoIP/ChinaMaxNoIP.list",
    "https://fastly.jsdelivr.net/gh/fernvenue/chn-cidr-list@master/ipv4.yaml",
    "https://fastly.jsdelivr.net/gh/fernvenue/chn-cidr-list@master/ipv6.yaml",
    "!https://gcore.jsdelivr.net/gh/TG-Twilight/AWAvenue-Ads-Rule@main/Filters/AWAvenue-Ads-Rule-Clash.yaml"
)

@Serializable
data class GlobalProxySettings(
    @SerialName("proxy_mode")
    val proxyMode: ProxyMode = ProxyMode.PAC,
    @SerialName("rule_urls")
    val ruleUrls: List<String> = DEFAULT_PAC_RULE_URLS
)

@Serializable
data class ReverseForwarderSettings(
    @SerialName("dial_url") val dialUrl: String = "wss://example.com:8081/ssh",
    @SerialName("listen_addr") val listenAddr: String = "127.0.0.1:2222",
    val insecure: Boolean = false
)

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
    val proxyMode: ProxyMode = ProxyMode.PAC,
    val ruleUrls: List<String> = emptyList(),
    @SerialName("disable_http_mask")
    val disableHttpMask: Boolean = false,
    @SerialName("http_mask_mode")
    val httpMaskMode: HttpMaskMode = HttpMaskMode.AUTO,
    @SerialName("http_mask_tls")
    val httpMaskTls: Boolean = false,
    @SerialName("http_mask_host")
    val httpMaskHost: String = "",
    @SerialName("path_root")
    val httpMaskPathRoot: String = "",
    @SerialName("http_mask_multiplex")
    val httpMaskMultiplex: HttpMaskMultiplex = HttpMaskMultiplex.OFF,
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
enum class AsciiPreference(val wireToken: String, val label: String) {
    @SerialName("ascii")
    ASCII("ascii", "ASCII"),

    @SerialName("entropy")
    ENTROPY("entropy", "Entropy");
}

@Serializable
enum class AsciiMode(val wireValue: String, val description: String) {
    @SerialName("prefer_ascii")
    PREFER_ASCII("prefer_ascii", "Up ASCII / Down ASCII"),

    @SerialName("prefer_entropy")
    PREFER_ENTROPY("prefer_entropy", "Up entropy / Down entropy"),

    @SerialName("up_ascii_down_entropy")
    UP_ASCII_DOWN_ENTROPY("up_ascii_down_entropy", "Up ASCII / Down entropy"),

    @SerialName("up_entropy_down_ascii")
    UP_ENTROPY_DOWN_ASCII("up_entropy_down_ascii", "Up entropy / Down ASCII");

    companion object {
        fun fromWire(raw: String?): AsciiMode = when (raw?.trim()?.lowercase()) {
            "ascii", "prefer_ascii" -> PREFER_ASCII
            "up_ascii_down_entropy" -> UP_ASCII_DOWN_ENTROPY
            "up_entropy_down_ascii" -> UP_ENTROPY_DOWN_ASCII
            else -> PREFER_ENTROPY
        }

        fun fromPreferences(
            uplink: AsciiPreference,
            downlink: AsciiPreference
        ): AsciiMode {
            return when (uplink to downlink) {
                AsciiPreference.ASCII to AsciiPreference.ASCII -> PREFER_ASCII
                AsciiPreference.ASCII to AsciiPreference.ENTROPY -> UP_ASCII_DOWN_ENTROPY
                AsciiPreference.ENTROPY to AsciiPreference.ASCII -> UP_ENTROPY_DOWN_ASCII
                AsciiPreference.ENTROPY to AsciiPreference.ENTROPY -> PREFER_ENTROPY
            }
        }
    }
}

fun AsciiMode.uplinkPreference(): AsciiPreference {
    return when (this) {
        AsciiMode.PREFER_ASCII,
        AsciiMode.UP_ASCII_DOWN_ENTROPY -> AsciiPreference.ASCII

        AsciiMode.PREFER_ENTROPY,
        AsciiMode.UP_ENTROPY_DOWN_ASCII -> AsciiPreference.ENTROPY
    }
}

fun AsciiMode.downlinkPreference(): AsciiPreference {
    return when (this) {
        AsciiMode.PREFER_ASCII,
        AsciiMode.UP_ENTROPY_DOWN_ASCII -> AsciiPreference.ASCII

        AsciiMode.PREFER_ENTROPY,
        AsciiMode.UP_ASCII_DOWN_ENTROPY -> AsciiPreference.ENTROPY
    }
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

@Serializable(with = HttpMaskModeSerializer::class)
enum class HttpMaskMode(val wireValue: String, val label: String) {
    AUTO("auto", "Auto"),
    STREAM("stream", "Stream"),
    POLL("poll", "Poll"),
    WS("ws", "ws");

    companion object {
        fun fromWire(raw: String?): HttpMaskMode = when (raw?.lowercase()) {
            "auto" -> AUTO
            "stream", "xhttp" -> STREAM
            "poll", "pht" -> POLL
            "ws" -> WS
            "legacy" -> AUTO
            else -> AUTO
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

@Serializable(with = HttpMaskMultiplexSerializer::class)
enum class HttpMaskMultiplex(val wireValue: String, val label: String) {
    OFF("off", "Off"),
    AUTO("auto", "Auto"),
    ON("on", "On");

    companion object {
        fun fromWire(raw: String?): HttpMaskMultiplex = when (raw?.lowercase()) {
            "auto" -> AUTO
            "on" -> ON
            else -> OFF
        }
    }
}

object HttpMaskMultiplexSerializer : KSerializer<HttpMaskMultiplex> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("HttpMaskMultiplex", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: HttpMaskMultiplex) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): HttpMaskMultiplex {
        return HttpMaskMultiplex.fromWire(decoder.decodeString())
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
