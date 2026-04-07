package com.futaiii.sudodroid.util

import com.futaiii.sudodroid.net.GoCoreClient
import java.util.Locale

object TrafficStatsFormatter {
    fun formatSummary(stats: GoCoreClient.TrafficStats): String {
        val directTx = formatBytes(stats.directTx)
        val directRx = formatBytes(stats.directRx)
        val proxyTx = formatBytes(stats.proxyTx)
        val proxyRx = formatBytes(stats.proxyRx)
        return "直连 ↑$directTx ↓$directRx | 代理 ↑$proxyTx ↓$proxyRx"
    }

    fun formatBytes(bytes: Long): String {
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
}
