package com.futaiii.sudodroid.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.futaiii.sudodroid.protocol.ShortLinkCodec
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val keyGlobalProxySettings = stringPreferencesKey("global_proxy_settings_json")
    private val keyReverseForwarderSettings = stringPreferencesKey("reverse_forwarder_settings_json")

    val nodes: Flow<List<NodeConfig>> = context.nodeDataStore.data
        .map { prefs -> readNodes(prefs) }
        .distinctUntilChanged()

    val globalProxySettings: Flow<GlobalProxySettings> = context.nodeDataStore.data
        .map { prefs -> decodeOrDefault(prefs[keyGlobalProxySettings], GlobalProxySettings()) }
        .distinctUntilChanged()

    val reverseForwarderSettings: Flow<ReverseForwarderSettings> = context.nodeDataStore.data
        .map { prefs -> decodeOrDefault(prefs[keyReverseForwarderSettings], ReverseForwarderSettings()) }
        .distinctUntilChanged()

    val lastActiveId: Flow<String?> = context.nodeDataStore.data
        .map { prefs -> prefs[keyActiveId] }
        .distinctUntilChanged()

    suspend fun saveActiveId(id: String?) {
        context.nodeDataStore.edit { prefs ->
            if (id != null) {
                if (prefs[keyActiveId] == id) return@edit
                prefs[keyActiveId] = id
            } else {
                if (!prefs.contains(keyActiveId)) return@edit
                prefs.remove(keyActiveId)
            }
        }
    }

    suspend fun save(node: NodeConfig) {
        mutateNodes { current ->
            val idx = current.indexOfFirst { it.id == node.id }
            if (idx >= 0) {
                current[idx] = node
            } else {
                current.add(node)
            }
        }
    }

    suspend fun saveGlobalProxySettings(settings: GlobalProxySettings) {
        context.nodeDataStore.edit { prefs ->
            val encoded = json.encodeToString(settings)
            if (prefs[keyGlobalProxySettings] != encoded) {
                prefs[keyGlobalProxySettings] = encoded
            }
        }
    }

    suspend fun saveReverseForwarderSettings(settings: ReverseForwarderSettings) {
        context.nodeDataStore.edit { prefs ->
            val encoded = json.encodeToString(settings)
            if (prefs[keyReverseForwarderSettings] != encoded) {
                prefs[keyReverseForwarderSettings] = encoded
            }
        }
    }

    suspend fun delete(id: String) {
        mutateNodes { current -> current.removeAll { it.id == id } }
    }

    suspend fun importShortLink(link: String, nameOverride: String? = null): NodeConfig {
        val cfg = ShortLinkCodec.fromLink(link)
        val named = cfg.copy(name = nameOverride ?: cfg.name)
        save(named)
        return named
    }

    private fun readNodes(prefs: Preferences): List<NodeConfig> {
        return decodeOrDefault(prefs[keyNodes], emptyList())
    }

    private suspend fun mutateNodes(transform: (MutableList<NodeConfig>) -> Unit) {
        context.nodeDataStore.edit { prefs ->
            val updated = readNodes(prefs).toMutableList()
            transform(updated)
            persist(prefs, updated)
        }
    }

    private suspend fun persist(list: List<NodeConfig>) {
        context.nodeDataStore.edit { prefs ->
            persist(prefs, list)
        }
    }

    private fun persist(prefs: MutablePreferences, list: List<NodeConfig>) {
        val encoded = json.encodeToString(list)
        if (prefs[keyNodes] != encoded) {
            prefs[keyNodes] = encoded
        }
    }

    private inline fun <reified T> decodeOrDefault(stored: String?, fallback: T): T {
        if (stored.isNullOrBlank()) return fallback
        return runCatching { json.decodeFromString<T>(stored) }.getOrElse { fallback }
    }
}
