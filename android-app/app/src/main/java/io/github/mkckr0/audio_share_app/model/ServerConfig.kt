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

package io.github.mkckr0.audio_share_app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

const val MAX_MIX_SERVER_COUNT = 4

@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val enabled: Boolean = true,
    val volume: Float = 1f,
)

@Serializable
data class ServerEndpoint(
    val host: String,
    val port: Int,
) {
    val displayName: String
        get() = "$host:$port"
}

object ServerConfigJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(servers: List<ServerConfig>): String {
        return json.encodeToString(servers)
    }

    fun decode(value: String): List<ServerConfig> {
        return json.decodeFromString(value)
    }
}

object ServerEndpointJson {
    fun encode(endpoints: List<ServerEndpoint>): String {
        return ServerConfigJson.json.encodeToString(endpoints)
    }

    fun decode(value: String): List<ServerEndpoint> {
        return ServerConfigJson.json.decodeFromString(value)
    }
}

fun resolveServerConfigs(
    serversJson: String?,
    legacyHost: String?,
    legacyPort: Int?,
    defaultHost: String,
    defaultPort: Int,
): List<ServerConfig> {
    if (serversJson != null) {
        runCatching { ServerConfigJson.decode(serversJson) }.getOrNull()?.let { return it }
    }
    if (legacyHost == null && legacyPort == null) {
        return emptyList()
    }
    return listOf(
        ServerConfig(
            name = "Server 1",
            host = legacyHost ?: defaultHost,
            port = legacyPort ?: defaultPort,
        )
    )
}
