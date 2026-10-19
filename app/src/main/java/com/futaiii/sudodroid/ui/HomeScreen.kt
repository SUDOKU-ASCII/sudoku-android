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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.size
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
            connecting = connecting,
            onTogglePower = onTogglePower,
            onOpenNodes = onOpenNodes
        )
    }
    val runPanel: @Composable () -> Unit = {
        RunModePanel(
            runMode = runMode,
            locked = state.isRunning || connecting,
            node = node,
            onChange = onRunModeChange
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
                        runPanel()
                    }
                    Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        trafficPanel()
                        routing()
                    }
                }
            } else {
                hero()
                trafficPanel()
                routing()
                runPanel()
                nodePanel()
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroPanel(
    state: AppState,
    node: NodeConfig?,
    connecting: Boolean,
    onTogglePower: () -> Unit,
    onOpenNodes: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val statusText = when {
        state.isRunning -> "Connected"
        connecting -> "Connecting"
        node == null -> "No node yet"
        else -> "Not connected"
    }
    Panel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = state.isRunning,
        contentPadding = PaddingValues(start = 24.dp, end = 20.dp, top = 22.dp, bottom = 22.dp)
    ) {
        CenteredRow(modifier = Modifier.fillMaxWidth(), spacing = 16.dp) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = scheme.surfaceContainerLow) {
                    CenteredRow(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        spacing = 8.dp
                    ) {
                        Dot(if (state.isRunning) scheme.tertiary else scheme.onSurfaceVariant, 7.dp)
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state.isRunning) scheme.tertiary else scheme.onSurface
                        )
                    }
                }
                Text(
                    text = node?.let { it.name.ifBlank { it.host } } ?: "Add a node",
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        node == null -> "Import a sudoku:// link or add one by hand"
                        state.isProxyOnlyRunning -> "Local proxy on 127.0.0.1:${node.localPort}"
                        else -> formatHostPort(node.host, node.port)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (node == null) {
                    FilledTonalButton(onClick = onOpenNodes) { Text("Add a node") }
                }
            }
            PowerButton(
                running = state.isRunning,
                connecting = connecting && !state.isRunning,
                enabled = node != null || state.isRunning,
                onClick = onTogglePower,
                diameter = 96.dp
            )
        }
    }
}

@Composable
private fun RunModePanel(
    runMode: RunMode,
    locked: Boolean,
    node: NodeConfig?,
    onChange: (RunMode) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val vpn = runMode == RunMode.VPN
    Panel(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        CenteredRow(modifier = Modifier.fillMaxWidth(), spacing = 16.dp) {
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHighest) {
                Icon(
                    Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = if (vpn) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("VPN mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (vpn) "All device traffic is tunnelled"
                    else "Local proxy only${node?.let { " · 127.0.0.1:${it.localPort}" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Switch(
                checked = vpn,
                onCheckedChange = { onChange(if (it) RunMode.VPN else RunMode.PROXY_ONLY) },
                enabled = !locked
            )
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
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHighest) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = scheme.onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TrafficPanel(traffic: TrafficSnapshot, running: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Panel(modifier = Modifier.fillMaxWidth()) {
        Eyebrow("Live traffic")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeedTile(
                modifier = Modifier.weight(1f),
                label = "Download",
                rate = TrafficStatsFormatter.formatRate(traffic.down),
                total = TrafficStatsFormatter.formatBytes(traffic.downTotal),
                down = true,
                dim = !(running && traffic.live)
            )
            SpeedTile(
                modifier = Modifier.weight(1f),
                label = "Upload",
                rate = TrafficStatsFormatter.formatRate(traffic.up),
                total = TrafficStatsFormatter.formatBytes(traffic.upTotal),
                down = false,
                dim = !(running && traffic.live)
            )
        }
        Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerLow) {
            Sparkline(
                history = traffic.history,
                downColor = scheme.primary,
                upColor = scheme.onSurfaceVariant,
                gridColor = scheme.outlineVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .padding(vertical = 8.dp)
            )
        }
        if (!running) {
            Text(
                "Connect to see live speed and usage.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpeedTile(
    label: String,
    rate: String,
    total: String,
    down: Boolean,
    dim: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerLow) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CenteredRow(spacing = 6.dp) {
                Icon(
                    imageVector = if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    tint = if (down) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            }
            Text(
                text = rate,
                style = SpeedFigure.copy(fontSize = 26.sp, lineHeight = 30.sp),
                color = if (dim) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("Total $total", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}
