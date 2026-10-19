@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.futaiii.sudodroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.futaiii.sudodroid.data.NodeConfig

@Composable
fun NodesScreen(
    state: AppState,
    widthClass: WidthClass,
    onAdd: () -> Unit,
    onSelect: (NodeConfig) -> Unit,
    onPing: (NodeConfig) -> Unit,
    onEdit: (NodeConfig) -> Unit,
    onDelete: (NodeConfig) -> Unit,
    onCopyLink: (NodeConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = if (widthClass == WidthClass.Expanded) 1120.dp else 720.dp)
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
                    title = "Nodes",
                    subtitle = if (state.nodes.isEmpty()) "Nothing here yet" else "${state.nodes.size} saved",
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Add")
                }
            }
            if (state.nodes.isEmpty()) {
                EmptyNodes(onAdd = onAdd)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 340.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(state.nodes, key = { it.node.id }) { nodeUi ->
                        NodeCard(
                            nodeUi = nodeUi,
                            onSelect = { onSelect(nodeUi.node) },
                            onPing = { onPing(nodeUi.node) },
                            onEdit = { onEdit(nodeUi.node) },
                            onDelete = { onDelete(nodeUi.node) },
                            onCopyLink = { onCopyLink(nodeUi.node) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeCard(
    nodeUi: NodeUi,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopyLink: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val node = nodeUi.node
    Panel(
        modifier = Modifier.fillMaxWidth(),
        highlighted = nodeUi.isActive,
        contentPadding = PaddingValues(start = 20.dp, end = 10.dp, top = 18.dp, bottom = 8.dp),
        onClick = onSelect
    ) {
        CenteredRow(modifier = Modifier.fillMaxWidth(), spacing = 14.dp) {
            Icon(
                imageVector = if (nodeUi.isActive) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (nodeUi.isActive) "Selected" else "Not selected",
                tint = scheme.onSurface
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.name.ifBlank { node.host },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatHostPort(node.host, node.port),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = nodeUi.latencyMs?.let { "$it ms" } ?: "-- ms",
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    nodeUi.latencyMs == null -> scheme.outline
                    nodeUi.latencyMs < 250 -> scheme.tertiary
                    else -> scheme.primary
                },
                modifier = Modifier.padding(end = 10.dp)
            )
        }
        FlowRow(
            modifier = Modifier.padding(end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Tag(node.aead.wireName)
            Tag(node.asciiMode.description)
            Tag("Local ${node.localPort}")
            Tag("Pad ${node.paddingMin}-${node.paddingMax}%")
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPing, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Speed, contentDescription = "Test latency", tint = scheme.onSurface, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onCopyLink, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Link, contentDescription = "Copy short link", tint = scheme.onSurface, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Edit, contentDescription = "Edit", tint = scheme.onSurface, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = scheme.onSurface, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyNodes(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 56.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(56.dp)
        )
        Text("No nodes yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Add a Sudoku server by hand, or paste a sudoku:// link and it imports in one step.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Add node")
        }
    }
}

internal fun formatHostPort(host: String, port: Int): String {
    val sanitized = host.trim().removeSurrounding("[", "]")
    return if (sanitized.contains(":")) "[$sanitized]:$port" else "$sanitized:$port"
}
