package com.futaiii.sudodroid.net

import com.futaiii.sudodroid.data.ProxyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/** Where the in-process Sudoku core serves its external controller (loopback only, random secret). */
data class ControllerEndpoint(val port: Int, val secret: String) {
    val address: String get() = "127.0.0.1:$port"

    companion object {
        /** Picks a free loopback port and a fresh bearer token for one core start. */
        fun random(): ControllerEndpoint {
            val port = ServerSocket(0, 1, InetAddress.getByName(ControllerApi.HOST)).use { it.localPort }
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return ControllerEndpoint(port, bytes.joinToString("") { "%02x".format(it) })
        }
    }
}

@Serializable
data class TrafficSample(
    val up: Long = 0,
    val down: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0
)

@Serializable
data class ConnectionMeta(
    val network: String = "",
    val type: String = "",
    val host: String = "",
    val destinationIP: String = "",
    val destinationPort: String = "",
    val sourcePort: String = ""
)

@Serializable
data class ConnectionInfo(
    val id: String,
    val metadata: ConnectionMeta = ConnectionMeta(),
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = ""
) {
    val target: String
        get() {
            val name = metadata.host.ifBlank { metadata.destinationIP }
            return if (metadata.destinationPort.isBlank()) name else "$name:${metadata.destinationPort}"
        }

    /** DIRECT connections bypass the tunnel; anything else left through a Sudoku outbound. */
    val isDirect: Boolean get() = chains.lastOrNull().equals("DIRECT", ignoreCase = true) ||
        chains.firstOrNull().equals("DIRECT", ignoreCase = true)
}

@Serializable
data class ConnectionsSnapshot(
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val connections: List<ConnectionInfo> = emptyList()
)

/**
 * Client for the mihomo-compatible external controller that the Sudoku core exposes.
 *
 * It speaks plain HTTP/1.0 over a loopback socket: that keeps PATCH available on every Android
 * version, needs no extra dependency, and makes the server stream NDJSON without chunked framing.
 */
object ControllerApi {
    const val HOST = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val REQUEST_READ_TIMEOUT_MS = 4_000
    // The server pushes a sample every second, so silence this long means the peer is gone.
    private const val STREAM_READ_TIMEOUT_MS = 15_000
    private const val MAX_BACKOFF_MS = 10_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val endpointFlow = MutableStateFlow<ControllerEndpoint?>(null)

    /** The controller of the running core, or null while no core runs. */
    val endpoint: StateFlow<ControllerEndpoint?> = endpointFlow

    fun attach(endpoint: ControllerEndpoint?) {
        endpointFlow.value = endpoint
    }

    fun detach() = attach(null)

    /** mihomo calls PAC "rule"; the core accepts it (and "global"/"direct") on PATCH /configs. */
    fun wireMode(mode: ProxyMode): String = when (mode) {
        ProxyMode.PAC -> "rule"
        ProxyMode.GLOBAL -> "global"
        ProxyMode.DIRECT -> "direct"
    }

    /** Switches the routing mode of new connections without restarting the core. */
    suspend fun setMode(mode: ProxyMode): Boolean {
        val ep = endpointFlow.value ?: return false
        val response = request(ep, "PATCH", "/configs", """{"mode":"${wireMode(mode)}"}""")
        return response.status == 204 || response.status == 200
    }

    suspend fun connections(): ConnectionsSnapshot? {
        val ep = endpointFlow.value ?: return null
        val response = request(ep, "GET", "/connections")
        if (response.status != 200) return null
        return json.decodeFromString(ConnectionsSnapshot.serializer(), response.body)
    }

    suspend fun closeConnection(id: String) {
        val ep = endpointFlow.value ?: return
        request(ep, "DELETE", "/connections/$id")
    }

    suspend fun closeAllConnections() {
        val ep = endpointFlow.value ?: return
        request(ep, "DELETE", "/connections")
    }

    /**
     * One sample per second while collected. The stream is only open while somebody collects, and it
     * reconnects with a growing delay if the controller is not up yet.
     */
    fun trafficSamples(endpoint: ControllerEndpoint): Flow<TrafficSample> = flow {
        var failures = 0
        while (true) {
            lines(endpoint, "/traffic")
                .catch { if (it is CancellationException) throw it }
                .collect { line ->
                    val sample = runCatching { json.decodeFromString(TrafficSample.serializer(), line) }.getOrNull()
                        ?: return@collect
                    failures = 0
                    emit(sample)
                }
            failures++
            delay(minOf(1_000L * failures, MAX_BACKOFF_MS))
        }
    }

    internal data class Response(val status: Int, val body: String)

    internal suspend fun request(
        endpoint: ControllerEndpoint,
        method: String,
        path: String,
        body: String? = null
    ): Response = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, endpoint.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = REQUEST_READ_TIMEOUT_MS
            writeRequest(socket, method, path, endpoint.secret, body)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val status = readHead(reader)
            Response(status, reader.readText())
        }
    }

    /** Streams non-blank body lines of GET [path] until the server closes or the collector leaves. */
    internal fun lines(endpoint: ControllerEndpoint, path: String): Flow<String> = callbackFlow {
        val socket = Socket()
        val reader = launch(Dispatchers.IO) {
            try {
                socket.connect(InetSocketAddress(HOST, endpoint.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = STREAM_READ_TIMEOUT_MS
                writeRequest(socket, "GET", path, endpoint.secret, null)
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val status = readHead(input)
                if (status != 200) throw IOException("controller answered HTTP $status")
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isNotBlank()) trySend(line)
                }
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }
        awaitClose {
            runCatching { socket.close() }
            reader.cancel()
        }
    }.conflate()

    private fun writeRequest(socket: Socket, method: String, path: String, secret: String, body: String?) {
        val payload = body?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.0\r\n")
            append("Host: ").append(HOST).append("\r\n")
            append("Authorization: Bearer ").append(secret).append("\r\n")
            append("Accept: application/json\r\n")
            if (payload != null) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(payload.size).append("\r\n")
            }
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (payload != null) out.write(payload)
        out.flush()
    }

    /** Reads the status line and skips the headers; leaves [reader] at the start of the body. */
    private fun readHead(reader: BufferedReader): Int {
        val statusLine = reader.readLine() ?: throw IOException("controller closed the connection")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("bad status line: $statusLine")
        while (true) {
            val header = reader.readLine() ?: throw IOException("truncated response head")
            if (header.isEmpty()) break
        }
        return status
    }
}
