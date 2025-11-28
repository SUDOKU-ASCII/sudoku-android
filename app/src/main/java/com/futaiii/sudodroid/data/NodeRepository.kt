package com.futaiii.sudodroid.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.futaiii.sudodroid.protocol.ShortLinkCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.nodeDataStore by preferencesDataStore(name = "nodes")

class NodeRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val keyNodes = stringPreferencesKey("nodes_json")
    private val keyActiveId = stringPreferencesKey("active_node_id")

    val nodes: Flow<List<NodeConfig>> = context.nodeDataStore.data.map { prefs ->
        prefs[keyNodes]?.let { stored ->
            runCatching { json.decodeFromString<List<NodeConfig>>(stored) }.getOrElse { emptyList() }
        } ?: emptyList()
    }

    val lastActiveId: Flow<String?> = context.nodeDataStore.data.map { prefs ->
        prefs[keyActiveId]
    }

    suspend fun saveActiveId(id: String?) {
        context.nodeDataStore.edit { prefs ->
            if (id != null) {
                prefs[keyActiveId] = id
            } else {
                prefs.remove(keyActiveId)
            }
        }
    }

    suspend fun save(node: NodeConfig) {
        val updated = nodes.first().toMutableList().apply {
            val idx = indexOfFirst { it.id == node.id }
            if (idx >= 0) {
                this[idx] = node
            } else {
                add(node)
            }
        }
        persist(updated)
    }

    suspend fun delete(id: String) {
        val updated = nodes.first().filterNot { it.id == id }
        persist(updated)
    }

    suspend fun importShortLink(link: String, nameOverride: String? = null): NodeConfig {
        val cfg = ShortLinkCodec.fromLink(link)
        val named = cfg.copy(name = nameOverride ?: cfg.name)
        save(named)
        return named
    }

    private suspend fun persist(list: List<NodeConfig>) {
        context.nodeDataStore.edit { prefs ->
            prefs[keyNodes] = json.encodeToString(list)
        }
    }
}
