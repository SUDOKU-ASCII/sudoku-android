@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.futaiii.sudodroid.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futaiii.sudodroid.data.AeadMode
import com.futaiii.sudodroid.data.AsciiMode
import com.futaiii.sudodroid.data.DEFAULT_PAC_RULE_URLS
import com.futaiii.sudodroid.data.GlobalProxySettings
import com.futaiii.sudodroid.data.HttpMaskMode
import com.futaiii.sudodroid.data.HttpMaskMultiplex
import com.futaiii.sudodroid.data.IpMode
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.NodeInputValidator
import com.futaiii.sudodroid.data.ProxyMode
import com.futaiii.sudodroid.net.GoCoreClient
import com.futaiii.sudodroid.protocol.ShortLinkCodec
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private enum class RunningMode {
    VPN,
    PROXY_ONLY
}

@Composable
fun AppRoot(
    viewModel: AppViewModel,
    onToggleVpn: (Boolean) -> Unit,
    onSwitchNodeWhileRunning: (NodeConfig) -> Unit,
    onToggleProxyOnly: (Boolean) -> Unit,
    onSwitchNodeWhileProxyOnlyRunning: (NodeConfig) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val defaultPacRuleUrlsText = remember { DEFAULT_PAC_RULE_URLS.joinToString("\n") }
    var editorInitial by remember { mutableStateOf<NodeConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NodeConfig?>(null) }
    var pendingSwitch by remember { mutableStateOf<NodeConfig?>(null) }
    var runningMode by rememberSaveable { mutableStateOf(RunningMode.VPN) }
    var reverseDialUrl by rememberSaveable { mutableStateOf("") }
    var reverseListenAddr by rememberSaveable { mutableStateOf("") }
    var reverseInsecure by rememberSaveable { mutableStateOf(false) }
    var globalProxyMode by rememberSaveable { mutableStateOf(ProxyMode.PAC) }
    var globalIpMode by rememberSaveable { mutableStateOf(IpMode.DEFAULT) }
    var globalRuleUrlsText by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(state.error) {
        val err = state.error
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.reverseForwarderSettings, state.reverseForwardStatus.running) {
        if (!state.reverseForwardStatus.running) {
            reverseDialUrl = state.reverseForwarderSettings.dialUrl
            reverseListenAddr = state.reverseForwarderSettings.listenAddr
            reverseInsecure = state.reverseForwarderSettings.insecure
        }
    }

    LaunchedEffect(state.reverseForwardStatus.running, state.reverseForwardStatus.dialUrl, state.reverseForwardStatus.listenAddr, state.reverseForwardStatus.insecure) {
        if (state.reverseForwardStatus.running) {
            reverseDialUrl = state.reverseForwardStatus.dialUrl
            reverseListenAddr = state.reverseForwardStatus.listenAddr
            reverseInsecure = state.reverseForwardStatus.insecure
        }
    }

    LaunchedEffect(state.globalProxySettings) {
        globalProxyMode = state.globalProxySettings.proxyMode
        globalIpMode = state.globalProxySettings.ipMode
        globalRuleUrlsText = when {
            state.globalProxySettings.ruleUrls.isNotEmpty() -> state.globalProxySettings.ruleUrls.joinToString("\n")
            state.globalProxySettings.proxyMode == ProxyMode.PAC -> defaultPacRuleUrlsText
            else -> ""
        }
    }

    LaunchedEffect(state.isVpnRunning, state.isProxyOnlyRunning) {
        when {
            state.isProxyOnlyRunning -> runningMode = RunningMode.PROXY_ONLY
            state.isVpnRunning -> runningMode = RunningMode.VPN
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
                viewModel.importShortLink(link, name) {
                    showEditor = false
                    editorInitial = null
                }
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

    pendingSwitch?.let { node ->
        ConfirmSwitchDialog(
            targetName = node.name.ifBlank { node.host },
            onDismiss = { pendingSwitch = null },
            onConfirm = {
                viewModel.selectNode(node.id)
                when {
                    state.isVpnRunning -> onSwitchNodeWhileRunning(node)
                    state.isProxyOnlyRunning -> onSwitchNodeWhileProxyOnlyRunning(node)
                }
                pendingSwitch = null
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerShape = RoundedCornerShape(0.dp),
                drawerTonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Global Settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.Close, contentDescription = "Close settings")
                        }
                    }

                    GlobalSettingsCard(
                        proxyMode = globalProxyMode,
                        ipMode = globalIpMode,
                        ruleUrls = globalRuleUrlsText,
                        onProxyModeChange = { mode ->
                            globalProxyMode = mode
                            if (mode == ProxyMode.PAC && globalRuleUrlsText.lines().none { it.isNotBlank() }) {
                                globalRuleUrlsText = defaultPacRuleUrlsText
                            }
                        },
                        onIpModeChange = { globalIpMode = it },
                        onRuleUrlsChange = { globalRuleUrlsText = it },
                        onSave = {
                            viewModel.updateGlobalProxySettings(
                                proxyMode = globalProxyMode,
                                ipMode = globalIpMode,
                                ruleUrlsText = globalRuleUrlsText
                            )
                            scope.launch {
                                snackbarHostState.showSnackbar("Global network settings saved")
                            }
                        }
                    )

                    ReverseForwarderCard(
                        isVpnRunning = state.isVpnRunning,
                        isProxyOnlyRunning = state.isProxyOnlyRunning,
                        status = state.reverseForwardStatus,
                        isBusy = state.reverseForwardBusy,
                        dialUrl = reverseDialUrl,
                        listenAddr = reverseListenAddr,
                        insecure = reverseInsecure,
                        onDialUrlChange = {
                            reverseDialUrl = it
                            viewModel.saveReverseForwarderSettings(it, reverseListenAddr, reverseInsecure)
                        },
                        onListenAddrChange = {
                            reverseListenAddr = it
                            viewModel.saveReverseForwarderSettings(reverseDialUrl, it, reverseInsecure)
                        },
                        onInsecureChange = {
                            reverseInsecure = it
                            viewModel.saveReverseForwarderSettings(reverseDialUrl, reverseListenAddr, it)
                        },
                        onStart = {
                            viewModel.startReverseForwarder(
                                listenAddr = reverseListenAddr,
                                dialUrl = reverseDialUrl,
                                insecure = reverseInsecure
                            )
                        },
                        onStop = { viewModel.stopReverseForwarder() }
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.statusBarsPadding(),
            topBar = {
                SudodroidTopBar(
                    runningMode = runningMode,
                    isVpnRunning = state.isVpnRunning,
                    isProxyOnlyRunning = state.isProxyOnlyRunning,
                    activeNode = activeNode,
                    onToggleMode = {
                        if (activeNode == null) {
                            scope.launch { snackbarHostState.showSnackbar("Please add/select a node first") }
                        } else {
                            when (runningMode) {
                                RunningMode.VPN -> {
                                    if (state.isVpnRunning) {
                                        onToggleVpn(true)
                                        scope.launch { snackbarHostState.showSnackbar("VPN stopped") }
                                    } else {
                                        if (state.isProxyOnlyRunning) {
                                            onToggleProxyOnly(true)
                                            scope.launch {
                                                delay(700)
                                                onToggleVpn(false)
                                                snackbarHostState.showSnackbar("VPN mode started")
                                            }
                                        } else {
                                            onToggleVpn(false)
                                            scope.launch { snackbarHostState.showSnackbar("VPN mode started") }
                                        }
                                    }
                                }

                                RunningMode.PROXY_ONLY -> {
                                    if (state.isProxyOnlyRunning) {
                                        onToggleProxyOnly(true)
                                        scope.launch { snackbarHostState.showSnackbar("Proxy-only mode stopped") }
                                    } else {
                                        if (state.isVpnRunning) {
                                            onToggleVpn(true)
                                            scope.launch {
                                                delay(700)
                                                onToggleProxyOnly(false)
                                                snackbarHostState.showSnackbar("Proxy-only mode started")
                                            }
                                        } else {
                                            onToggleProxyOnly(false)
                                            scope.launch { snackbarHostState.showSnackbar("Proxy-only mode started") }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onLongPressTitle = {
                        if (activeNode == null) {
                            scope.launch { snackbarHostState.showSnackbar("Please add/select a node first") }
                        } else {
                            when (runningMode) {
                                RunningMode.VPN -> {
                                    runningMode = RunningMode.PROXY_ONLY
                                    if (state.isVpnRunning) {
                                        onToggleVpn(true)
                                        scope.launch {
                                            delay(700)
                                            onToggleProxyOnly(false)
                                            snackbarHostState.showSnackbar("Switched to proxy-only mode")
                                        }
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar("Switched to proxy-only mode") }
                                    }
                                }

                                RunningMode.PROXY_ONLY -> {
                                    runningMode = RunningMode.VPN
                                    if (state.isProxyOnlyRunning) {
                                        onToggleProxyOnly(true)
                                        scope.launch {
                                            delay(700)
                                            onToggleVpn(false)
                                            snackbarHostState.showSnackbar("Switched to VPN mode")
                                        }
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar("Switched to VPN mode") }
                                    }
                                }
                            }
                        }
                    }
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
            Box(
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
                        globalProxySettings = state.globalProxySettings,
                        onSelect = {
                            if ((state.isVpnRunning || state.isProxyOnlyRunning) && state.activeId != it.id) {
                                pendingSwitch = it
                            } else {
                                viewModel.selectNode(it.id)
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
}

@Composable
private fun SudodroidTopBar(
    runningMode: RunningMode,
    isVpnRunning: Boolean,
    isProxyOnlyRunning: Boolean,
    activeNode: NodeConfig?,
    onToggleMode: () -> Unit,
    onOpenDrawer: () -> Unit,
    onLongPressTitle: () -> Unit
) {
    val selectedModeRunning = when (runningMode) {
        RunningMode.VPN -> isVpnRunning
        RunningMode.PROXY_ONLY -> isProxyOnlyRunning
    }
    val buttonLabel = when (runningMode) {
        RunningMode.VPN -> if (selectedModeRunning) "Stop VPN" else "Start VPN"
        RunningMode.PROXY_ONLY -> if (selectedModeRunning) "Stop Proxy-only" else "Start Proxy-only"
    }

    LargeTopAppBar(
        title = {
            Column {
                Text(
                    "Sudodroid",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.pointerInput(isVpnRunning, isProxyOnlyRunning) {
                        detectTapGestures(onLongPress = { onLongPressTitle() })
                    }
                )
                val subtitle = when {
                    isVpnRunning && activeNode != null -> "VPN connected to ${activeNode.name.ifBlank { activeNode.host }}"
                    isProxyOnlyRunning && activeNode != null -> "Proxy-only on ${activeNode.name.ifBlank { activeNode.host }}"
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
                onClick = onToggleMode,
                enabled = activeNode != null,
                colors = when (runningMode) {
                    RunningMode.VPN -> if (selectedModeRunning) {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    RunningMode.PROXY_ONLY -> ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            ) {
                Icon(
                    if (selectedModeRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(buttonLabel)
            }
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Outlined.Tune, contentDescription = "Open settings")
            }
        },
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun GlobalSettingsCard(
    proxyMode: ProxyMode,
    ipMode: IpMode,
    ruleUrls: String,
    onProxyModeChange: (ProxyMode) -> Unit,
    onIpModeChange: (IpMode) -> Unit,
    onRuleUrlsChange: (String) -> Unit,
    onSave: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Global Network Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text("IP version preference", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow {
                IpMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = ipMode == mode,
                        onClick = { onIpModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, IpMode.entries.size),
                        modifier = Modifier.height(40.dp),
                        label = {
                            Text(
                                when (mode) {
                                    IpMode.DEFAULT -> "Default"
                                    IpMode.IPV4_ONLY -> "IPv4"
                                    IpMode.IPV6_PREFERRED -> "IPv6"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            Text("Routing mode", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow {
                ProxyMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = proxyMode == mode,
                        onClick = { onProxyModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ProxyMode.entries.size),
                        modifier = Modifier.height(40.dp),
                        label = {
                            Text(
                                mode.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            if (proxyMode == ProxyMode.PAC) {
                Text("PAC Rules", style = MaterialTheme.typography.labelMedium)
                val lines = ruleUrls.lines()
                lines.forEachIndexed { index, line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = line,
                            onValueChange = { newValue ->
                                val newLines = lines.toMutableList()
                                newLines[index] = newValue
                                onRuleUrlsChange(newLines.joinToString("\n"))
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = null
                        )
                        IconButton(
                            onClick = {
                                val newLines = lines.toMutableList()
                                newLines.removeAt(index)
                                onRuleUrlsChange(newLines.joinToString("\n"))
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete rule")
                        }
                    }
                }
                FilledTonalButton(
                    onClick = {
                        val newLines = lines.toMutableList()
                        newLines.add("")
                        onRuleUrlsChange(newLines.joinToString("\n"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Rule")
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = onSave) {
                    Icon(Icons.Outlined.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save Global Settings")
                }
            }
        }
    }
}

@Composable
private fun ReverseForwarderCard(
    isVpnRunning: Boolean,
    isProxyOnlyRunning: Boolean,
    status: GoCoreClient.ReverseForwardStatus,
    isBusy: Boolean,
    dialUrl: String,
    listenAddr: String,
    insecure: Boolean,
    onDialUrlChange: (String) -> Unit,
    onListenAddrChange: (String) -> Unit,
    onInsecureChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
        )
    ) {
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .padding(16.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WifiTethering, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Local Port Forwarder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(if (status.running) "Running" else "Stopped") }
                )
            }

            OutlinedTextField(
                value = dialUrl,
                onValueChange = onDialUrlChange,
                label = { Text("rev-dial (ws:// or wss://)") },
                singleLine = true,
                enabled = !status.running && !isBusy,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
            )
            OutlinedTextField(
                value = listenAddr,
                onValueChange = onListenAddrChange,
                label = { Text("rev-listen (e.g. 127.0.0.1:2222)") },
                singleLine = true,
                enabled = !status.running && !isBusy,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Switch(
                    checked = insecure,
                    onCheckedChange = onInsecureChange,
                    enabled = !status.running && !isBusy
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Allow insecure TLS")
                    Text(
                        "Only for self-signed wss endpoints",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val commandPreview = "./sudoku -rev-dial $dialUrl -rev-listen $listenAddr"
            Text(
                text = commandPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (status.lastError.isNotBlank()) {
                Text(
                    text = "Last error: ${status.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status.running) {
                    FilledTonalButton(
                        onClick = onStop,
                        enabled = !isBusy,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isBusy) "Stopping..." else "Stop")
                    }
                } else {
                    FilledTonalButton(
                        onClick = onStart,
                        enabled = !isBusy && dialUrl.isNotBlank() && listenAddr.isNotBlank()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isBusy) "Starting..." else "Start Forwarder")
                    }
                }
            }
        }
    }
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
    globalProxySettings: GlobalProxySettings,
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
                globalProxySettings = globalProxySettings,
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
    globalProxySettings: GlobalProxySettings,
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
                        formatHostPort(nodeUi.node.host, nodeUi.node.port),
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
                    label = when (globalProxySettings.proxyMode) {
                        ProxyMode.PAC -> "PAC (${globalProxySettings.ruleUrls.size} rules)"
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

private fun formatHostPort(host: String, port: Int): String {
    val sanitized = host.trim().removeSurrounding("[", "]")
    return if (sanitized.contains(":")) {
        "[$sanitized]:$port"
    } else {
        "$sanitized:$port"
    }
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
    var customTablesText by rememberSaveable {
        mutableStateOf(
            initial?.customTables?.takeIf { it.isNotEmpty() }?.joinToString("\n")
                ?: initial?.customTable.orEmpty()
        )
    }
    var aeadMode by rememberSaveable { mutableStateOf(initial?.aead ?: AeadMode.CHACHA20_POLY1305) }
    var disableHttpMask by rememberSaveable { mutableStateOf(initial?.disableHttpMask ?: false) }
    var httpMaskMode by rememberSaveable { mutableStateOf(initial?.httpMaskMode ?: HttpMaskMode.LEGACY) }
    var httpMaskTls by rememberSaveable { mutableStateOf(initial?.httpMaskTls ?: false) }
    var httpMaskHost by rememberSaveable { mutableStateOf(initial?.httpMaskHost.orEmpty()) }
    var httpMaskPathRoot by rememberSaveable { mutableStateOf(initial?.httpMaskPathRoot.orEmpty()) }
    var httpMaskMultiplex by rememberSaveable { mutableStateOf(initial?.httpMaskMultiplex ?: HttpMaskMultiplex.OFF) }
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
                    // Short link import moved to top for easier access
                    item {
                        SectionCard(title = "Quick Import") {
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
                                        val linkToImport = if (trimmed.startsWith("sudoku://")) trimmed else "sudoku://$trimmed"
                                        onImportLink(linkToImport, name.takeIf { it.isNotBlank() })
                                    }
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Import Short Link")
                            }
                        }
                    }
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
                            OutlinedTextField(
                                value = customTablesText,
                                onValueChange = { customTablesText = it },
                                label = { Text("Custom tables (optional, one per line; enables xvp rotation)") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
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
                        SectionCard(title = "HTTP Mask") {
                            val httpMaskEnabled = !disableHttpMask
                            LaunchedEffect(httpMaskEnabled, httpMaskMode) {
                                if (!httpMaskEnabled || httpMaskMode == HttpMaskMode.LEGACY) {
                                    httpMaskMultiplex = HttpMaskMultiplex.OFF
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Switch(
                                    checked = httpMaskEnabled,
                                    onCheckedChange = { disableHttpMask = !it }
                                )
                                Column {
                                    Text("HTTP mask")
                                    Text(
                                        "Legacy header or HTTP tunnel wrapper.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("HTTP tunnel mode", style = MaterialTheme.typography.labelMedium)
                            SingleChoiceSegmentedButtonRow {
                                HttpMaskMode.entries.forEachIndexed { index: Int, mode: HttpMaskMode ->
                                    SegmentedButton(
                                        selected = httpMaskMode == mode,
                                        onClick = { httpMaskMode = mode },
                                        enabled = httpMaskEnabled,
                                        shape = SegmentedButtonDefaults.itemShape(index, HttpMaskMode.entries.size),
                                        modifier = Modifier.height(40.dp),
                                        label = { Text(mode.label) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            val tunnelOptionsEnabled = httpMaskEnabled && httpMaskMode != HttpMaskMode.LEGACY
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Switch(
                                    checked = httpMaskTls,
                                    onCheckedChange = { httpMaskTls = it },
                                    enabled = tunnelOptionsEnabled
                                )
                                Column {
                                    Text("Force HTTPS")
                                    Text(
                                        "Auto-infer if disabled; applies to tunnel modes.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = httpMaskHost,
                                onValueChange = { httpMaskHost = it },
                                label = { Text("HTTP Host/SNI override (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = tunnelOptionsEnabled,
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = httpMaskPathRoot,
                                onValueChange = { httpMaskPathRoot = it },
                                label = { Text("HTTP path root (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = tunnelOptionsEnabled,
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("HTTP multiplex (mux)", style = MaterialTheme.typography.labelMedium)
                            SingleChoiceSegmentedButtonRow {
                                HttpMaskMultiplex.entries.forEachIndexed { index: Int, mode: HttpMaskMultiplex ->
                                    SegmentedButton(
                                        selected = httpMaskMultiplex == mode,
                                        onClick = { httpMaskMultiplex = mode },
                                        enabled = tunnelOptionsEnabled,
                                        shape = SegmentedButtonDefaults.itemShape(index, HttpMaskMultiplex.entries.size),
                                        modifier = Modifier.height(40.dp),
                                        label = { Text(mode.label) }
                                    )
                                }
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
                                    disableHttpMask = disableHttpMask,
                                    httpMaskMode = httpMaskMode,
                                    httpMaskTls = httpMaskTls,
                                    httpMaskHost = httpMaskHost,
                                    httpMaskPathRoot = httpMaskPathRoot,
                                    httpMaskMultiplex = httpMaskMultiplex,
                                    customTablesText = customTablesText,
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
    disableHttpMask: Boolean,
    httpMaskMode: HttpMaskMode,
    httpMaskTls: Boolean,
    httpMaskHost: String,
    httpMaskPathRoot: String,
    httpMaskMultiplex: HttpMaskMultiplex,
    enablePureDownlink: Boolean,
    customTablesText: String
): NodeConfig {
    val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("Invalid server port")
    val parsedLocalPort = localPort.toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw IllegalArgumentException("Invalid local port")
    val minPad = paddingMin.toIntOrNull() ?: throw IllegalArgumentException("Invalid padding min")
    val maxPad = paddingMax.toIntOrNull() ?: throw IllegalArgumentException("Invalid padding max")
    val (normalizedMin, normalizedMax) = if (minPad <= maxPad) minPad to maxPad else maxPad to minPad
    NodeInputValidator.requirePaddingPercentRange(normalizedMin, normalizedMax)
    val sanitizedHost = host.trim().removeSurrounding("[", "]")
    if (sanitizedHost.isBlank()) throw IllegalArgumentException("Host cannot be blank")
    if (key.trim().isEmpty()) throw IllegalArgumentException("Key cannot be blank")
    val sanitizedHttpMaskHost = httpMaskHost.trim()
    val sanitizedHttpMaskPathRoot = NodeInputValidator.requireHttpMaskPathRoot(httpMaskPathRoot)
    val sanitizedHttpMaskMultiplex = if (disableHttpMask || httpMaskMode == HttpMaskMode.LEGACY) {
        HttpMaskMultiplex.OFF
    } else {
        httpMaskMultiplex
    }

    val customTables = NodeInputValidator.parseCustomTablePatterns(customTablesText)
    customTables.forEach { NodeInputValidator.requireValidCustomTablePattern(it) }

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
        disableHttpMask = disableHttpMask,
        httpMaskMode = httpMaskMode,
        httpMaskTls = httpMaskTls,
        httpMaskHost = sanitizedHttpMaskHost,
        httpMaskPathRoot = sanitizedHttpMaskPathRoot,
        httpMaskMultiplex = sanitizedHttpMaskMultiplex,
        customTable = customTables.firstOrNull().orEmpty(),
        customTables = customTables,
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

@Composable
private fun ConfirmSwitchDialog(
    targetName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch node") },
        text = { Text("Switch VPN to \"$targetName\"?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Switch") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
