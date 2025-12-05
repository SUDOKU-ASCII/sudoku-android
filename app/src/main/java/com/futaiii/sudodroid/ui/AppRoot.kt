@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.futaiii.sudodroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Http
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futaiii.sudodroid.data.AeadMode
import com.futaiii.sudodroid.data.AsciiMode
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.ProxyMode
import com.futaiii.sudodroid.protocol.ShortLinkCodec
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun AppRoot(
    viewModel: AppViewModel,
    onToggleVpn: (Boolean) -> Unit,
    onSwitchNodeWhileRunning: (NodeConfig) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editorInitial by remember { mutableStateOf<NodeConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NodeConfig?>(null) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(state.error) {
        val err = state.error
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearError()
        }
    }

    if (showEditor) {
        NodeEditorDialog(
            initial = editorInitial,
            onDismiss = {
                showEditor = false
                editorInitial = null
            },
            onSubmit = {
                viewModel.saveNode(it)
                showEditor = false
                editorInitial = null
            },
            onImportLink = { link, name ->
                viewModel.importShortLink(link, name)
                showEditor = false
                editorInitial = null
            }
        )
    }

    pendingDelete?.let { node ->
        ConfirmDeleteDialog(
            nodeName = node.name.ifBlank { node.host },
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.deleteNode(node.id)
                pendingDelete = null
            }
        )
    }

    val activeNode = state.nodes.firstOrNull { it.isActive }?.node

    Scaffold(
        modifier = Modifier.statusBarsPadding(),
        topBar = {
            SudodroidTopBar(
                isRunning = state.isVpnRunning,
                activeNode = activeNode,
                onToggle = { onToggleVpn(state.isVpnRunning) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editorInitial = null
                    showEditor = true
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add node")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (state.nodes.isEmpty()) {
                EmptyState(
                    onAddNode = {
                        editorInitial = null
                        showEditor = true
                    }
                )
            } else {
                NodeList(
                    nodes = state.nodes,
                    onSelect = {
                        viewModel.selectNode(it.id)
                        if (state.isVpnRunning) {
                            onSwitchNodeWhileRunning(it)
                        }
                    },
                    onPing = { viewModel.pingNode(it) },
                    onEdit = {
                        editorInitial = it
                        showEditor = true
                    },
                    onDelete = { pendingDelete = it },
                    onCopyLink = {
                        val link = ShortLinkCodec.toLink(it)
                        clipboard.setText(AnnotatedString(link))
                        scope.launch {
                            snackbarHostState.showSnackbar("Copied short link")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SudodroidTopBar(
    isRunning: Boolean,
    activeNode: NodeConfig?,
    onToggle: () -> Unit
) {
    LargeTopAppBar(
        title = {
            Column {
                Text("Sudodroid", style = MaterialTheme.typography.headlineSmall)
                val subtitle = when {
                    isRunning && activeNode != null -> "Connected to ${activeNode.name.ifBlank { activeNode.host }}"
                    activeNode != null -> "Ready on ${activeNode.name.ifBlank { activeNode.host }}"
                    else -> "Add a node to get started"
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            FilledTonalButton(
                onClick = onToggle,
                enabled = activeNode != null,
                colors = if (isRunning) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                }
            ) {
                Icon(
                    if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isRunning) "Stop VPN" else "Start VPN")
            }
        },
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun EmptyState(onAddNode: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = "No nodes yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Add a sudoku server manually or paste a sudoku:// link.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = TextAlign.Center
        )
        Button(onClick = onAddNode) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add node")
        }
    }
}

@Composable
private fun NodeList(
    nodes: ImmutableList<NodeUi>,
    onSelect: (NodeConfig) -> Unit,
    onPing: (NodeConfig) -> Unit,
    onEdit: (NodeConfig) -> Unit,
    onDelete: (NodeConfig) -> Unit,
    onCopyLink: (NodeConfig) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 96.dp, top = 16.dp)
    ) {
        items(nodes, key = { it.node.id }) { nodeUi ->
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

@Composable
private fun NodeCard(
    nodeUi: NodeUi,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopyLink: () -> Unit
) {
    ElevatedCard(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (nodeUi.isActive) {
                MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        nodeUi.node.name.ifBlank { nodeUi.node.host },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${nodeUi.node.host}:${nodeUi.node.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (nodeUi.isActive) {
                    AssistChip(
                        onClick = onSelect,
                        label = { Text("Selected") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoChip(
                    icon = Icons.Outlined.Speed,
                    label = when (val latency = nodeUi.latencyMs) {
                        null -> "Latency --"
                        else -> "Latency ${latency} ms"
                    }
                )
                InfoChip(
                    icon = Icons.Outlined.Bolt,
                    label = nodeUi.node.aead.wireName.uppercase()
                )
                InfoChip(
                    icon = Icons.Outlined.Tune,
                    label = nodeUi.node.asciiMode.description
                )
                InfoChip(
                    icon = Icons.Outlined.Http,
                    label = "Local ${nodeUi.node.localPort}"
                )
                InfoChip(
                    icon = Icons.Outlined.NetworkCheck,
                    label = when (nodeUi.node.proxyMode) {
                        ProxyMode.PAC -> "PAC (${nodeUi.node.ruleUrls.size} rules)"
                        ProxyMode.GLOBAL -> "Global proxy"
                        ProxyMode.DIRECT -> "Bypass all"
                    }
                )
                InfoChip(
                    icon = Icons.Outlined.WifiTethering,
                    label = "Pad ${nodeUi.node.paddingMin}-${nodeUi.node.paddingMax}%"
                )
            }
            Divider()
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onPing, colors = IconButtonDefaults.iconButtonColors()) {
                    Icon(Icons.Default.Refresh, contentDescription = "Ping node")
                }
                IconButton(onClick = onCopyLink, colors = IconButtonDefaults.iconButtonColors()) {
                    Icon(Icons.Default.Link, contentDescription = "Copy short link")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

@Composable
private fun InfoChip(icon: ImageVector, label: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    )
}

@Composable
private fun NodeEditorDialog(
    initial: NodeConfig?,
    onDismiss: () -> Unit,
    onSubmit: (NodeConfig) -> Unit,
    onImportLink: (String, String?) -> Unit
) {
    val isEditing = initial != null
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(initial?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((initial?.port ?: 1080).toString()) }
    var key by rememberSaveable { mutableStateOf(initial?.key.orEmpty()) }
    var localPort by rememberSaveable { mutableStateOf((initial?.localPort ?: 1080).toString()) }
    var paddingMin by rememberSaveable { mutableStateOf((initial?.paddingMin ?: 5).toString()) }
    var paddingMax by rememberSaveable { mutableStateOf((initial?.paddingMax ?: 15).toString()) }
    var asciiMode by rememberSaveable { mutableStateOf(initial?.asciiMode ?: AsciiMode.PREFER_ENTROPY) }
    var aeadMode by rememberSaveable { mutableStateOf(initial?.aead ?: AeadMode.CHACHA20_POLY1305) }
    var proxyMode by rememberSaveable { mutableStateOf(initial?.proxyMode ?: ProxyMode.GLOBAL) }
    var ruleUrls by rememberSaveable { mutableStateOf(initial?.ruleUrls?.joinToString("\n") ?: "") }
    var enablePureDownlink by rememberSaveable { mutableStateOf(initial?.enablePureDownlink ?: true) }
    var shortLink by rememberSaveable { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (isEditing) "Edit node" else "Add node",
                    style = MaterialTheme.typography.headlineSmall
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        SectionCard(title = "Connection") {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Display name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = host,
                                    onValueChange = { host = it },
                                    label = { Text("Server host") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = port,
                                    onValueChange = { port = it.filter { ch: Char -> ch.isDigit() } },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(120.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = key,
                                onValueChange = { key = it },
                                label = { Text("Key (private or public)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = localPort,
                                onValueChange = { localPort = it.filter { ch: Char -> ch.isDigit() } },
                                label = { Text("Local mixed proxy port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item {
                        SectionCard(title = "Behavior") {
                            Text("ASCII preference", style = MaterialTheme.typography.labelMedium)
                            SingleChoiceSegmentedButtonRow {
                                AsciiMode.entries.forEachIndexed { index: Int, mode: AsciiMode ->
                                    val label = when (mode) {
                                        AsciiMode.PREFER_ASCII -> "ASCII"
                                        AsciiMode.PREFER_ENTROPY -> "Entropy"
                                    }
                                    SegmentedButton(
                                        selected = asciiMode == mode,
                                        onClick = { asciiMode = mode },
                                        shape = SegmentedButtonDefaults.itemShape(index, AsciiMode.entries.size),
                                        modifier = Modifier.height(40.dp),
                                        label = {
                                            Text(
                                                label,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("AEAD mode", style = MaterialTheme.typography.labelMedium)
                            SingleChoiceSegmentedButtonRow {
                                val buttonHeight = 60.dp
                                AeadMode.entries.forEachIndexed { index: Int, mode: AeadMode ->
                                    SegmentedButton(
                                        selected = aeadMode == mode,
                                        onClick = { aeadMode = mode },
                                        shape = SegmentedButtonDefaults.itemShape(index, AeadMode.entries.size),
                                        label = { 
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(buttonHeight),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    mode.wireName.uppercase(),
                                                    textAlign = TextAlign.Center,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        },
                                        modifier = Modifier.height(buttonHeight)
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = paddingMin,
                                    onValueChange = { paddingMin = it.filter { ch: Char -> ch.isDigit() } },
                                    label = { Text("Padding min %") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = paddingMax,
                                    onValueChange = { paddingMax = it.filter { ch: Char -> ch.isDigit() } },
                                    label = { Text("Padding max %") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    item {
                        SectionCard(title = "Proxy mode") {
                            Text("Routing", style = MaterialTheme.typography.labelMedium)
                            val proxyOptions = ProxyMode.entries
                            SingleChoiceSegmentedButtonRow {
                                proxyOptions.forEachIndexed { index: Int, mode: ProxyMode ->
                                    SegmentedButton(
                                        selected = proxyMode == mode,
                                        onClick = { proxyMode = mode },
                                        shape = SegmentedButtonDefaults.itemShape(index, proxyOptions.size),
                                        label = { Text(mode.label) }
                                    )
                                }
                            }
                            if (proxyMode == ProxyMode.PAC) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = ruleUrls,
                                    onValueChange = { ruleUrls = it },
                                    label = { Text("Rule URLs (one per line)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3
                                )
                            }
                        }
                    }
                    item {
                        SectionCard(title = "Downlink") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Switch(
                                    checked = !enablePureDownlink,
                                    onCheckedChange = { enablePureDownlink = !it }
                                )
                                Column {
                                    Text("Bandwidth-optimized downlink")
                                    Text(
                                        "Uses packed downlink; requires AEAD.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                    item {
                        SectionCard(title = "Short link import") {
                            OutlinedTextField(
                                value = shortLink,
                                onValueChange = { shortLink = it },
                                label = { Text("sudoku:// link") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        clipboard.getText()?.let { shortLink = it.text.trim() }
                                    }) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val trimmed = shortLink.trim()
                                    if (trimmed.isNotEmpty()) {
                                        // Auto-add prefix if missing for better UX, though Codec handles it too
                                        val linkToImport = if (trimmed.startsWith("sudoku://")) trimmed else "sudoku://$trimmed"
                                        onImportLink(linkToImport, name.takeIf { it.isNotBlank() })
                                    }
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Import")
                            }
                        }
                    }
                }
                if (errorText != null) {
                    Text(
                        errorText!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            val node = runCatching {
                                buildNodeConfig(
                                    initial = initial,
                                    name = name,
                                    host = host,
                                    port = port,
                                    key = key,
                                    localPort = localPort,
                                    paddingMin = paddingMin,
                                    paddingMax = paddingMax,
                                    asciiMode = asciiMode,
                                    aeadMode = aeadMode,
                                    proxyMode = proxyMode,
                                    ruleUrls = ruleUrls,
                                    enablePureDownlink = enablePureDownlink
                                )
                            }.getOrElse {
                                errorText = it.message
                                return@Button
                            }
                            onSubmit(node)
                        }
                    ) {
                        Text(if (isEditing) "Save" else "Add")
                    }
                }
            }
        }
    }
}

private fun buildNodeConfig(
    initial: NodeConfig?,
    name: String,
    host: String,
    port: String,
    key: String,
    localPort: String,
    paddingMin: String,
    paddingMax: String,
    asciiMode: AsciiMode,
    aeadMode: AeadMode,
    proxyMode: ProxyMode,
    ruleUrls: String,
    enablePureDownlink: Boolean
): NodeConfig {
    val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("Invalid server port")
    val parsedLocalPort = localPort.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("Invalid local port")
    val minPad = paddingMin.toIntOrNull() ?: 0
    val maxPad = paddingMax.toIntOrNull() ?: 0
    val (normalizedMin, normalizedMax) = if (minPad <= maxPad) minPad to maxPad else maxPad to minPad
    val sanitizedHost = host.trim()
    if (sanitizedHost.isBlank()) throw IllegalArgumentException("Host cannot be blank")
    if (key.trim().isEmpty()) throw IllegalArgumentException("Key cannot be blank")
    val sanitizedRuleUrls = ruleUrls.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    return NodeConfig(
        id = initial?.id ?: UUID.randomUUID().toString(),
        name = name.ifBlank { sanitizedHost },
        host = sanitizedHost,
        port = parsedPort,
        key = key.trim(),
        asciiMode = asciiMode,
        aead = aeadMode,
        enablePureDownlink = enablePureDownlink,
        paddingMin = normalizedMin,
        paddingMax = normalizedMax,
        localPort = parsedLocalPort,
        proxyMode = proxyMode,
        ruleUrls = if (proxyMode == ProxyMode.PAC) sanitizedRuleUrls else emptyList(),
        createdAt = initial?.createdAt ?: System.currentTimeMillis()
    )
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    nodeName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove node") },
        text = { Text("Delete \"$nodeName\"?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
