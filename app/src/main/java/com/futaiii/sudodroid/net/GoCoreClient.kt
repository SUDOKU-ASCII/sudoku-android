package com.futaiii.sudodroid.net

import android.util.Log
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.ProxyMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object GoCoreClient {
    private const val TAG = "GoCoreClient"
    private val json = Json { encodeDefaults = true }
    // gomobile bind -javapkg com.futaiii.sudoku ./pkg/mobile
    // generates class com.futaiii.sudoku.mobile.Mobile
    private const val MOBILE_CLASS = "com.futaiii.sudoku.mobile.Mobile"

    private object MobileBinding {
        private val mobileClass = runCatching { Class.forName(MOBILE_CLASS) }.getOrNull()
        // New signature: start(String) throws Exception
        private val startMethod = mobileClass?.getMethod("start", String::class.java)
        // New signature: stop()
        private val stopMethod = mobileClass?.getMethod("stop")

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
        @SerialName("proxy_mode") val proxyMode: String
    )
}
