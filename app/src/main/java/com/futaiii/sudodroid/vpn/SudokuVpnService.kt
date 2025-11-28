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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class SudokuVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var activeNode: NodeConfig? = null
    private var tunnelStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            if (intent?.action == ACTION_STOP) {
                stopVpnInternal()
                // Mark this start as finished so the system does not
                // attempt to restart the service.
                stopSelf()
                START_NOT_STICKY
            } else {
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

                    startForeground(NOTI_ID, buildNotification())
                    scope.launch {
                        try {
                            startCore(node)
                            buildVpnInterface(node)
                            startTunnel(node)
                            statusFlow.value = true
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to start VPN", e)
                            statusFlow.value = false
                            stopSelf()
                        }
                    }
                    START_STICKY
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
            return
        }

        Log.i(TAG, "Stopping VPN...")

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
    }
    
    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.vpn_channel_description)
            mgr.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_NODE_ID = "nodeId"
        const val ACTION_STOP = "com.futaiii.sudodroid.vpn.STOP"
        private const val CHANNEL_ID = "sudoku_vpn"
        private const val NOTI_ID = 1
        private const val TAG = "SudokuVpnService"
        private val statusFlow = MutableStateFlow(false)
        val status: StateFlow<Boolean> = statusFlow
    }
}
