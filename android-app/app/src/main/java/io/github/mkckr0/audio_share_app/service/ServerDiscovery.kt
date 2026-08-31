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

package io.github.mkckr0.audio_share_app.service

import android.content.Context
import android.net.ConnectivityManager
import io.github.mkckr0.audio_share_app.model.ServerEndpoint
import io.github.mkckr0.audio_share_app.pb.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ServerDiscovery(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    suspend fun discover(ports: Set<Int>): List<ServerEndpoint> = coroutineScope {
        val hosts = localSubnetHosts()
        val limiter = Semaphore(MAX_CONCURRENT_PROBES)

        ports.filter { it in 1..65535 }.take(MAX_DISCOVERY_PORTS).flatMap { port ->
            hosts.map { host ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        if (isAudioShareServer(host, port)) ServerEndpoint(host, port) else null
                    }
                }
            }
        }.awaitAll().filterNotNull().distinct()
            .sortedWith(compareBy<ServerEndpoint> { ipv4ToLong(it.host) }.thenBy { it.port })
    }

    @Suppress("DEPRECATION")
    private fun localSubnetHosts(): Set<String> {
        val result = linkedSetOf<String>()
        connectivityManager.allNetworks.forEach { network ->
            val properties = connectivityManager.getLinkProperties(network) ?: return@forEach
            properties.linkAddresses.forEach { linkAddress ->
                val address = linkAddress.address as? Inet4Address ?: return@forEach
                if (address.isLoopbackAddress || address.isLinkLocalAddress) return@forEach

                val prefix = linkAddress.prefixLength.coerceAtLeast(24)
                val hostBits = 32 - prefix
                val ownAddress = ipv4ToLong(address.hostAddress ?: return@forEach)
                val mask = if (hostBits == 32) 0L else (0xffffffffL shl hostBits) and 0xffffffffL
                val networkAddress = ownAddress and mask
                val broadcastAddress = networkAddress or mask.inv().and(0xffffffffL)

                for (candidate in (networkAddress + 1) until broadcastAddress) {
                    if (candidate != ownAddress) result += longToIpv4(candidate)
                }
            }
        }
        return result
    }

    private suspend fun isAudioShareServer(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = PROBE_TIMEOUT_MS
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                socket.getOutputStream().write(
                    ByteBuffer.allocate(Int.SIZE_BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(NetClient.CMD.CMD_GET_FORMAT.ordinal)
                        .array()
                )
                socket.getOutputStream().flush()

                val input = DataInputStream(socket.getInputStream())
                val command = readIntLE(input)
                val size = readIntLE(input)
                if (command != NetClient.CMD.CMD_GET_FORMAT.ordinal || size !in 1..MAX_FORMAT_SIZE) {
                    return@use false
                }
                val payload = ByteArray(size)
                input.readFully(payload)
                Client.AudioFormat.parseFrom(payload).sampleRate > 0
            }
        }.getOrDefault(false)
    }

    private fun readIntLE(input: DataInputStream): Int {
        return Integer.reverseBytes(input.readInt())
    }

    private fun ipv4ToLong(host: String): Long {
        return host.split('.').fold(0L) { value, part -> (value shl 8) or part.toLong() }
    }

    private fun longToIpv4(value: Long): String {
        return listOf(24, 16, 8, 0).joinToString(".") { shift ->
            ((value shr shift) and 0xff).toString()
        }
    }

    private companion object {
        const val MAX_CONCURRENT_PROBES = 32
        const val MAX_DISCOVERY_PORTS = 8
        const val PROBE_TIMEOUT_MS = 450
        const val MAX_FORMAT_SIZE = 4096
    }
}
