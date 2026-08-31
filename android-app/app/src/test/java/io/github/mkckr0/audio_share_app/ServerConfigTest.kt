package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.model.ServerConfig
import io.github.mkckr0.audio_share_app.model.ServerConfigJson
import io.github.mkckr0.audio_share_app.model.ServerEndpoint
import io.github.mkckr0.audio_share_app.model.ServerEndpointJson
import io.github.mkckr0.audio_share_app.model.resolveServerConfigs
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConfigTest {

    @Test
    fun resolveServerConfigs_firstLaunchReturnsEmptyList() {
        assertEquals(
            emptyList<ServerConfig>(),
            resolveServerConfigs(null, null, null, "127.0.0.1", 65530),
        )
    }

    @Test
    fun resolveServerConfigs_preservesLegacyEndpoint() {
        val servers = resolveServerConfigs(null, "192.168.1.20", 65531, "127.0.0.1", 65530)

        assertEquals(1, servers.size)
        assertEquals("192.168.1.20", servers.single().host)
        assertEquals(65531, servers.single().port)
    }

    @Test
    fun resolveServerConfigs_savedEmptyListStaysEmpty() {
        assertEquals(
            emptyList<ServerConfig>(),
            resolveServerConfigs("[]", "192.168.1.20", 65531, "127.0.0.1", 65530),
        )
    }

    @Test
    fun encodeDecode_roundTrip() {
        val servers = listOf(
            ServerConfig(
                id = "server-1",
                name = "PC 1",
                host = "192.168.1.10",
                port = 65530,
                enabled = true,
                volume = 0.75f,
            ),
            ServerConfig(
                id = "server-2",
                name = "PC 2",
                host = "192.168.1.11",
                port = 65531,
                enabled = false,
                volume = 1.25f,
            )
        )

        assertEquals(servers, ServerConfigJson.decode(ServerConfigJson.encode(servers)))
    }

    @Test
    fun endpointEncodeDecode_roundTrip() {
        val endpoints = listOf(
            ServerEndpoint(host = "192.168.1.10", port = 65530),
            ServerEndpoint(host = "audio-pc.local", port = 65531),
        )

        assertEquals(endpoints, ServerEndpointJson.decode(ServerEndpointJson.encode(endpoints)))
    }
}
