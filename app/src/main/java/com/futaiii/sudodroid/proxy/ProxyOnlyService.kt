package com.futaiii.sudodroid.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.futaiii.sudodroid.MainActivity
import com.futaiii.sudodroid.R
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.net.GoCoreClient
import com.futaiii.sudodroid.runtime.ServiceRuntimeSupport
import com.futaiii.sudodroid.vpn.SudokuVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProxyOnlyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var notificationJob: Job? = null
    private var activeNode: NodeConfig? = null
    private var coreRunning = false
    private var skipStopCoreOnDestroy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val skipStopCore = intent.getBooleanExtra(EXTRA_SKIP_STOP_CORE, false)
                skipStopCoreOnDestroy = skipStopCore
                scope.launch {
                    stopProxyInternal(stopCore = !skipStopCore)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_SWITCH_NODE -> {
                if (SudokuVpnService.isRunning) {
                    Log.w(TAG, "VPN is running; ignore proxy-only switch")
                    return START_NOT_STICKY
                }
                val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
                if (nodeId.isNullOrBlank()) {
                    Log.w(TAG, "Switch requested without node id")
                    return START_STICKY
                }
                scope.launch {
                    val node = ServiceRuntimeSupport.selectNode(this@ProxyOnlyService, nodeId, allowFallback = false)
                    if (node == null) {
                        Log.e(TAG, "Switch failed: node not found")
                        return@launch
                    }
                    if (!coreRunning) {
                        startProxyWithNode(node)
                        return@launch
                    }
                    if (activeNode?.id == node.id) return@launch
                    startCore(node)
                    activeNode = node
                    activeNodeIdFlow.value = node.id
                    val mgr = getSystemService(NotificationManager::class.java)
                    mgr.notify(NOTI_ID, buildNotification(node, GoCoreClient.getTrafficStats()))
                }
                return START_STICKY
            }

            else -> {
                if (SudokuVpnService.isRunning) {
                    Log.w(TAG, "VPN is running; proxy-only mode cannot start")
                    stopSelf()
                    return START_NOT_STICKY
                }

                ensureNotificationChannel()
                startForeground(NOTI_ID, buildNotification(activeNode, null))

                val requestedId = intent?.getStringExtra(EXTRA_NODE_ID)
                startJob?.cancel()
                startJob = scope.launch {
                    val node = ServiceRuntimeSupport.selectNode(this@ProxyOnlyService, requestedId, allowFallback = true)
                    if (node == null) {
                        Log.e(TAG, "Cannot start proxy-only mode: node not found")
                        stopProxyInternal(stopCore = true)
                        stopSelf()
                        return@launch
                    }
                    if (coreRunning && activeNode?.id == node.id) {
                        return@launch
                    }
                    startProxyWithNode(node)
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only tear down the Go core if VPN is NOT running and we're not handing off
        // to VPN. During handoff, SudokuVpnService will restart the core itself.
        if (!skipStopCoreOnDestroy && !SudokuVpnService.isRunning) {
            runCatching { GoCoreClient.stop() }
        }
        stopNotificationUpdates()
        statusFlow.value = false
        activeNodeIdFlow.value = null
        coreRunning = false
        scope.cancel()
    }

    private suspend fun startProxyWithNode(node: NodeConfig) {
        try {
            startCore(node)
            activeNode = node
            coreRunning = true
            statusFlow.value = true
            activeNodeIdFlow.value = node.id
            startNotificationUpdates()
        } catch (e: CancellationException) {
            Log.i(TAG, "Proxy-only start cancelled")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start proxy-only mode", e)
            stopProxyInternal(stopCore = true)
            stopSelf()
        }
    }

    private suspend fun startCore(node: NodeConfig) {
        ServiceRuntimeSupport.startCore(this, node)
    }

    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        val mgr = getSystemService(NotificationManager::class.java)
        notificationJob = scope.launch {
            ServiceRuntimeSupport.pollTrafficStats(isRunning = { coreRunning }) { stats ->
                mgr.notify(NOTI_ID, buildNotification(activeNode, stats))
            }
        }
    }

    private fun stopNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = null
    }

    private fun stopProxyInternal(stopCore: Boolean) {
        skipStopCoreOnDestroy = !stopCore
        startJob?.cancel()
        startJob = null
        stopNotificationUpdates()
        // Only tear down the Go core if asked and VPN is NOT running.
        if (stopCore && !SudokuVpnService.isRunning) {
            runCatching { GoCoreClient.stop() }
        }
        coreRunning = false
        activeNode = null
        statusFlow.value = false
        activeNodeIdFlow.value = null
        runCatching { stopForegroundCompat() }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.proxy_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.proxy_channel_description)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(node: NodeConfig?, stats: GoCoreClient.TrafficStats?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val base = node?.let {
            val name = it.name.ifBlank { it.host }
            "Proxy only · $name · 127.0.0.1:${it.localPort}"
        } ?: getString(R.string.notification_proxy_running)

        val text = if (stats == null) {
            base
        } else {
            "$base\n${ServiceRuntimeSupport.formatTrafficText(stats)}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(base)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val EXTRA_NODE_ID = "nodeId"
        const val ACTION_STOP = "com.futaiii.sudodroid.proxy.STOP"
        const val ACTION_SWITCH_NODE = "com.futaiii.sudodroid.proxy.SWITCH_NODE"
        const val EXTRA_SKIP_STOP_CORE = "com.futaiii.sudodroid.proxy.SKIP_STOP_CORE"

        private const val CHANNEL_ID = "sudoku_proxy_only"
        private const val NOTI_ID = 2
        private const val TAG = "ProxyOnlyService"

        private val statusFlow = MutableStateFlow(false)
        val status: StateFlow<Boolean> = statusFlow

        private val activeNodeIdFlow = MutableStateFlow<String?>(null)
        val activeNodeId: StateFlow<String?> = activeNodeIdFlow

        /**
         * Called synchronously by SudokuVpnService before it starts its own core.
         * This immediately marks proxy-only as stopped so the service's coroutine
         * won't issue a delayed GoCoreClient.stop() that kills the VPN's core.
         * A cleanup intent is sent to properly tear down the foreground notification.
         */
        fun requestStopFromVpn(context: Context) {
            // Synchronously clear status to prevent any in-flight coroutine
            // from calling GoCoreClient.stop() after VPN starts.
            statusFlow.value = false
            activeNodeIdFlow.value = null
            // Fire-and-forget cleanup intent for foreground / notification.
            runCatching {
                context.startService(Intent(context, ProxyOnlyService::class.java).apply {
                    action = ACTION_STOP
                    putExtra(EXTRA_SKIP_STOP_CORE, true)
                })
            }
        }
    }
}
