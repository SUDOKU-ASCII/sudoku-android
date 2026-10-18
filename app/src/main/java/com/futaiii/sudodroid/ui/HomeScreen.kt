@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.futaiii.sudodroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.ProxyMode
import com.futaiii.sudodroid.net.TrafficHub
import com.futaiii.sudodroid.net.TrafficSnapshot
import com.futaiii.sudodroid.ui.theme.SpeedFigure
import com.futaiii.sudodroid.util.TrafficStatsFormatter

enum class RunMode(val label: String) {
    VPN("VPN"),
    PROXY_ONLY("Proxy only")
}

/**
 * The /traffic stream is only subscribed while this is composed with an active core, and
 * collectAsStateWithLifecycle drops it shortly after the app leaves the foreground.
 */
@Composable
fun rememberTraffic(active: Boolean): TrafficSnapshot {
    if (!active) return TrafficSnapshot()
    val snapshot by TrafficHub.snapshots.collectAsStateWithLifecycle()
    return snapshot
}

@Composable
fun HomeScreen(
    state: AppState,
    widthClass: WidthClass,
    runMode: RunMode,
    connecting: Boolean,
    onRunModeChange: (RunMode) -> Unit,
    onTogglePower: () -> Unit,
    onProxyModeChange: (ProxyMode) -> Unit,
    onOpenNodes: () -> Unit,
    modifier: Modifier = Modifier
) {
    val traffic = rememberTraffic(state.isRunning)
    val node = state.activeNode
    val hero: @Composable () -> Unit = {
        HeroPanel(
            state = state,
            node = node,
            runMode = runMode,
            connecting = connecting,
            onRunModeChange = onRunModeChange,
            onTogglePower = onTogglePower,
            onOpenNodes = onOpenNodes
        )
    }
    val nodePanel: @Composable () -> Unit = { NodePanel(node = node, latencyMs = state.nodes.firstOrNull { it.isActive }?.latencyMs, onClick = onOpenNodes) }
    val routing: @Composable () -> Unit = {
        RoutingPanel(
            mode = state.globalProxySettings.proxyMode,
            running = state.isRunning,
            onSelect = onProxyModeChange
        )
    }
    val trafficPanel: @Composable () -> Unit = { TrafficPanel(traffic = traffic, running = state.isRunning) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = if (widthClass == WidthClass.Expanded) 1120.dp else 640.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ScreenTitle(
                title = "Sudodroid",
                subtitle = when {
                    state.isVpnRunning -> "VPN active"
                    state.isProxyOnlyRunning -> "Local proxy active"
                    else -> "Ready"
                }
            )
            if (widthClass == WidthClass.Expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        hero()
                        nodePanel()
                    }
                    Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        routing()
                        trafficPanel()
                    }
                }
            } else {
                hero()
                nodePanel()
                routing()
                trafficPanel()
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroPanel(
    state: AppState,
    node: NodeConfig?,
    runMode: RunMode,
    connecting: Boolean,
    onRunModeChange: (RunMode) -> Unit,
    onTogglePower: () -> Unit,
    onOpenNodes: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = state.isRunning
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PowerButton(
                running = state.isRunning,
                connecting = connecting && !state.isRunning,
                enabled = node != null || state.isRunning,
                onClick = onTogglePower
            )
            Text(
                text = when {
                    state.isRunning -> "Connected"
                    connecting -> "Connecting"
                    node == null -> "No node yet"
                    else -> "Not connected"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = if (state.isRunning) scheme.tertiary else scheme.onSurface
            )
            Text(
                text = when {
                    state.isVpnRunning -> "All device traffic is tunnelled"
                    state.isProxyOnlyRunning && node != null -> "Proxy on 127.0.0.1:${node.localPort}"
                    node == null -> "Add or import a node to begin"
                    else -> "Tap to ${if (runMode == RunMode.VPN) "start the VPN" else "start the local proxy"}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (node == null) {
                FilledTonalButton(onClick = onOpenNodes) { Text("Add a node") }
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            RunMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = runMode == mode,
                    onClick = { onRunModeChange(mode) },
                    enabled = !state.isRunning && !connecting,
                    shape = SegmentedButtonDefaults.itemShape(index, RunMode.entries.size),
                    modifier = Modifier.height(44.dp),
                    label = { Text(mode.label, maxLines = 1) }
                )
            }
        }
    }
}

@Composable
private fun NodePanel(node: NodeConfig?, latencyMs: Long?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Panel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        onClick = onClick
    ) {
        CenteredRow(modifier = Modifier.fillMaxWidth(), spacing = 14.dp) {
            Icon(Icons.Rounded.Dns, contentDescription = null, tint = scheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node?.let { it.name.ifBlank { it.host } } ?: "Choose a node",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = node?.let { formatHostPort(it.host, it.port) } ?: "No node selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (latencyMs != null) {
                Text("$latencyMs ms", style = MaterialTheme.typography.labelLarge, color = scheme.tertiary)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RoutingPanel(mode: ProxyMode, running: Boolean, onSelect: (ProxyMode) -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Eyebrow("Routing")
        ProxyModeSelector(selected = mode, onSelect = onSelect)
        Text(
            text = mode.describe(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (running) {
            Text(
                text = "Switches instantly; open connections finish on their current route.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun TrafficPanel(traffic: TrafficSnapshot, running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val dim = if (running && traffic.live) 1f else 0.45f
    Panel(modifier = Modifier.fillMaxWidth()) {
        Eyebrow("Live traffic")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedReadout(
                modifier = Modifier.weight(1f),
                label = "Download",
                value = TrafficStatsFormatter.formatRate(traffic.down),
                color = scheme.primary.copy(alpha = dim),
                down = true
            )
            SpeedReadout(
                modifier = Modifier.weight(1f),
                label = "Upload",
                value = TrafficStatsFormatter.formatRate(traffic.up),
                color = scheme.secondary.copy(alpha = dim),
                down = false
            )
        }
        Sparkline(
            history = traffic.history,
            downColor = scheme.primary,
            upColor = scheme.secondary,
            gridColor = scheme.outlineVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("Downloaded", TrafficStatsFormatter.formatBytes(traffic.downTotal), modifier = Modifier.weight(1f))
            Metric("Uploaded", TrafficStatsFormatter.formatBytes(traffic.upTotal), modifier = Modifier.weight(1f))
        }
        if (!running) {
            Text(
                "Connect to see live speed and usage.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.outline
            )
        }
    }
}

@Composable
private fun SpeedReadout(
    label: String,
    value: String,
    color: Color,
    down: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CenteredRow(spacing = 4.dp) {
            Icon(
                imageVector = if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = null,
                tint = color
            )
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = SpeedFigure.copy(fontSize = 28.sp, lineHeight = 32.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
