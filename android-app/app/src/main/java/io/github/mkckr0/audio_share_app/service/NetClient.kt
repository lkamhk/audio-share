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
import android.util.Log
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.pb.Client.AudioFormat
import io.github.mkckr0.audio_share_app.pb.Client.Capabilities
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.ConnectedDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.toJavaAddress
import io.ktor.util.network.address
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class NetClient(val context: Context) {

    private val tag = NetClient::class.simpleName

    private fun defaultScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("NetClientCoroutine") + CoroutineExceptionHandler { _, cause ->
            Log.d(tag, cause.stackTraceToString())
            _callback?.launch {
                onError(cause.message, cause)
            }
        }
    )

    private var _callback: Callback? = null
    private var _scope: CoroutineScope? = null
    private val scope: CoroutineScope get() = _scope!!

    private var _selectorManager: SelectorManager? = null
    private val selectorManager get() = _selectorManager!!
    private var _tcpSocket: Socket? = null
    private val tcpSocket get() = _tcpSocket!!
//    private var _udpSocket: ConnectedDatagramSocket? = null
    private var _udpSocket: BoundDatagramSocket? = null
    private val udpSocket get() = _udpSocket!!

    private var _heartbeatLastTick = TimeSource.Monotonic.markNow()
    private var remoteUdpAddress: InetSocketAddress? = null
    private var protocolV2 = false
    private var sessionId = 0L
    private var capabilities: Capabilities? = null

    enum class CMD {
        CMD_NONE,
        CMD_GET_FORMAT,
        CMD_START_PLAY,
        CMD_HEARTBEAT,
        CMD_HELLO_V2,
    }

    interface Callback {
        val scope: CoroutineScope
        suspend fun log(message: String)
        suspend fun onReceiveAudioFormat(format: AudioFormat): Boolean
        suspend fun onPlaybackStarted()
        suspend fun onReceiveAudioData(audioData: NetworkAudioPacket)
        suspend fun onError(message: String?, cause: Throwable?)

        fun launch(block: suspend Callback.() -> Unit): Job {
            return scope.launch {
                block()
            }
        }
    }

    fun start(host: String, port: Int, callback: Callback) {
        Log.d(tag, "$host:$port")
        _scope = defaultScope()
        scope.launch {
            _callback = callback

            if (_selectorManager != null) {
                throw Exception("Repeat start")
            }

            _callback?.launch {
                log("${context.getString(R.string.label_connecting)} $host:$port")
            }
            try {
                connect(host, port, tryV2 = true)
            } catch (_: LegacyProtocolException) {
                closeSockets()
                connect(host, port, tryV2 = false)
            }
        }
    }

    private suspend fun connect(host: String, port: Int, tryV2: Boolean) {
        _selectorManager = SelectorManager(Dispatchers.IO)
        try {
            _tcpSocket = withTimeout(3.seconds) {
                aSocket(selectorManager).tcp().connect(host, port)
            }
        } catch (e: TimeoutCancellationException) {
            throw Exception(context.getString(R.string.label_timeout))
        } catch (e: UnresolvedAddressException) {
            throw Exception(context.getString(R.string.label_unresolved_address))
        }

        _callback?.launch { log("TCP connected") }

        val tcpReadChannel = tcpSocket.openReadChannel()
        val tcpWriteChannel = tcpSocket.openWriteChannel()

        protocolV2 = false
        if (tryV2) {
            try {
                tcpWriteChannel.writeCMD(CMD.CMD_HELLO_V2)
                val hello = withTimeout(1.seconds) { tcpReadChannel.readCMD() }
                if (hello != CMD.CMD_HELLO_V2) throw LegacyProtocolException()
                capabilities = tcpReadChannel.readCapabilities()
                protocolV2 = capabilities?.protocolVersion == AudioProtocol.VERSION_V2
                if (!protocolV2) throw LegacyProtocolException()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw LegacyProtocolException()
            }
        }

        tcpWriteChannel.writeCMD(CMD.CMD_GET_FORMAT)
        var cmd = tcpReadChannel.readCMD()
        if (cmd != CMD.CMD_GET_FORMAT) throw Exception("Unexpected format response")
        val audioFormat = tcpReadChannel.readAudioFormat() ?: throw Exception("Missing audio format")
        var accepted = false
        _callback?.launch { accepted = onReceiveAudioFormat(audioFormat) }?.join()
        if (!accepted) {
            closeSockets()
            return
        }

        _callback?.launch { log("get format success (${if (protocolV2) "v2" else "v1"})") }

        tcpWriteChannel.writeCMD(CMD.CMD_START_PLAY)
        cmd = tcpReadChannel.readCMD()
        if (cmd != CMD.CMD_START_PLAY) throw Exception("Unexpected start response")
        val id = tcpReadChannel.readIntLE()
        if (id <= 0) throw Exception("Invalid stream id")
        sessionId = if (protocolV2) tcpReadChannel.readLongLE() else 0L

        _callback?.launch { onPlaybackStarted() }

        _udpSocket = aSocket(selectorManager).udp()
            .bind(InetSocketAddress(tcpSocket.localAddress.toJavaAddress().address, 0))
        remoteUdpAddress = InetSocketAddress(host, port)

        scope.launch {
            _heartbeatLastTick = TimeSource.Monotonic.markNow()
            while (true) {
                if (TimeSource.Monotonic.markNow() - _heartbeatLastTick > 5.seconds) {
                    throw Exception("heartbeat timeout")
                }
                delay(3.seconds)
            }
        }
        scope.launch {
            while (true) {
                cmd = tcpReadChannel.readCMD()
                if (cmd == CMD.CMD_HEARTBEAT) {
                    _heartbeatLastTick = TimeSource.Monotonic.markNow()
                    tcpWriteChannel.writeCMD(CMD.CMD_HEARTBEAT)
                }
            }
        }

        scope.launch {
            val address = remoteUdpAddress!!
            if (protocolV2) {
                udpSocket.writeControlPacket(
                    AudioProtocol.controlPacket(AudioProtocol.FLAG_REGISTRATION, sessionId),
                    address,
                )
            } else {
                udpSocket.writeIntLE(id, address)
            }
            while (true) {
                val datagram = udpSocket.readByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
                val packet = if (protocolV2) {
                    AudioProtocol.parseAudioPacket(datagram, sessionId)?.let {
                        NetworkAudioPacket(it.payload, it.sequence, it.frameIndex, it.frameCount, it.retransmitted)
                    }
                } else {
                    NetworkAudioPacket(datagram)
                }
                if (packet != null) _callback?.onReceiveAudioData(packet)
            }
        }
    }

    fun requestRetransmit(sequence: UInt) {
        val address = remoteUdpAddress ?: return
        if (!protocolV2 || sessionId == 0L || _udpSocket == null || _scope == null) return
        scope.launch {
            udpSocket.writeControlPacket(
                AudioProtocol.controlPacket(AudioProtocol.FLAG_NACK, sessionId, sequence),
                address,
            )
        }
    }

    private fun closeSockets() {
        _udpSocket?.close()
        _udpSocket = null
        _tcpSocket?.close()
        _tcpSocket = null
        _selectorManager?.close()
        _selectorManager = null
        remoteUdpAddress = null
        sessionId = 0L
        capabilities = null
        protocolV2 = false
    }

    fun stop() {
        Log.d(tag, "stop")
        _callback?.scope?.cancel()
        _callback = null
        _scope?.cancel()
        _scope = null
        closeSockets()
    }

    private class LegacyProtocolException : Exception()
}
