@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.futaiii.sudodroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.futaiii.sudodroid.data.AeadMode
import com.futaiii.sudodroid.data.AsciiMode
import com.futaiii.sudodroid.data.AsciiPreference
import com.futaiii.sudodroid.data.DEFAULT_PAC_RULE_URLS
import com.futaiii.sudodroid.data.GlobalProxySettings
import com.futaiii.sudodroid.data.HttpMaskMode
import com.futaiii.sudodroid.data.HttpMaskMultiplex
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.NodeInputValidator
import com.futaiii.sudodroid.data.ProxyMode
import com.futaiii.sudodroid.data.downlinkPreference
import com.futaiii.sudodroid.data.uplinkPreference
import com.futaiii.sudodroid.net.GoCoreClient
import com.futaiii.sudodroid.protocol.ShortLinkCodec
import java.util.UUID
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun NodeEditorDialog(
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
    val initialAsciiMode = initial?.asciiMode ?: AsciiMode.PREFER_ENTROPY
    var uplinkAsciiPreference by rememberSaveable { mutableStateOf(initialAsciiMode.uplinkPreference()) }
    var downlinkAsciiPreference by rememberSaveable { mutableStateOf(initialAsciiMode.downlinkPreference()) }
    var customTablesText by rememberSaveable {
        mutableStateOf(
            initial?.customTables?.takeIf { it.isNotEmpty() }?.joinToString("\n")
                ?: initial?.customTable.orEmpty()
        )
    }
    var aeadMode by rememberSaveable { mutableStateOf(initial?.aead ?: AeadMode.CHACHA20_POLY1305) }
    var disableHttpMask by rememberSaveable { mutableStateOf(initial?.disableHttpMask ?: false) }
    var httpMaskMode by rememberSaveable { mutableStateOf(initial?.httpMaskMode ?: HttpMaskMode.AUTO) }
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
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
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
                            Text("Uplink style", style = MaterialTheme.typography.labelMedium)
                            AsciiPreferenceSelector(
                                selected = uplinkAsciiPreference,
                                onSelected = { uplinkAsciiPreference = it }
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("Downlink style", style = MaterialTheme.typography.labelMedium)
                            AsciiPreferenceSelector(
                                selected = downlinkAsciiPreference,
                                onSelected = { downlinkAsciiPreference = it }
                            )
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
                                        "HTTP tunnel wrapper (auto/stream/poll/ws).",
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
                                        label = { Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            val tunnelOptionsEnabled = httpMaskEnabled
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
                        }
                    }
                    item {
                        SectionCard(title = "Multiplex") {
                            Text("Tunnel multiplex (mux)", style = MaterialTheme.typography.labelMedium)
                            SingleChoiceSegmentedButtonRow {
                                HttpMaskMultiplex.entries.forEachIndexed { index: Int, mode: HttpMaskMultiplex ->
                                    SegmentedButton(
                                        selected = httpMaskMultiplex == mode,
                                        onClick = { httpMaskMultiplex = mode },
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
                                        "Uses packed downlink.",
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
                                    asciiMode = AsciiMode.fromPreferences(
                                        uplink = uplinkAsciiPreference,
                                        downlink = downlinkAsciiPreference
                                    ),
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

@Composable
private fun AsciiPreferenceSelector(
    selected: AsciiPreference,
    onSelected: (AsciiPreference) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        AsciiPreference.entries.forEachIndexed { index, preference ->
            SegmentedButton(
                selected = selected == preference,
                onClick = { onSelected(preference) },
                shape = SegmentedButtonDefaults.itemShape(index, AsciiPreference.entries.size),
                modifier = Modifier
                    .height(40.dp)
                    .weight(1f),
                label = {
                    Text(
                        preference.label,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            )
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
        httpMaskMultiplex = httpMaskMultiplex,
        customTable = customTables.firstOrNull().orEmpty(),
        customTables = customTables,
        createdAt = initial?.createdAt ?: System.currentTimeMillis()
    )
}

@Composable
internal fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
internal fun ConfirmDeleteDialog(
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
internal fun ConfirmSwitchDialog(
    targetName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch node") },
        text = { Text("Switch the running connection to \"$targetName\"?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Switch") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
