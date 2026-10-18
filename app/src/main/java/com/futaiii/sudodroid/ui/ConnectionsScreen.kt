@file:OptIn(ExperimentalMaterial3Api::class)

package com.futaiii.sudodroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.futaiii.sudodroid.net.ConnectionInfo
import com.futaiii.sudodroid.net.ConnectionsSnapshot
import com.futaiii.sudodroid.net.ControllerApi
import com.futaiii.sudodroid.util.TrafficStatsFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_MS = 2_000L
private const val MAX_ROWS = 200

@Composable
fun ConnectionsScreen(
    running: Boolean,
    widthClass: WidthClass,
    modifier: Modifier = Modifier
) {
    var snapshot by remember { mutableStateOf<ConnectionsSnapshot?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Polls only while the screen is visible, the app is in the foreground and a core is running.
    LaunchedEffect(running, lifecycleOwner) {
        if (!running) {
            snapshot = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                snapshot = runCatching { ControllerApi.connections() }.getOrNull() ?: snapshot
                delay(POLL_MS)
            }
        }
    }

    val rows = remember(snapshot) {
        snapshot?.connections
            ?.sortedByDescending { it.download + it.upload }
            ?.take(MAX_ROWS)
            .orEmpty()
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = if (widthClass == WidthClass.Expanded) 960.dp else 720.dp)
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScreenTitle(
                    title = "Activity",
                    subtitle = if (running) "${snapshot?.connections?.size ?: 0} open connections" else "Not connected",
                    modifier = Modifier.weight(1f)
                )
                if (rows.isNotEmpty()) {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { ControllerApi.closeAllConnections() }
                            snapshot = runCatching { ControllerApi.connections() }.getOrNull() ?: snapshot
                        }
                    }) { Text("Close all") }
                }
            }
            if (rows.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 56.dp, start = 16.dp, end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Rounded.SwapVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = if (running) "No open connections right now." else "Connect to see what is flowing through the tunnel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rows, key = { it.id }) { info ->
                        ConnectionRow(
                            info = info,
                            onClose = {
                                scope.launch {
                                    runCatching { ControllerApi.closeConnection(info.id) }
                                    snapshot = runCatching { ControllerApi.connections() }.getOrNull() ?: snapshot
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(info: ConnectionInfo, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    info.target.ifBlank { "unknown" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(info.metadata.network.uppercase().ifBlank { "TCP" })
                        append("  ·  ")
                        append(if (info.isDirect) "Direct" else "Proxy")
                        append("  ·  ↓ ")
                        append(TrafficStatsFormatter.formatBytes(info.download))
                        append("  ↑ ")
                        append(TrafficStatsFormatter.formatBytes(info.upload))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.isDirect) scheme.onSurfaceVariant else scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close connection", tint = scheme.onSurfaceVariant)
            }
        }
    }
}
