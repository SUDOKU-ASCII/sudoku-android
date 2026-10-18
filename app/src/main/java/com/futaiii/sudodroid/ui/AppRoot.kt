package com.futaiii.sudodroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.protocol.ShortLinkCodec
import com.futaiii.sudodroid.ui.theme.AppBackgroundBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CONNECT_TIMEOUT_MS = 12_000L

private enum class Destination(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    Home("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Nodes("Nodes", Icons.Outlined.Dns, Icons.Rounded.Dns),
    Activity("Activity", Icons.Outlined.SwapVert, Icons.Rounded.SwapVert),
    Settings("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings)
}

@Composable
fun AppRoot(
    viewModel: AppViewModel,
    onToggleVpn: (Boolean) -> Unit,
    onSwitchNodeWhileRunning: (NodeConfig) -> Unit,
    onToggleProxyOnly: (Boolean) -> Unit,
    onSwitchNodeWhileProxyOnlyRunning: (NodeConfig) -> Unit,
    onRestartRunningProxy: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    var runMode by rememberSaveable { mutableStateOf(RunMode.VPN) }
    var connecting by remember { mutableStateOf(false) }
    var editorInitial by remember { mutableStateOf<NodeConfig?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NodeConfig?>(null) }
    var pendingSwitch by remember { mutableStateOf<NodeConfig?>(null) }

    val runningNow by rememberUpdatedState(state.isRunning)

    fun toast(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    BackHandler(enabled = destination != Destination.Home) { destination = Destination.Home }

    LaunchedEffect(state.error) {
        val err = state.error
        if (err != null) {
            snackbarHostState.showSnackbar(err)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.isVpnRunning, state.isProxyOnlyRunning) {
        when {
            state.isProxyOnlyRunning -> runMode = RunMode.PROXY_ONLY
            state.isVpnRunning -> runMode = RunMode.VPN
        }
        if (state.isRunning) connecting = false
    }

    LaunchedEffect(connecting) {
        if (!connecting) return@LaunchedEffect
        delay(CONNECT_TIMEOUT_MS)
        connecting = false
        if (!runningNow) toast("Still not connected. Check the node and the VPN permission.")
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

    val togglePower: () -> Unit = {
        when {
            state.isVpnRunning -> onToggleVpn(true)
            state.isProxyOnlyRunning -> onToggleProxyOnly(true)
            state.activeNode == null -> toast("Add or select a node first")
            else -> {
                connecting = true
                when (runMode) {
                    RunMode.VPN -> onToggleVpn(false)
                    RunMode.PROXY_ONLY -> onToggleProxyOnly(false)
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackgroundBrush)
    ) {
        val widthClass = WidthClass.of(maxWidth)
        val compact = widthClass == WidthClass.Compact

        val content: @Composable () -> Unit = {
            Crossfade(
                targetState = destination,
                animationSpec = tween(180),
                label = "destination"
            ) { target ->
                when (target) {
                    Destination.Home -> HomeScreen(
                        state = state,
                        widthClass = widthClass,
                        runMode = runMode,
                        connecting = connecting,
                        onRunModeChange = { runMode = it },
                        onTogglePower = togglePower,
                        onProxyModeChange = { viewModel.updateProxyMode(it) },
                        onOpenNodes = { destination = Destination.Nodes }
                    )

                    Destination.Nodes -> NodesScreen(
                        state = state,
                        widthClass = widthClass,
                        onAdd = {
                            editorInitial = null
                            showEditor = true
                        },
                        onSelect = {
                            if (state.isRunning && state.activeId != it.id) {
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
                            clipboard.setText(AnnotatedString(ShortLinkCodec.toLink(it)))
                            toast("Copied short link")
                        }
                    )

                    Destination.Activity -> ConnectionsScreen(
                        running = state.isRunning,
                        widthClass = widthClass
                    )

                    Destination.Settings -> SettingsScreen(
                        state = state,
                        widthClass = widthClass,
                        onSavePacRules = { text ->
                            viewModel.savePacRules(text) {
                                onRestartRunningProxy()
                                toast("PAC rules saved")
                            }
                        },
                        onSaveForwarderSettings = { dial, listen, insecure ->
                            viewModel.saveReverseForwarderSettings(dial, listen, insecure)
                        },
                        onStartForwarder = { listen, dial, insecure ->
                            viewModel.startReverseForwarder(listen, dial, insecure)
                        },
                        onStopForwarder = { viewModel.stopReverseForwarder() }
                    )
                }
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (compact) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp
                    ) {
                        Destination.entries.forEach { item ->
                            val selected = destination == item
                            NavigationBarItem(
                                selected = selected,
                                onClick = { destination = item },
                                icon = {
                                    Icon(if (selected) item.selectedIcon else item.icon, contentDescription = item.label)
                                },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            if (compact) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        )
                ) { content() }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = Color.Transparent) {
                        Destination.entries.forEach { item ->
                            val selected = destination == item
                            NavigationRailItem(
                                selected = selected,
                                onClick = { destination = item },
                                icon = {
                                    Icon(if (selected) item.selectedIcon else item.icon, contentDescription = item.label)
                                },
                                label = { Text(item.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.systemBars.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom
                                )
                            )
                    ) { content() }
                }
            }
        }
    }
}
