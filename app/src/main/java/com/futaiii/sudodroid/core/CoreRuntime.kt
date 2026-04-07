package com.futaiii.sudodroid.core

import com.futaiii.sudodroid.data.NodeConfig
import com.futaiii.sudodroid.data.NodeRepository
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

    suspend fun startCore(repo: NodeRepository, node: NodeConfig) {
        val globalSettings = repo.globalProxySettings.first()
        val configJson = GoCoreClient.buildConfigJson(node, globalSettings)
        GoCoreClient.start(configJson)
        GoCoreClient.resetTrafficStats()
    }
}
