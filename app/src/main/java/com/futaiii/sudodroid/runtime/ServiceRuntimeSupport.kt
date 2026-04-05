package com.futaiii.sudodroid.runtime

import android.content.Context
import com.futaiii.sudodroid.SudodroidApp
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.net.GoCoreClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.util.Locale

internal object ServiceRuntimeSupport {
    suspend fun selectNode(context: Context, nodeId: String?, allowFallback: Boolean): NodeConfig? {
        val list = app(context).nodeRepository.nodes.first()
        if (list.isEmpty()) return null
        if (nodeId.isNullOrBlank()) return list.first()
        return list.find { it.id == nodeId } ?: if (allowFallback) list.first() else null
    }

    suspend fun startCore(context: Context, node: NodeConfig) {
        val repo = app(context).nodeRepository
        val global = repo.globalProxySettings.first()
        val json = GoCoreClient.buildConfigJson(node, global)
        GoCoreClient.start(json)
        GoCoreClient.resetTrafficStats()
    }

    suspend fun pollTrafficStats(
        isRunning: () -> Boolean,
        onUpdate: (GoCoreClient.TrafficStats?) -> Unit
    ) {
        var lastStats: GoCoreClient.TrafficStats? = null
        var idleCycles = 0
        var emittedInitial = false

        while (currentCoroutineContext().isActive && isRunning()) {
            val stats = GoCoreClient.getTrafficStats()
            val changed = stats != lastStats
            if (!emittedInitial || changed) {
                emittedInitial = true
                lastStats = stats
                idleCycles = 0
                onUpdate(stats)
            } else {
                idleCycles++
            }
            delay(trafficPollDelayMs(changed = changed, hasStats = stats != null, idleCycles = idleCycles))
        }
    }

    internal fun trafficPollDelayMs(changed: Boolean, hasStats: Boolean, idleCycles: Int): Long = when {
        !hasStats -> 5_000L
        changed -> 1_000L
        idleCycles < 2 -> 2_000L
        else -> 5_000L
    }

    fun formatTrafficText(stats: GoCoreClient.TrafficStats): String {
        val directTx = formatBytes(stats.directTx)
        val directRx = formatBytes(stats.directRx)
        val proxyTx = formatBytes(stats.proxyTx)
        val proxyRx = formatBytes(stats.proxyRx)
        return "直连 ↑$directTx ↓$directRx | 代理 ↑$proxyTx ↓$proxyRx"
    }

    private fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L).toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var scaled = value
        var unitIndex = 0
        while (scaled >= 1024 && unitIndex < units.lastIndex) {
            scaled /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${scaled.toLong()}${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f%s", scaled, units[unitIndex])
        }
    }

    private fun app(context: Context): SudodroidApp {
        return context.applicationContext as SudodroidApp
    }
}
