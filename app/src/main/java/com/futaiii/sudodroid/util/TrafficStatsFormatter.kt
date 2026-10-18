package com.futaiii.sudodroid.util

import com.futaiii.sudodroid.net.TrafficSnapshot
import java.util.Locale

object TrafficStatsFormatter {
    private val byteUnits = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    fun formatBytes(bytes: Long): String = scale(bytes, byteUnits)

    /** Throughput such as "1.2 MB/s"; one decimal keeps idle text stable so the notification is not rewritten. */
    fun formatRate(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

    /** "↑ 12 KB/s  ↓ 1.3 MB/s · 84.2 MB". */
    fun formatNotification(snapshot: TrafficSnapshot): String {
        val total = snapshot.upTotal + snapshot.downTotal
        return "↑ ${formatRate(snapshot.up)}  ↓ ${formatRate(snapshot.down)} · ${formatBytes(total)}"
    }

    private fun scale(value: Long, units: Array<String>): String {
        var scaled = value.coerceAtLeast(0L).toDouble()
        var unitIndex = 0
        while (scaled >= 1024 && unitIndex < units.lastIndex) {
            scaled /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${scaled.toLong()} ${units[0]}"
        } else {
            String.format(Locale.US, "%.1f %s", scaled, units[unitIndex])
        }
    }
}
