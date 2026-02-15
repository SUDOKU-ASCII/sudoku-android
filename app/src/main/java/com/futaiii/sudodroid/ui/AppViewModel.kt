package com.futaiii.sudodroid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.NodeRepository
import com.futaiii.sudodroid.net.GoCoreClient
import com.futaiii.sudodroid.net.ServerAddressResolver
import com.futaiii.sudodroid.vpn.SudokuVpnService
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class NodeUi(
    val node: NodeConfig,
    val latencyMs: Long? = null,
    val isActive: Boolean = false
)

data class AppState(
    val nodes: ImmutableList<NodeUi> = persistentListOf(),
    val activeId: String? = null,
    val isVpnRunning: Boolean = false,
    val reverseForwardStatus: GoCoreClient.ReverseForwardStatus = GoCoreClient.ReverseForwardStatus(),
    val reverseForwardBusy: Boolean = false,
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

        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                reverseForwardStatus.value = GoCoreClient.getReverseForwardStatus()
                delay(1_000)
            }
        }
    }

    private val baseState = combine(
        repo.nodes,
        activeId,
        latencyMap,
        reverseForwardStatus,
        reverseForwardBusy
    ) { nodes, active, latency, reverseStatus, reverseBusy ->
        AppState(
            nodes = nodes.map {
                NodeUi(
                    node = it,
                    latencyMs = latency[it.id],
                    isActive = it.id == active
                )
            }.toImmutableList(),
            activeId = active,
            reverseForwardStatus = reverseStatus,
            reverseForwardBusy = reverseBusy
        )
    }

    val state: StateFlow<AppState> = combine(
        baseState,
        SudokuVpnService.status,
        error
    ) { base, vpnRunning, err -> base.copy(isVpnRunning = vpnRunning, error = err) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState())

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
            val start = System.nanoTime()
            val latency = kotlin.runCatching {
                val proxy = if (state.value.isVpnRunning && state.value.activeId == node.id) {
                    // If VPN is running for this node, use the local SOCKS proxy to test real connectivity
                    java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", node.localPort))
                } else {
                    java.net.Proxy.NO_PROXY
                }
                
                val socket = java.net.Socket(proxy)
                socket.tcpNoDelay = true
                // Connect to the server port. If using proxy, this tests Client -> Proxy -> Server.
                // If direct, this tests Client -> Server.
                val resolved = ServerAddressResolver.resolve(node)
                socket.connect(java.net.InetSocketAddress(resolved.host, resolved.port), 5_000)
                socket.close()
                kotlin.math.abs((System.nanoTime() - start) / 1_000_000L)
            }.getOrNull()
            latencyMap.value = latencyMap.value.toMutableMap().apply { put(node.id, latency) }
        }
    }

    fun startReverseForwarder(listenAddr: String, dialUrl: String, insecure: Boolean) {
        if (state.value.isVpnRunning) {
            error.value = "Please stop VPN before starting local forwarder"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            reverseForwardBusy.value = true
            runCatching {
                GoCoreClient.startReverseForwarder(listenAddr, dialUrl, insecure)
            }.onFailure {
                error.value = it.message ?: "Failed to start local forwarder"
            }
            reverseForwardStatus.value = GoCoreClient.getReverseForwardStatus()
            reverseForwardBusy.value = false
        }
    }

    fun stopReverseForwarder() {
        viewModelScope.launch(Dispatchers.IO) {
            reverseForwardBusy.value = true
            runCatching { GoCoreClient.stopReverseForwarder() }
                .onFailure { error.value = it.message ?: "Failed to stop local forwarder" }
            reverseForwardStatus.value = GoCoreClient.getReverseForwardStatus()
            reverseForwardBusy.value = false
        }
    }

    fun clearError() {
        error.value = null
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
