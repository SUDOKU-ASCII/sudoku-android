package com.futaiii.sudodroid.net

import android.util.Log
import com.futaiii.sudodroid.data.GlobalProxySettings
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
    private val lifecycleLock = Any()
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
        private val startReverseForwarderMethod = mobileClass?.getMethod(
            "startReverseForwarder",
            String::class.java,
            String::class.java,
            java.lang.Boolean.TYPE
        )
        private val stopReverseForwarderMethod = mobileClass?.getMethod("stopReverseForwarder")
        private val reverseStatusMethod = mobileClass?.getMethod("getReverseForwardStatusJson")

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

        fun startReverseForwarder(listenAddr: String, dialUrl: String, insecure: Boolean) {
            val method = startReverseForwarderMethod
                ?: error("Sudoku core AAR missing reverse forwarder API; rebuild app/libs/sudoku.aar")
            try {
                method.invoke(null, listenAddr, dialUrl, insecure)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException ?: e
            }
        }

        fun stopReverseForwarder() {
            try {
                stopReverseForwarderMethod?.invoke(null)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to stop reverse forwarder", e)
            }
        }

        fun getReverseForwardStatusJsonOrNull(): String? {
            return try {
                reverseStatusMethod?.invoke(null) as? String
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get reverse forwarder status", e)
                null
            }
        }

    }

    private inline fun <T> withLifecycleLock(block: () -> T): T = synchronized(lifecycleLock) { block() }

    fun start(configJson: String) {
        withLifecycleLock {
            // Ensure previous instance is stopped
            runCatching { MobileBinding.stop() }
            try {
                MobileBinding.start(configJson)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Go core", t)
                throw t
            }
        }
    }

    fun stop() {
        withLifecycleLock { MobileBinding.stop() }
    }

    fun getTrafficStats(): TrafficStats? {
        val raw = MobileBinding.getTrafficStatsJsonOrNull() ?: return null
        return runCatching { json.decodeFromString<TrafficStats>(raw) }.getOrNull()
    }

    fun resetTrafficStats() {
        MobileBinding.resetTrafficStats()
    }

    fun startReverseForwarder(listenAddr: String, dialUrl: String, insecure: Boolean) {
        withLifecycleLock {
            try {
                MobileBinding.startReverseForwarder(listenAddr.trim(), dialUrl.trim(), insecure)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start reverse forwarder", t)
                throw t
            }
        }
    }

    fun stopReverseForwarder() {
        withLifecycleLock { MobileBinding.stopReverseForwarder() }
    }

    fun getReverseForwardStatus(): ReverseForwardStatus {
        val raw = MobileBinding.getReverseForwardStatusJsonOrNull() ?: return ReverseForwardStatus()
        return runCatching { json.decodeFromString<ReverseForwardStatus>(raw) }
            .getOrElse {
                Log.w(TAG, "Failed to decode reverse forwarder status: $raw", it)
                ReverseForwardStatus()
            }
    }

    fun buildConfigJson(
        node: NodeConfig,
        globalProxySettings: GlobalProxySettings = GlobalProxySettings()
    ): String {
        val normalizedHost = normalizeHost(node.host)
        val serverAddress = joinHostPort(normalizedHost, node.port)
        val proxyMode = globalProxySettings.proxyMode.wireValue
        val ruleUrls = if (globalProxySettings.proxyMode == ProxyMode.PAC) {
            globalProxySettings.ruleUrls.mapNotNull { url ->
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
            if (!node.disableHttpMask && !isLiteralAddress(normalizedHost)) normalizedHost else ""
        }
        val httpMaskMultiplex = if (node.disableHttpMask) {
            HttpMaskMultiplex.OFF.wireValue
        } else {
            node.httpMaskMultiplex.wireValue
        }
        val httpMaskPathRoot = node.httpMaskPathRoot.trim()
        val httpMask = GoHttpMaskConfig(
            disable = node.disableHttpMask,
            mode = node.httpMaskMode.wireValue,
            tls = node.httpMaskTls,
            host = httpMaskHost,
            pathRoot = httpMaskPathRoot,
            multiplex = httpMaskMultiplex
        )
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
            httpMask = httpMask,
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
        @SerialName("httpmask") val httpMask: GoHttpMaskConfig = GoHttpMaskConfig(),
        @SerialName("proxy_mode") val proxyMode: String
    )

    @Serializable
    private data class GoHttpMaskConfig(
        val disable: Boolean = false,
        val mode: String = "auto",
        val tls: Boolean = false,
        val host: String = "",
        @SerialName("path_root") val pathRoot: String = "",
        val multiplex: String = "off"
    )

    @Serializable
    data class TrafficStats(
        @SerialName("direct_tx") val directTx: Long = 0,
        @SerialName("direct_rx") val directRx: Long = 0,
        @SerialName("proxy_tx") val proxyTx: Long = 0,
        @SerialName("proxy_rx") val proxyRx: Long = 0
    )

    @Serializable
    data class ReverseForwardStatus(
        val running: Boolean = false,
        @SerialName("listen_addr") val listenAddr: String = "",
        @SerialName("dial_url") val dialUrl: String = "",
        val insecure: Boolean = false,
        @SerialName("last_error") val lastError: String = ""
    )

    private fun normalizeHost(host: String): String {
        return host.trim().removeSurrounding("[", "]")
    }

    private fun joinHostPort(host: String, port: Int): String {
        return if (host.contains(":")) {
            "[$host]:$port"
        } else {
            "$host:$port"
        }
    }

    private fun isLiteralAddress(host: String): Boolean {
        if (host.contains(":")) {
            return true
        }
        val parts = host.split(".")
        return parts.size == 4 && parts.all { part ->
            val value = part.toIntOrNull() ?: return false
            value in 0..255 && part == value.toString()
        }
    }
}
