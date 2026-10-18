package com.futaiii.sudodroid.net

import android.util.Log
import com.futaiii.sudodroid.data.GlobalProxySettings
import com.futaiii.sudodroid.data.NodeConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object GoCoreClient {
    private const val TAG = "GoCoreClient"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val lifecycleLock = Any()
    private val reverseStatusCacheLock = Any()

    @Volatile
    private var lastReverseForwardStatusRaw: String? = null

    @Volatile
    private var lastReverseForwardStatus: ReverseForwardStatus? = null
    // gomobile bind -javapkg com.futaiii.sudoku ./pkg/mobile
    // generates class com.futaiii.sudoku.mobile.Mobile
    private const val MOBILE_CLASS = "com.futaiii.sudoku.mobile.Mobile"

    private object MobileBinding {
        private val mobileClass = runCatching { Class.forName(MOBILE_CLASS) }.getOrNull()
        // New signature: start(String) throws Exception
        private val startMethod = mobileClass?.getMethod("start", String::class.java)
        // New signature: stop()
        private val stopMethod = mobileClass?.getMethod("stop")
        private val setRuleCacheDirMethod = mobileClass?.let {
            runCatching { it.getMethod("setRuleCacheDir", String::class.java) }.getOrNull()
        }
        private val probeLatencyMethod = mobileClass?.let {
            runCatching { it.getMethod("probeLatencyJson", String::class.java) }.getOrNull()
        }
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

        fun setRuleCacheDir(path: String) {
            val method = setRuleCacheDirMethod
                ?: error("Sudoku core AAR missing rule cache API; rebuild app/libs/sudoku.aar")
            try {
                method.invoke(null, path)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException ?: e
            }
        }

        fun probeLatencyJson(configJson: String): String {
            val method = probeLatencyMethod
                ?: error("Sudoku core AAR missing latency probe API; rebuild app/libs/sudoku.aar")
            return try {
                method.invoke(null, configJson) as String
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException ?: e
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

    fun initialize(cacheDir: File) {
        val ruleCacheDir = File(cacheDir, "sudoku/geodata")
        runCatching { MobileBinding.setRuleCacheDir(ruleCacheDir.absolutePath) }
            .onFailure { Log.e(TAG, "Failed to configure Sudoku rule cache", it) }
    }

    /** Starts the core; [endpoint] is published to [ControllerApi] once the core is up. */
    fun start(configJson: String, endpoint: ControllerEndpoint? = null) {
        withLifecycleLock {
            invalidateReverseForwardStatusCache()
            ControllerApi.detach()
            // Ensure previous instance is stopped
            runCatching { MobileBinding.stop() }
            try {
                MobileBinding.start(configJson)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Go core", t)
                throw t
            }
            ControllerApi.attach(endpoint)
        }
    }

    fun stop() {
        withLifecycleLock {
            ControllerApi.detach()
            MobileBinding.stop()
            invalidateReverseForwardStatusCache()
        }
    }

    fun probeLatency(node: NodeConfig, globalProxySettings: GlobalProxySettings = GlobalProxySettings()): LatencyProbeResult {
        val configJson = buildConfigJson(node, globalProxySettings)
        val raw = MobileBinding.probeLatencyJson(configJson)
        return json.decodeFromString<LatencyProbeResult>(raw)
    }

    fun startReverseForwarder(listenAddr: String, dialUrl: String, insecure: Boolean) {
        withLifecycleLock {
            invalidateReverseForwardStatusCache()
            try {
                MobileBinding.startReverseForwarder(listenAddr.trim(), dialUrl.trim(), insecure)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start reverse forwarder", t)
                throw t
            }
        }
    }

    fun stopReverseForwarder() {
        withLifecycleLock {
            MobileBinding.stopReverseForwarder()
            invalidateReverseForwardStatusCache()
        }
    }

    fun getReverseForwardStatus(): ReverseForwardStatus {
        val raw = MobileBinding.getReverseForwardStatusJsonOrNull() ?: return ReverseForwardStatus()
        synchronized(reverseStatusCacheLock) {
            if (raw == lastReverseForwardStatusRaw) {
                return lastReverseForwardStatus ?: ReverseForwardStatus()
            }
            val decoded = runCatching { json.decodeFromString<ReverseForwardStatus>(raw) }
                .getOrElse {
                    Log.w(TAG, "Failed to decode reverse forwarder status: $raw", it)
                    ReverseForwardStatus()
                }
            lastReverseForwardStatusRaw = raw
            lastReverseForwardStatus = decoded
            return decoded
        }
    }

    fun buildConfigJson(
        node: NodeConfig,
        globalProxySettings: GlobalProxySettings = GlobalProxySettings(),
        controller: ControllerEndpoint? = null
    ): String {
        val normalizedHost = normalizeHost(node.host)
        val serverAddress = joinHostPort(normalizedHost, node.port)
        val proxyMode = globalProxySettings.proxyMode.wireValue
        // Always ship the PAC list: the core keeps it dormant outside PAC, but a live switch to
        // PAC (PATCH /configs) then has the user's rules to load instead of an empty set.
        val ruleUrls = globalProxySettings.ruleUrls.mapNotNull { url ->
            url.trim().takeIf { it.isNotEmpty() }
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
        val multiplex = node.httpMaskMultiplex.wireValue
        val httpMaskPathRoot = node.httpMaskPathRoot.trim()
        val httpMask = GoHttpMaskConfig(
            disable = node.disableHttpMask,
            mode = node.httpMaskMode.wireValue,
            tls = node.httpMaskTls,
            host = httpMaskHost,
            pathRoot = httpMaskPathRoot
        )
        val config = GoCoreConfig(
            name = node.name.trim(),
            externalController = controller?.address.orEmpty(),
            secret = controller?.secret.orEmpty(),
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
            multiplex = multiplex,
            httpMask = httpMask,
            proxyMode = proxyMode
        )
        return json.encodeToString(config)
    }

    @Serializable
    private data class GoCoreConfig(
        val mode: String = "client",
        val transport: String = "tcp",
        val name: String = "",
        @SerialName("external_controller") val externalController: String = "",
        val secret: String = "",
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
        val multiplex: String = "off",
        @SerialName("httpmask") val httpMask: GoHttpMaskConfig = GoHttpMaskConfig(),
        @SerialName("proxy_mode") val proxyMode: String
    )

    @Serializable
    private data class GoHttpMaskConfig(
        val disable: Boolean = false,
        val mode: String = "auto",
        val tls: Boolean = false,
        val host: String = "",
        @SerialName("path_root") val pathRoot: String = ""
    )

    @Serializable
    data class LatencyProbeResult(
        @SerialName("latency_ms") val latencyMs: Long = -1,
        @SerialName("status_code") val statusCode: Int = 0,
        @SerialName("connect_ok") val connectOk: Boolean = false,
        @SerialName("checked_at_unix") val checkedAtUnix: Long = 0,
        val error: String = ""
    )

    @Serializable
    data class ReverseForwardStatus(
        val running: Boolean = false,
        @SerialName("listen_addr") val listenAddr: String = "",
        @SerialName("dial_url") val dialUrl: String = "",
        val insecure: Boolean = false,
        @SerialName("last_error") val lastError: String = ""
    )

    private fun invalidateReverseForwardStatusCache() {
        synchronized(reverseStatusCacheLock) {
            lastReverseForwardStatusRaw = null
            lastReverseForwardStatus = null
        }
    }

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
