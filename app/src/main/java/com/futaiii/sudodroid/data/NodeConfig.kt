package com.futaiii.sudodroid.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    @SerialName("aead")
    val aead: AeadMode = AeadMode.CHACHA20_POLY1305,
    val paddingMin: Int = 5,
    val paddingMax: Int = 15,
    val localPort: Int = 1080,
    val proxyMode: ProxyMode = ProxyMode.GLOBAL,
    val ruleUrls: List<String> = emptyList(),
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
