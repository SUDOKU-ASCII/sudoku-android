package com.futaiii.sudodroid.core

import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.NodeRepository
import com.futaiii.sudodroid.net.ControllerApi
import com.futaiii.sudodroid.net.ControllerEndpoint
import com.futaiii.sudodroid.net.GoCoreClient
import kotlinx.coroutines.flow.first

object CoreRuntime {
    suspend fun selectNode(
        repo: NodeRepository,
        nodeId: String?,
        allowFallback: Boolean
    ): NodeConfig? {
        val nodes = repo.nodes.first()
        if (nodes.isEmpty()) return null
        if (nodeId.isNullOrBlank()) return nodes.first()
        val selected = nodes.find { it.id == nodeId }
        return selected ?: if (allowFallback) nodes.first() else null
    }

    /** Starts the core with its loopback controller on, so speed and mode switching go through the API. */
    suspend fun startCore(repo: NodeRepository, node: NodeConfig) {
        val globalSettings = repo.globalProxySettings.first()
        var lastError: Throwable? = null
        // The free port is probed before the core binds it, so a rare collision is retried once or twice.
        repeat(3) {
            val endpoint = ControllerEndpoint.random()
            val configJson = GoCoreClient.buildConfigJson(node, globalSettings, endpoint)
            try {
                GoCoreClient.start(configJson, endpoint)
                return
            } catch (t: Throwable) {
                if (t.message?.contains("external controller") != true) throw t
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Failed to start core")
    }

    /** Applies the saved routing mode to the running core without restarting it. */
    suspend fun applyProxyMode(repo: NodeRepository): Boolean {
        val mode = repo.globalProxySettings.first().proxyMode
        return runCatching { ControllerApi.setMode(mode) }.getOrDefault(false)
    }
}
