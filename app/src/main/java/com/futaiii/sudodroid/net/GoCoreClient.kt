package com.futaiii.sudodroid.net

import android.util.Log
import com.futaiii.sudodroid.data.HttpMaskMode
import com.futaiii.sudodroid.data.HttpMaskMultiplex
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.ProxyMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object GoCoreClient {
    private const val TAG = "GoCoreClient"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    // gomobile bind -javapkg com.futaiii.sudoku ./pkg/mobile
    // generates class com.futaiii.sudoku.mobile.Mobile
    private const val MOBILE_CLASS = "com.futaiii.sudoku.mobile.Mobile"

    private object MobileBinding {
        private val mobileClass = runCatching { Class.forName(MOBILE_CLASS) }.getOrNull()
        // New signature: start(String) throws Exception
        private val startMethod = mobileClass?.getMethod("start", String::class.java)
        // New signature: stop()
        private val stopMethod = mobileClass?.getMethod("stop")
        private val trafficMethod = mobileClass?.getMethod("getTrafficStatsJson")
        private val resetTrafficMethod = mobileClass?.getMethod("resetTrafficStats")

        fun start(configJson: String) {
            val method = startMethod ?: error("Sudoku core AAR missing; run scripts/build_sudoku_aar.sh to generate $MOBILE_CLASS")
            try {
                method.invoke(null, configJson)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                // Unwrap the exception thrown by Go
                throw e.targetException ?: e
            }
        }

        fun stop() {
            try {
                stopMethod?.invoke(null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop Go core", e)
            }
        }

        fun getTrafficStatsJsonOrNull(): String? {
            return try {
                trafficMethod?.invoke(null) as? String
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get Go core traffic stats", e)
                null
            }
        }

        fun resetTrafficStats() {
            try {
                resetTrafficMethod?.invoke(null)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to reset Go core traffic stats", e)
            }
        }
    }

    fun start(configJson: String) {
        // Ensure previous instance is stopped
        runCatching { MobileBinding.stop() }
        try {
            MobileBinding.start(configJson)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start Go core", t)
            throw t
        }
    }

    fun stop() {
        MobileBinding.stop()
    }

    fun getTrafficStats(): TrafficStats? {
        val raw = MobileBinding.getTrafficStatsJsonOrNull() ?: return null
        return runCatching { json.decodeFromString<TrafficStats>(raw) }.getOrNull()
    }

    fun resetTrafficStats() {
        MobileBinding.resetTrafficStats()
    }

    fun buildConfigJson(node: NodeConfig): String {
        val resolved = ServerAddressResolver.resolve(node)
        val serverAddress = resolved.serverAddress
        val proxyMode = node.proxyMode.wireValue
        val ruleUrls = if (node.proxyMode == ProxyMode.PAC) {
            node.ruleUrls.mapNotNull { url ->
                val trimmed = url.trim()
                trimmed.takeIf { it.isNotEmpty() }
            }
        } else {
            emptyList()
        }

        val normalizedCustomTables = node.customTables
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val primaryCustomTable = normalizedCustomTables.firstOrNull()
            ?: node.customTable.trim().takeIf { it.isNotEmpty() }
        val customTables = if (normalizedCustomTables.isNotEmpty()) normalizedCustomTables else {
            primaryCustomTable?.let { listOf(it) } ?: emptyList()
        }

        val httpMaskHost = node.httpMaskHost.trim().ifEmpty {
            if (!node.disableHttpMask) resolved.sniHost.orEmpty() else ""
        }
        val httpMaskMultiplex = if (node.disableHttpMask || node.httpMaskMode == HttpMaskMode.LEGACY) {
            HttpMaskMultiplex.OFF.wireValue
        } else {
            node.httpMaskMultiplex.wireValue
        }
        val config = GoCoreConfig(
            localPort = node.localPort,
            serverAddress = serverAddress,
            key = node.key.trim(),
            aead = node.aead.wireName,
            paddingMin = node.paddingMin,
            paddingMax = node.paddingMax,
            ruleUrls = ruleUrls,
            ascii = node.asciiMode.wireValue,
            customTable = primaryCustomTable.orEmpty(),
            customTables = customTables,
            enablePureDownlink = node.enablePureDownlink,
            disableHttpMask = node.disableHttpMask,
            httpMaskMode = node.httpMaskMode.wireValue,
            httpMaskTls = node.httpMaskTls,
            httpMaskHost = httpMaskHost,
            httpMaskMultiplex = httpMaskMultiplex,
            proxyMode = proxyMode
        )
        return json.encodeToString(config)
    }

    @Serializable
    private data class GoCoreConfig(
        val mode: String = "client",
        val transport: String = "tcp",
        @SerialName("local_port") val localPort: Int,
        @SerialName("server_address") val serverAddress: String,
        val key: String,
        val aead: String,
        @SerialName("suspicious_action") val suspiciousAction: String = "fallback",
        @SerialName("padding_min") val paddingMin: Int,
        @SerialName("padding_max") val paddingMax: Int,
        @SerialName("rule_urls") val ruleUrls: List<String> = emptyList(),
        val ascii: String,
        @SerialName("custom_table") val customTable: String = "",
        @SerialName("custom_tables") val customTables: List<String> = emptyList(),
        @SerialName("enable_pure_downlink") val enablePureDownlink: Boolean = true,
        @SerialName("disable_http_mask") val disableHttpMask: Boolean = false,
        @SerialName("http_mask_mode") val httpMaskMode: String,
        @SerialName("http_mask_tls") val httpMaskTls: Boolean = false,
        @SerialName("http_mask_host") val httpMaskHost: String = "",
        @SerialName("http_mask_multiplex") val httpMaskMultiplex: String = "off",
        @SerialName("proxy_mode") val proxyMode: String
    )

    @Serializable
    data class TrafficStats(
        @SerialName("direct_tx") val directTx: Long = 0,
        @SerialName("direct_rx") val directRx: Long = 0,
        @SerialName("proxy_tx") val proxyTx: Long = 0,
        @SerialName("proxy_rx") val proxyRx: Long = 0
    )
}
