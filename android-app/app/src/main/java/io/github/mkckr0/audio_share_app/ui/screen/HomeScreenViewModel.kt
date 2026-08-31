/*
 *    Copyright 2022-2024 mkckr0 <https://github.com/mkckr0>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.mkckr0.audio_share_app.ui.screen

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.model.MAX_MIX_SERVER_COUNT
import io.github.mkckr0.audio_share_app.model.NetworkConfigKeys
import io.github.mkckr0.audio_share_app.model.ServerConfig
import io.github.mkckr0.audio_share_app.model.ServerConfigJson
import io.github.mkckr0.audio_share_app.model.ServerEndpoint
import io.github.mkckr0.audio_share_app.model.ServerEndpointJson
import io.github.mkckr0.audio_share_app.model.getInteger
import io.github.mkckr0.audio_share_app.model.networkConfigDataStore
import io.github.mkckr0.audio_share_app.model.resolveServerConfigs
import io.github.mkckr0.audio_share_app.service.ServerDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeScreenViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface UiState {
        data object Loading : UiState
        data class Success(
            val servers: List<ServerConfig>,
            val savedEndpoints: List<ServerEndpoint>,
            val discoveredEndpoints: List<ServerEndpoint>,
            val isDiscovering: Boolean,
        ) : UiState
    }

    private val discoveredEndpoints = MutableStateFlow<List<ServerEndpoint>>(emptyList())
    private val isDiscovering = MutableStateFlow(false)
    private val discovery = ServerDiscovery(application)

    val uiState: StateFlow<UiState> = combine(
        application.networkConfigDataStore.data,
        discoveredEndpoints,
        isDiscovering,
    ) { preferences, discovered, discovering ->
        UiState.Success(
            servers = loadServers(preferences),
            savedEndpoints = loadEndpoints(preferences),
            discoveredEndpoints = discovered,
            isDiscovering = discovering,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UiState.Loading,
    )

    init {
        discoverServers()
    }

    fun saveServers(servers: List<ServerConfig>, rememberEndpoints: Boolean = false): Job {
        return viewModelScope.launch {
            getApplication<Application>().networkConfigDataStore.edit {
                it[stringPreferencesKey(NetworkConfigKeys.SERVERS_JSON)] = ServerConfigJson.encode(
                    servers.limitEnabledServers()
                )
                if (rememberEndpoints) {
                    val previous = it[stringPreferencesKey(NetworkConfigKeys.SERVER_ENDPOINTS_JSON)]
                        ?.let { value -> runCatching { ServerEndpointJson.decode(value) }.getOrDefault(emptyList()) }
                        .orEmpty()
                    val current = servers.mapNotNull { server -> server.toEndpointOrNull() }
                    it[stringPreferencesKey(NetworkConfigKeys.SERVER_ENDPOINTS_JSON)] =
                        ServerEndpointJson.encode(mergeEndpoints(current, previous))
                }
            }
        }
    }

    fun discoverServers(): Job {
        return viewModelScope.launch {
            if (isDiscovering.value) return@launch
            isDiscovering.value = true
            try {
                val preferences = getApplication<Application>().networkConfigDataStore.data.first()
                val ports = loadServers(preferences).map { it.port }.toMutableSet().apply {
                    add(getApplication<Application>().getInteger(R.integer.default_port))
                }
                val found = discovery.discover(ports)
                discoveredEndpoints.value = found
                rememberEndpoints(found)
            } finally {
                isDiscovering.value = false
            }
        }
    }

    fun useEndpoint(servers: List<ServerConfig>, endpoint: ServerEndpoint): Job {
        val existing = servers.firstOrNull {
            it.host.equals(endpoint.host, ignoreCase = true) && it.port == endpoint.port
        }
        val updated = if (existing != null) {
            servers.map { if (it.id == existing.id) it.copy(enabled = true) else it }
        } else {
            servers + ServerConfig(
                name = "Server ${servers.size + 1}",
                host = endpoint.host,
                port = endpoint.port,
                enabled = servers.count { it.enabled } < MAX_MIX_SERVER_COUNT,
            )
        }
        return saveServers(updated, rememberEndpoints = true)
    }

    fun addServer(servers: List<ServerConfig>): Job {
        val nextIndex = servers.size + 1
        return saveServers(
            servers + ServerConfig(
                name = "Server $nextIndex",
                host = getApplication<Application>().getString(R.string.default_host),
                port = getApplication<Application>().getInteger(R.integer.default_port),
                enabled = servers.count { it.enabled } < MAX_MIX_SERVER_COUNT,
            )
        )
    }

    private fun loadServers(preferences: Preferences): List<ServerConfig> {
        val application = getApplication<Application>()
        return resolveServerConfigs(
            serversJson = preferences[stringPreferencesKey(NetworkConfigKeys.SERVERS_JSON)],
            legacyHost = preferences[stringPreferencesKey(NetworkConfigKeys.HOST)],
            legacyPort = preferences[intPreferencesKey(NetworkConfigKeys.PORT)],
            defaultHost = application.getString(R.string.default_host),
            defaultPort = application.getInteger(R.integer.default_port),
        )
    }

    private fun loadEndpoints(preferences: Preferences): List<ServerEndpoint> {
        val value = preferences[stringPreferencesKey(NetworkConfigKeys.SERVER_ENDPOINTS_JSON)]
            ?: return emptyList()
        return runCatching { ServerEndpointJson.decode(value) }.getOrDefault(emptyList())
    }

    private suspend fun rememberEndpoints(endpoints: List<ServerEndpoint>) {
        if (endpoints.isEmpty()) return
        getApplication<Application>().networkConfigDataStore.edit {
            val previous = it[stringPreferencesKey(NetworkConfigKeys.SERVER_ENDPOINTS_JSON)]
                ?.let { value -> runCatching { ServerEndpointJson.decode(value) }.getOrDefault(emptyList()) }
                .orEmpty()
            it[stringPreferencesKey(NetworkConfigKeys.SERVER_ENDPOINTS_JSON)] =
                ServerEndpointJson.encode(mergeEndpoints(endpoints, previous))
        }
    }

    private fun mergeEndpoints(
        preferred: List<ServerEndpoint>,
        existing: List<ServerEndpoint>,
    ): List<ServerEndpoint> {
        return (preferred + existing)
            .filter { it.host.isNotBlank() && it.port in 1..65535 }
            .distinctBy { "${it.host.trim().lowercase()}:${it.port}" }
            .take(MAX_SAVED_ENDPOINTS)
    }

    private fun ServerConfig.toEndpointOrNull(): ServerEndpoint? {
        val normalizedHost = host.trim()
        if (normalizedHost.isBlank() || normalizedHost.contains(Regex("\\s")) || port !in 1..65535) {
            return null
        }
        return ServerEndpoint(normalizedHost, port)
    }

    private fun List<ServerConfig>.limitEnabledServers(): List<ServerConfig> {
        var enabledCount = 0
        return map { server ->
            if (!server.enabled) {
                return@map server
            }
            enabledCount += 1
            if (enabledCount <= MAX_MIX_SERVER_COUNT) server else server.copy(enabled = false)
        }
    }

    private companion object {
        const val MAX_SAVED_ENDPOINTS = 20
    }
}
