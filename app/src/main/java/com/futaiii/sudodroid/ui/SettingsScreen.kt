@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.futaiii.sudodroid.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.futaiii.sudodroid.BuildConfig
import com.futaiii.sudodroid.data.DEFAULT_PAC_RULE_URLS
import com.futaiii.sudodroid.net.GoCoreClient

@Composable
fun SettingsScreen(
    state: AppState,
    widthClass: WidthClass,
    onSavePacRules: (String) -> Unit,
    onSaveForwarderSettings: (dialUrl: String, listenAddr: String, insecure: Boolean) -> Unit,
    onStartForwarder: (listenAddr: String, dialUrl: String, insecure: Boolean) -> Unit,
    onStopForwarder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val defaultRulesText = remember { DEFAULT_PAC_RULE_URLS.joinToString("\n") }
    var rulesText by rememberSaveable { mutableStateOf("") }
    var dialUrl by rememberSaveable { mutableStateOf("") }
    var listenAddr by rememberSaveable { mutableStateOf("") }
    var insecure by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.globalProxySettings.ruleUrls) {
        rulesText = state.globalProxySettings.ruleUrls.joinToString("\n").ifBlank { defaultRulesText }
    }
    LaunchedEffect(state.reverseForwarderSettings, state.reverseForwardStatus.running) {
        if (!state.reverseForwardStatus.running) {
            dialUrl = state.reverseForwarderSettings.dialUrl
            listenAddr = state.reverseForwarderSettings.listenAddr
            insecure = state.reverseForwarderSettings.insecure
        }
    }
    LaunchedEffect(
        state.reverseForwardStatus.running,
        state.reverseForwardStatus.dialUrl,
        state.reverseForwardStatus.listenAddr,
        state.reverseForwardStatus.insecure
    ) {
        if (state.reverseForwardStatus.running) {
            dialUrl = state.reverseForwardStatus.dialUrl
            listenAddr = state.reverseForwardStatus.listenAddr
            insecure = state.reverseForwardStatus.insecure
        }
    }

    val rules: @Composable () -> Unit = {
        RulesPanel(
            rulesText = rulesText,
            running = state.isRunning,
            onChange = { rulesText = it },
            onUseDefault = {
                rulesText = defaultRulesText
                onSavePacRules(defaultRulesText)
            },
            onSave = { onSavePacRules(rulesText) }
        )
    }
    val forwarder: @Composable () -> Unit = {
        ForwarderPanel(
            status = state.reverseForwardStatus,
            busy = state.reverseForwardBusy,
            dialUrl = dialUrl,
            listenAddr = listenAddr,
            insecure = insecure,
            onDialUrlChange = {
                dialUrl = it
                onSaveForwarderSettings(it, listenAddr, insecure)
            },
            onListenAddrChange = {
                listenAddr = it
                onSaveForwarderSettings(dialUrl, it, insecure)
            },
            onInsecureChange = {
                insecure = it
                onSaveForwarderSettings(dialUrl, listenAddr, it)
            },
            onStart = { onStartForwarder(listenAddr, dialUrl, insecure) },
            onStop = onStopForwarder
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = if (widthClass == WidthClass.Expanded) 1120.dp else 680.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ScreenTitle(title = "Settings", subtitle = "Rules, forwarding and about")
            if (widthClass == WidthClass.Expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { rules() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        forwarder()
                        AboutPanel()
                    }
                }
            } else {
                rules()
                forwarder()
                AboutPanel()
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RulesPanel(
    rulesText: String,
    running: Boolean,
    onChange: (String) -> Unit,
    onUseDefault: () -> Unit,
    onSave: () -> Unit
) {
    val lines = rulesText.lines()
    Panel(modifier = Modifier.fillMaxWidth()) {
        Eyebrow("PAC rules")
        Text(
            "Rule lists decide what goes direct in PAC mode. Prefix a URL with ! to block what it matches.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        lines.forEachIndexed { index, line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = line,
                    onValueChange = { new ->
                        onChange(lines.toMutableList().also { it[index] = new }.joinToString("\n"))
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = MaterialTheme.shapes.medium
                )
                IconButton(onClick = {
                    onChange(lines.toMutableList().also { it.removeAt(index) }.joinToString("\n"))
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove rule")
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange((lines + "").joinToString("\n")) }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Add")
            }
            TextButton(onClick = onUseDefault) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Defaults")
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = onSave) {
                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Save")
            }
        }
        if (running) {
            Text(
                "Rule lists are read when the core starts, so saving briefly reconnects the running session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ForwarderPanel(
    status: GoCoreClient.ReverseForwardStatus,
    busy: Boolean,
    dialUrl: String,
    listenAddr: String,
    insecure: Boolean,
    onDialUrlChange: (String) -> Unit,
    onListenAddrChange: (String) -> Unit,
    onInsecureChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val locked = status.running || busy
    Panel(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
        highlighted = status.running
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Local port forwarder", Modifier.weight(1f))
            Text(
                if (status.running) "Running" else "Stopped",
                style = MaterialTheme.typography.labelLarge,
                color = if (status.running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = dialUrl,
            onValueChange = onDialUrlChange,
            label = { Text("rev-dial (ws:// or wss://)") },
            singleLine = true,
            enabled = !locked,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )
        OutlinedTextField(
            value = listenAddr,
            onValueChange = onListenAddrChange,
            label = { Text("rev-listen (e.g. 127.0.0.1:2222)") },
            singleLine = true,
            enabled = !locked,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Allow insecure TLS")
                Text(
                    "Only for self-signed wss endpoints",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = insecure, onCheckedChange = onInsecureChange, enabled = !locked)
        }
        Text(
            "./sudoku -rev-dial $dialUrl -rev-listen $listenAddr",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (status.lastError.isNotBlank()) {
            Text(
                "Last error: ${status.lastError}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (status.running) {
                Button(
                    onClick = onStop,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (busy) "Stopping..." else "Stop")
                }
            } else {
                FilledTonalButton(
                    onClick = onStart,
                    enabled = !busy && dialUrl.isNotBlank() && listenAddr.isNotBlank()
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(if (busy) "Starting..." else "Start")
                }
            }
        }
    }
}

@Composable
private fun AboutPanel() {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Eyebrow("About")
        Text("Sudodroid ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text(
            "Speed, usage, routing mode and open connections come from the core's local controller. " +
                "It listens on loopback only, on a random port, behind a token that is regenerated on every start.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
