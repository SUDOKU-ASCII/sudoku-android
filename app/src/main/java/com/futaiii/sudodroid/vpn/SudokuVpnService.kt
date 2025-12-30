package com.futaiii.sudodroid.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.futaiii.sudodroid.MainActivity
import com.futaiii.sudodroid.R
import com.futaiii.sudodroid.SudodroidApp
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.net.GoCoreClient
import com.futaiii.sudodroid.qs.VpnTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

class SudokuVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var activeNode: NodeConfig? = null
    private var tunnelStarted = false
    private var notificationJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopVpnInternal()
                    // Mark this start as finished so the system does not
                    // attempt to restart the service.
                    stopSelf()
                    START_NOT_STICKY
                }

                ACTION_SWITCH_NODE -> {
                    if (!tunnelStarted) {
                        Log.w(TAG, "Switch node requested but VPN is not running; ignoring")
                        return START_STICKY
                    }
                    val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
                    if (nodeId.isNullOrBlank()) {
                        Log.w(TAG, "Switch node requested without node ID; ignoring")
                        return START_STICKY
                    }
                    scope.launch {
                        val node = selectNode(nodeId)
                        if (node == null) {
                            Log.e(TAG, "Switch node failed: node not found for id=$nodeId")
                            return@launch
                        }
                        val current = activeNode
                        if (current != null && current.id == node.id) {
                            Log.i(TAG, "Switch node requested to current active node; skipping")
                            return@launch
                        }
                        // Preserve existing local port to keep tunnel wiring stable.
                        val effectiveNode = if (current != null) {
                            node.copy(localPort = current.localPort)
                        } else {
                            node
                        }
                        activeNode = effectiveNode
                        try {
                            startCore(effectiveNode)
                            Log.i(TAG, "Switched core to node ${effectiveNode.name.ifBlank { effectiveNode.host }}")
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to switch core to new node", e)
                        }
                    }
                    START_STICKY
                }

                else -> {
                    if (tunnelStarted) {
                        Log.i(TAG, "VPN already running, ignoring duplicate start")
                        return START_STICKY
                    }
                    statusFlow.value = false
                    val nodeId = intent?.getStringExtra(EXTRA_NODE_ID)
                    val node = selectNode(nodeId)
                    if (node == null) {
                        Log.e(TAG, "Cannot start VPN: node not found")
                        stopSelf()
                        START_NOT_STICKY
                    } else {
                        activeNode = node

                        ensureNotificationChannel()
                        startForeground(NOTI_ID, buildNotification(null))
                        scope.launch {
                            try {
                                startCore(node)
                                buildVpnInterface(node)
                                startTunnel(node)
                                statusFlow.value = true
                                VpnTileService.requestListeningState(this@SudokuVpnService)
                                startNotificationUpdates()
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to start VPN", e)
                                statusFlow.value = false
                                VpnTileService.requestListeningState(this@SudokuVpnService)
                                stopSelf()
                            }
                        }
                        START_STICKY
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onStartCommand crashed", e)
            statusFlow.value = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        // System is revoking VPN permissions or shutting down the profile.
        scope.launch {
            stopVpnInternal()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // If the system destroys the service without an explicit STOP action,
        // still make a best-effort cleanup.
        runBlocking { stopVpnInternal() }
        scope.cancel()
    }

    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        val mgr = getSystemService(NotificationManager::class.java)
        notificationJob = scope.launch {
            while (true) {
                val stats = GoCoreClient.getTrafficStats()
                mgr.notify(NOTI_ID, buildNotification(stats))
                delay(1_000)
            }
        }
    }

    private fun stopNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = null
    }

    private fun buildVpnInterface(node: NodeConfig) {
        tunInterface?.close()
        val builder = Builder()
            .setSession("Sudodroid")
            .setBlocking(false)
            // Mirror sockstun defaults as closely as possible.
            .setMtu(8500)
            .addAddress("198.18.0.1", 32)
            .addAddress("fc00::1", 128)
            // All traffic through VPN.
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0) // IPv6 Route
            // Use mapped DNS virtual address so hev-socks5-tunnel's mapdns
            // can rewrite DNS answers and keep real resolution inside the tunnel.
            .addDnsServer("198.18.0.2")
        
        // Try to exclude self
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.addDisallowedApplication(packageName)
            } else {
                // For older Android versions, we might need a different approach or just hope for the best
                builder.addDisallowedApplication(packageName)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Failed to exclude app from VPN", e)
        }
        
        tunInterface = builder.establish() ?: throw IllegalStateException("Failed to establish VPN interface")
    }

    private fun startTunnel(node: NodeConfig) {
        if (!Socks5TunnelNative.isLoaded()) {
            throw IllegalStateException("hev-socks5-tunnel JNI not loaded")
        }
        val pfd = tunInterface ?: throw IllegalStateException("TUN interface missing")

        // Use the same FD for both the VPN interface and the native tunnel,
        // so closing tunInterface will also close the FD held by hev-socks5-tunnel.
        val fd = pfd.fd

        val configFile = File(cacheDir, "tproxy.conf")
        val yaml = buildTunnelConfig(node)
        configFile.writeText(yaml)

        val res = Socks5TunnelNative.start(configFile.absolutePath, fd)
        if (res != 0) {
            throw IllegalStateException("hev-socks5-tunnel exited with code $res")
        }
        tunnelStarted = true
    }

    private fun stopVpnInternal() {
        if (!tunnelStarted && tunInterface == null) {
            statusFlow.value = false
            VpnTileService.requestListeningState(this)
            return
        }

        Log.i(TAG, "Stopping VPN...")

        stopNotificationUpdates()

        // Stop components in reverse order, mirroring start sequence.
        try {
            Socks5TunnelNative.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping tunnel", e)
        }

        try {
            GoCoreClient.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping core", e)
        }

        try {
            tunInterface?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing TUN", e)
        }
        tunInterface = null
        tunnelStarted = false

        try {
            stopForeground(true)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to stop foreground", e)
        }

        statusFlow.value = false
        VpnTileService.requestListeningState(this)
        Log.i(TAG, "VPN stopped")
    }

    private fun selectNode(nodeId: String?): NodeConfig? = runBlocking {
        val repo = (application as SudodroidApp).nodeRepository
        val list = repo.nodes.first()
        return@runBlocking when {
            nodeId != null -> list.find { it.id == nodeId }
            else -> list.firstOrNull()
        }
    }

    private fun buildTunnelConfig(node: NodeConfig): String {
        val socksAddr = "127.0.0.1"
        val socksPort = node.localPort
        val mtu = 8500
        val ipv4 = "198.18.0.1"
        val ipv6 = "fc00::1"

        // Config modeled after the official sockstun TProxyService:
        // - tunnel.{mtu,ipv4,ipv6} match the VPN interface.
        // - socks5.* points at the local Sudoku mixed proxy.
        // - mapdns rewrites DNS answers into a virtual IPv4 range so
        //   lwIP + hev-mapped-dns can recover hostnames later when
        //   opening outbound TCP connections through Sudoku.
        return """
            tunnel:
              mtu: $mtu
              ipv4: $ipv4
              ipv6: '$ipv6'
            socks5:
              port: $socksPort
              address: '$socksAddr'
              udp: 'tcp'
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 240.0.0.0
              netmask: 240.0.0.0
              cache-size: 10000
            misc:
              task-stack-size: 81920
              log-level: debug
        """.trimIndent()
    }

    private fun startCore(node: NodeConfig) {
        val json = GoCoreClient.buildConfigJson(node)
        GoCoreClient.start(json)
        GoCoreClient.resetTrafficStats()
    }
    
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.vpn_channel_description)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(stats: GoCoreClient.TrafficStats?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = stats?.let { formatTrafficText(it) } ?: getString(R.string.notification_running)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun formatTrafficText(stats: GoCoreClient.TrafficStats): String {
        val directTx = formatBytes(stats.directTx)
        val directRx = formatBytes(stats.directRx)
        val proxyTx = formatBytes(stats.proxyTx)
        val proxyRx = formatBytes(stats.proxyRx)
        return "直连 ↑$directTx ↓$directRx | 代理 ↑$proxyTx ↓$proxyRx"
    }

    private fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L).toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var v = value
        var idx = 0
        while (v >= 1024 && idx < units.lastIndex) {
            v /= 1024
            idx++
        }
        return if (idx == 0) {
            "${v.toLong()}${units[idx]}"
        } else {
            String.format(Locale.US, "%.1f%s", v, units[idx])
        }
    }

    companion object {
        const val EXTRA_NODE_ID = "nodeId"
        const val ACTION_STOP = "com.futaiii.sudodroid.vpn.STOP"
        const val ACTION_SWITCH_NODE = "com.futaiii.sudodroid.vpn.SWITCH_NODE"
        private const val CHANNEL_ID = "sudoku_vpn"
        private const val NOTI_ID = 1
        private const val TAG = "SudokuVpnService"
        private val statusFlow = MutableStateFlow(false)
        val status: StateFlow<Boolean> = statusFlow
        val isRunning: Boolean
            get() = statusFlow.value
    }
}
