package com.futaiii.sudodroid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.futaiii.sudodroid.data.GlobalProxySettings
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.NodeRepository
import com.futaiii.sudodroid.data.ProxyMode
import com.futaiii.sudodroid.data.ReverseForwarderSettings
import com.futaiii.sudodroid.data.DEFAULT_PAC_RULE_URLS
import com.futaiii.sudodroid.net.GoCoreClient
import com.futaiii.sudodroid.proxy.ProxyOnlyService
import com.futaiii.sudodroid.vpn.SudokuVpnService
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NodeUi(
    val node: NodeConfig,
    val latencyMs: Long? = null,
    val isActive: Boolean = false
)

data class AppState(
    val nodes: ImmutableList<NodeUi> = persistentListOf(),
    val activeId: String? = null,
    val globalProxySettings: GlobalProxySettings = GlobalProxySettings(),
    val isVpnRunning: Boolean = false,
    val isProxyOnlyRunning: Boolean = false,
    val reverseForwardStatus: GoCoreClient.ReverseForwardStatus = GoCoreClient.ReverseForwardStatus(),
    val reverseForwardBusy: Boolean = false,
    val reverseForwarderSettings: ReverseForwarderSettings = ReverseForwarderSettings(),
    val error: String? = null
)

class AppViewModel(
    application: Application,
    private val repo: NodeRepository
) : AndroidViewModel(application) {
    private val activeId = MutableStateFlow<String?>(null)
    private val latencyMap = MutableStateFlow<Map<String, Long?>>(emptyMap())
    private val reverseForwardStatus = MutableStateFlow(GoCoreClient.ReverseForwardStatus())
    private val reverseForwardBusy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private var reverseForwardStatusJob: Job? = null

    init {
        viewModelScope.launch {
            // Restore last active ID
            val lastId = repo.lastActiveId.first()
            if (lastId != null) {
                activeId.value = lastId
            }

            repo.nodes.collect { nodes ->
                val current = activeId.value
                if (nodes.isEmpty()) {
                    activeId.value = null
                    repo.saveActiveId(null)
                } else if (current == null || nodes.none { it.id == current }) {
                    // If current is null or invalid, pick the first one (or keep lastId if valid)
                    // We need to check if lastId is still valid in the new list if we just restored it
                    val candidate = if (lastId != null && nodes.any { it.id == lastId } && current == lastId) {
                        lastId
                    } else {
                        nodes.first().id
                    }
                    activeId.value = candidate
                    repo.saveActiveId(candidate)
                }
            }
        }

        refreshReverseForwardStatus()
    }

    private fun refreshReverseForwardStatus() {
        ensureReverseForwardStatusPolling()
    }

    private fun updateReverseForwardStatus(status: GoCoreClient.ReverseForwardStatus) {
        if (reverseForwardStatus.value != status) {
            reverseForwardStatus.value = status
        }
        ensureReverseForwardStatusPolling()
    }

    private fun ensureReverseForwardStatusPolling() {
        if (reverseForwardStatusJob?.isActive == true) {
            return
        }
        reverseForwardStatusJob = viewModelScope.launch(Dispatchers.IO) {
            var lastStatus = reverseForwardStatus.value
            while (isActive) {
                val latestStatus = GoCoreClient.getReverseForwardStatus()
                if (latestStatus != lastStatus) {
                    reverseForwardStatus.value = latestStatus
                    lastStatus = latestStatus
                }
                val delayMs = if (latestStatus.running) {
                    REVERSE_FORWARD_STATUS_RUNNING_POLL_MS
                } else {
                    REVERSE_FORWARD_STATUS_IDLE_POLL_MS
                }
                delay(delayMs)
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (reverseForwardStatusJob === job) {
                    reverseForwardStatusJob = null
                }
            }
        }
    }

    private val baseState = combine(
        combine(
            repo.nodes,
            repo.globalProxySettings,
            activeId,
            latencyMap,
            reverseForwardStatus
        ) { nodes, globalSettings, active, latency, reverseStatus ->
            AppState(
                nodes = nodes.map {
                    NodeUi(
                        node = it,
                        latencyMs = latency[it.id],
                        isActive = it.id == active
                    )
                }.toImmutableList(),
                activeId = active,
                globalProxySettings = globalSettings,
                reverseForwardStatus = reverseStatus
            )
        },
        reverseForwardBusy,
        repo.reverseForwarderSettings
    ) { partial, reverseBusy, revFwdSettings ->
        partial.copy(reverseForwardBusy = reverseBusy, reverseForwarderSettings = revFwdSettings)
    }

    val state: StateFlow<AppState> = combine(
        baseState,
        SudokuVpnService.status,
        ProxyOnlyService.status,
        error
    ) { base, vpnRunning, proxyOnlyRunning, err ->
        base.copy(
            isVpnRunning = vpnRunning,
            isProxyOnlyRunning = proxyOnlyRunning,
            error = err
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState())

    fun selectNode(id: String) {
        activeId.value = id
        viewModelScope.launch { repo.saveActiveId(id) }
    }

    fun deleteNode(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            // The repo.nodes collector will handle updating activeId if needed
        }
    }

    fun saveNode(node: NodeConfig) {
        viewModelScope.launch {
            repo.save(node)
        }
    }

    fun importShortLink(link: String, nameOverride: String?, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            runCatching { repo.importShortLink(link, nameOverride) }
                .onSuccess { imported -> 
                    activeId.value = imported.id
                    repo.saveActiveId(imported.id)
                    onSuccess?.invoke()
                }
                .onFailure { error.value = it.message ?: "Import failed" }
        }
    }

    fun pingNode(node: NodeConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val latency = kotlin.runCatching {
                val current = state.value
                val result = GoCoreClient.probeLatency(node, current.globalProxySettings)
                result.latencyMs.takeIf { result.connectOk && it >= 0 }
            }.getOrNull()
            latencyMap.value = latencyMap.value.toMutableMap().apply { put(node.id, latency) }
        }
    }

    fun startReverseForwarder(listenAddr: String, dialUrl: String, insecure: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            reverseForwardBusy.value = true
            runCatching {
                GoCoreClient.startReverseForwarder(listenAddr, dialUrl, insecure)
            }.onFailure {
                error.value = it.message ?: "Failed to start local forwarder"
            }
            updateReverseForwardStatus(GoCoreClient.getReverseForwardStatus())
            reverseForwardBusy.value = false
        }
    }

    fun stopReverseForwarder() {
        viewModelScope.launch(Dispatchers.IO) {
            reverseForwardBusy.value = true
            runCatching { GoCoreClient.stopReverseForwarder() }
                .onFailure { error.value = it.message ?: "Failed to stop local forwarder" }
            updateReverseForwardStatus(GoCoreClient.getReverseForwardStatus())
            reverseForwardBusy.value = false
        }
    }

    fun updateGlobalProxySettings(
        proxyMode: ProxyMode,
        ruleUrlsText: String
    ) {
        viewModelScope.launch {
            val sanitizedRuleUrls = ruleUrlsText.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val finalRuleUrls = if (proxyMode == ProxyMode.PAC) {
                if (sanitizedRuleUrls.isEmpty()) DEFAULT_PAC_RULE_URLS else sanitizedRuleUrls
            } else {
                emptyList()
            }
            repo.saveGlobalProxySettings(
                GlobalProxySettings(
                    proxyMode = proxyMode,
                    ruleUrls = finalRuleUrls
                )
            )
        }
    }

    fun clearError() {
        error.value = null
    }

    fun saveReverseForwarderSettings(dialUrl: String, listenAddr: String, insecure: Boolean) {
        viewModelScope.launch {
            repo.saveReverseForwarderSettings(
                ReverseForwarderSettings(
                    dialUrl = dialUrl,
                    listenAddr = listenAddr,
                    insecure = insecure
                )
            )
        }
    }

    private companion object {
        private const val REVERSE_FORWARD_STATUS_RUNNING_POLL_MS = 2_000L
        private const val REVERSE_FORWARD_STATUS_IDLE_POLL_MS = 15_000L
    }
}

class AppViewModelFactory(
    private val repo: NodeRepository,
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(application, repo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel")
    }
}
