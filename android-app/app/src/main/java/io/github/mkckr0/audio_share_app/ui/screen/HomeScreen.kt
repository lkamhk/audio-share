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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import io.github.mkckr0.audio_share_app.model.MAX_MIX_SERVER_COUNT
import io.github.mkckr0.audio_share_app.model.ServerConfig
import io.github.mkckr0.audio_share_app.model.ServerEndpoint
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.service.AudioPlayer
import io.github.mkckr0.audio_share_app.ui.MainActivity
import io.github.mkckr0.audio_share_app.ui.screen.HomeScreenViewModel.UiState
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(viewModel: HomeScreenViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()

    when (val uiState = viewModel.uiState.collectAsStateWithLifecycle().value) {
        UiState.Loading -> {}
        is UiState.Success -> {
            var started by remember { mutableStateOf(false) }
            val servers = uiState.servers
            val enabledCount = servers.count { it.enabled }
            val endpointOptions = (
                uiState.discoveredEndpoints +
                    uiState.savedEndpoints +
                    servers.mapNotNull { server ->
                        server.host.takeIf { it.isNotBlank() }
                            ?.let { ServerEndpoint(it, server.port) }
                    }
                ).distinctBy { "${it.host.lowercase()}:${it.port}" }

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.4f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ServerDiscoveryPanel(
                        discoveredEndpoints = uiState.discoveredEndpoints,
                        isDiscovering = uiState.isDiscovering,
                        started = started,
                        onRefresh = viewModel::discoverServers,
                        onUseEndpoint = { endpoint ->
                            viewModel.useEndpoint(servers, endpoint)
                        },
                    )
                    servers.forEachIndexed { index, server ->
                        ServerCard(
                            server = server,
                            index = index,
                            enabledCount = enabledCount,
                            started = started,
                            endpointOptions = endpointOptions,
                            onChange = { updated ->
                                viewModel.saveServers(servers.replaceServer(updated))
                            },
                            onDelete = {
                                viewModel.saveServers(servers.filterNot { it.id == server.id })
                            },
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.addServer(servers) },
                        enabled = !started,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(context.getString(R.string.label_add_server))
                    }
                }

                IconButton(
                    onClick = {
                        if (!started && servers.none { it.enabled }) {
                            return@IconButton
                        }
                        scope.launch {
                            if (started) {
                                activity.awaitMediaController().stop()
                            } else {
                                viewModel.saveServers(servers, rememberEndpoints = true).join()
                                activity.awaitMediaController().play()
                            }
                        }
                    },
                    modifier = Modifier.size(80.dp),
                ) {
                    Icon(
                        imageVector = if (started) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Row(
                    modifier = Modifier.weight(0.8f)
                ) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        SelectionContainer {
                            Text(
                                text = AudioPlayer.message,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }

            LifecycleStartEffect(true) {
                val listener = object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        started = playWhenReady
                    }
                }

                scope.launch {
                    activity.awaitMediaController().run {
                        started = playWhenReady
                        addListener(listener)
                    }
                }

                onStopOrDispose {
                    scope.launch {
                        activity.awaitMediaController().removeListener(listener)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerDiscoveryPanel(
    discoveredEndpoints: List<ServerEndpoint>,
    isDiscovering: Boolean,
    started: Boolean,
    onRefresh: () -> Unit,
    onUseEndpoint: (ServerEndpoint) -> Unit,
) {
    val context = LocalContext.current
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.getString(R.string.label_server_discovery),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when {
                            isDiscovering -> context.getString(R.string.label_scanning_local_network)
                            discoveredEndpoints.isEmpty() -> context.getString(R.string.label_no_server_found)
                            else -> context.getString(
                                R.string.label_servers_found,
                                discoveredEndpoints.size,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (isDiscovering) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    IconButton(onClick = onRefresh, enabled = !started) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = context.getString(R.string.label_scan_again),
                        )
                    }
                }
            }
            discoveredEndpoints.forEach { endpoint ->
                FilledTonalButton(
                    onClick = { onUseEndpoint(endpoint) },
                    enabled = !started,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(context.getString(R.string.label_use_server, endpoint.displayName))
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: ServerConfig,
    index: Int,
    enabledCount: Int,
    started: Boolean,
    endpointOptions: List<ServerEndpoint>,
    onChange: (ServerConfig) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var portText by remember(server.id, server.port) { mutableStateOf(server.port.toString()) }
    var endpointMenuExpanded by remember(server.id) { mutableStateOf(false) }
    val canEnable = server.enabled || enabledCount < MAX_MIX_SERVER_COUNT

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = server.enabled,
                    enabled = !started && canEnable,
                    onCheckedChange = { onChange(server.copy(enabled = it)) },
                )
                OutlinedTextField(
                    value = server.name,
                    onValueChange = { onChange(server.copy(name = it.ifBlank { "Server ${index + 1}" })) },
                    enabled = !started,
                    label = { Text(context.getString(R.string.label_server_name)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDelete,
                    enabled = !started,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = server.host,
                    onValueChange = {
                        if (it.isEmpty() || !it.contains(Regex("\\s"))) {
                            onChange(server.copy(host = it))
                        }
                    },
                    enabled = !started,
                    isError = server.host.isBlank(),
                    label = { Text(context.getString(R.string.label_host)) },
                    modifier = Modifier.weight(0.7f),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        if (it.isEmpty() || it.isDigitsOnly() && it.toInt() in 1..65535) {
                            portText = it
                            it.toIntOrNull()?.let { port -> onChange(server.copy(port = port)) }
                        }
                    },
                    enabled = !started,
                    isError = portText.isBlank(),
                    label = { Text(context.getString(R.string.label_port)) },
                    modifier = Modifier.weight(0.3f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Box {
                    IconButton(
                        onClick = { endpointMenuExpanded = true },
                        enabled = !started && endpointOptions.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = context.getString(R.string.label_saved_servers),
                        )
                    }
                    DropdownMenu(
                        expanded = endpointMenuExpanded,
                        onDismissRequest = { endpointMenuExpanded = false },
                    ) {
                        endpointOptions.forEach { endpoint ->
                            DropdownMenuItem(
                                text = { Text(endpoint.displayName) },
                                onClick = {
                                    endpointMenuExpanded = false
                                    portText = endpoint.port.toString()
                                    onChange(server.copy(host = endpoint.host, port = endpoint.port))
                                },
                            )
                        }
                    }
                }
            }
            Text("${context.getString(R.string.label_volume_linear_gain)}: ${"%.2f".format(server.volume)}")
            Slider(
                value = server.volume,
                onValueChange = {
                    AudioPlayer.setServerVolume(server.id, it)
                    onChange(server.copy(volume = it))
                },
                valueRange = 0f..2f,
            )
        }
    }
}

private fun List<ServerConfig>.replaceServer(server: ServerConfig): List<ServerConfig> {
    return map { if (it.id == server.id) server else it }
}
