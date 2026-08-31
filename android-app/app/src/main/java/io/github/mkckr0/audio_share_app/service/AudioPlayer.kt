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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.Commands
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures.immediateVoidFuture
import com.google.common.util.concurrent.ListenableFuture
import io.github.mkckr0.audio_share_app.R
import io.github.mkckr0.audio_share_app.model.AudioConfigKeys
import io.github.mkckr0.audio_share_app.model.MAX_MIX_SERVER_COUNT
import io.github.mkckr0.audio_share_app.model.NetworkConfigKeys
import io.github.mkckr0.audio_share_app.model.ServerConfig
import io.github.mkckr0.audio_share_app.model.audioConfigDataStore
import io.github.mkckr0.audio_share_app.model.getFloat
import io.github.mkckr0.audio_share_app.model.getInteger
import io.github.mkckr0.audio_share_app.model.getResourceUri
import io.github.mkckr0.audio_share_app.model.networkConfigDataStore
import io.github.mkckr0.audio_share_app.model.resolveServerConfigs
import io.github.mkckr0.audio_share_app.pb.Client
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class AudioPlayer(val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val tag = AudioPlayer::class.simpleName

    private var _initState: State = State.Builder()
        .setAvailableCommands(
            Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_STOP,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_METADATA,
                    COMMAND_RELEASE,
                )
                .build()
        )
        .build()
    private var _state: State = _initState
    override fun getState(): State = _state

    private val lock = Any()
    private val scope: CoroutineScope = MainScope()
    private val mixerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("AudioMixerScope"))
    private val retryScope: CoroutineScope = MainScope()

    private var _audioTrack: AudioTrack? = null
    private val audioTrack get() = _audioTrack!!

    private var _loudnessEnhancer: LoudnessEnhancer? = null
    private val loudnessEnhancer get() = _loudnessEnhancer!!

    private var outputFormat: Client.AudioFormat? = null
    private var mixerJob: Job? = null
    private var running = false
    private var sources = emptyList<SourceState>()
    private var targetLatencyMs = 50
    private var shortWriteCount = 0L
    private var writeErrorCount = 0L
    private var renderLateCount = 0L
    private var audioTrackBufferMs = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        var message by mutableStateOf("")
        private var activePlayer: AudioPlayer? = null

        fun setServerVolume(id: String, volume: Float) {
            activePlayer?.setSourceVolume(id, volume)
        }
    }

    init {
        activePlayer = this
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        return future {
            Log.d(tag, "handleSetPlayWhenReady playWhenReady=$playWhenReady")
            _state = state.buildUpon().setPlayerError(null).build()
            if (playWhenReady) {
                startMixPlayback()
            } else {
                stopMixPlayback(context.getString(R.string.label_paused))
            }
        }
    }

    override fun handleStop(): ListenableFuture<*> {
        Log.d(tag, "handleStop")
        stopMixPlayback(context.getString(R.string.label_stopped))
        _state = _initState.buildUpon()
            .setPlaybackState(STATE_IDLE)
            .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        return immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        Log.d(tag, "handleRelease")
        stopMixPlayback("")
        scope.cancel()
        retryScope.cancel()
        mixerScope.cancel()
        if (activePlayer === this) {
            activePlayer = null
        }
        _state = State.Builder().build()
        return immediateVoidFuture()
    }

    private suspend fun startMixPlayback() {
        stopMixPlayback("")
        val serverConfigs = loadServerConfigs()
            .filter { it.enabled }
            .take(MAX_MIX_SERVER_COUNT)

        if (serverConfigs.isEmpty()) {
            message = context.getString(R.string.label_no_server_enabled)
            return
        }

        running = true
        acquirePlaybackLocks()
        sources = serverConfigs.map { SourceState(it) }
        message = serverConfigs.joinToString("\n") { "${it.name}: ${context.getString(R.string.label_connecting)} ${it.host}:${it.port}" }

        _state = state.buildUpon()
            .setPlaylist(
                serverConfigs.mapIndexed { index, server ->
                    MediaItemData.Builder("media-${server.id}")
                        .setMediaItem(
                            MediaItem.fromUri("tcp://${server.host}:${server.port}").buildUpon()
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(server.name)
                                        .setArtist("${server.host}:${server.port}")
                                        .setArtworkUri(context.getResourceUri(R.drawable.artwork))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                }
            )
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        invalidateState()

        sources.forEach { startSource(it) }
    }

    private fun stopMixPlayback(statusMessage: String) {
        running = false
        retryScope.coroutineContext.cancelChildren()
        mixerJob?.cancel()
        mixerJob = null
        sources.forEach { it.client?.stop() }
        synchronized(lock) {
            sources = emptyList()
            outputFormat = null
        }
        releaseAudioTrack()
        releasePlaybackLocks()
        if (statusMessage.isNotEmpty()) {
            message = statusMessage
        }
    }

    private fun startSource(source: SourceState) {
        source.callbackScope = MainScope() + CoroutineName("NetClientCallback-${source.server.id}")
        source.client = NetClient(context.applicationContext)
        source.client?.start(
            host = source.server.host,
            port = source.server.port,
            callback = SourceCallback(source)
        )
    }

    private fun restartSource(source: SourceState, reason: String?) {
        retryScope.launch {
            source.client?.stop()
            source.accepted = false
            source.streamBuffer = null
            var wait = 3
            while (running && wait > 0) {
                source.status = "${reason ?: "error"}, ${context.getString(R.string.label_retry).format(wait)}"
                updateMessage()
                delay(1.seconds)
                --wait
            }
            if (running) {
                source.status = "${context.getString(R.string.label_connecting)} ${source.server.host}:${source.server.port}"
                updateMessage()
                startSource(source)
            }
        }
    }

    private suspend fun ensureAudioTrack(format: Client.AudioFormat): Boolean {
        synchronized(lock) {
            outputFormat?.let {
                return PcmMixer.isMixableFormat(it, format)
            }
        }
        if (!createAudioTrack(format)) return false
        synchronized(lock) { outputFormat = format }
        startMixerLoop()
        return true
    }

    private suspend fun createAudioTrack(format: Client.AudioFormat): Boolean {
        val encoding = toAndroidEncoding(format)
        val channelMask = toAndroidChannelMask(format.channels)
        if (encoding == AudioFormat.ENCODING_INVALID || channelMask == AudioFormat.CHANNEL_INVALID) return false
        val minBufferSize = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBufferSize <= 0) return false
        val audioConfig = context.audioConfigDataStore.data.first()
        targetLatencyMs = when (audioConfig[intPreferencesKey(AudioConfigKeys.LATENCY_PROFILE)] ?: 1) {
            0 -> 25
            2 -> 100
            else -> 50
        }
        val bufferScale = (audioConfig[floatPreferencesKey(AudioConfigKeys.BUFFER_SCALE)]
            ?: context.getFloat(R.string.default_buffer_scale)).toInt()
        val frameSize = PcmMixer.frameSize(format)
        val desiredTrackBufferMs = when {
            targetLatencyMs >= 100 -> 50
            targetLatencyMs >= 50 -> 40
            else -> 30
        }
        val desiredTrackBufferBytes = format.sampleRate * frameSize * desiredTrackBufferMs / 1000
        val maximumTrackBufferBytes = format.sampleRate * frameSize * 60 / 1000
        val scaledMinimumBytes = min(minBufferSize * bufferScale, maximumTrackBufferBytes)
        val trackBufferBytes = max(minBufferSize, max(desiredTrackBufferBytes, scaledMinimumBytes))

        _audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .setSampleRate(format.sampleRate)
                    .build()
            )
            .setBufferSizeInBytes(trackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            releaseAudioTrack()
            return false
        }
        audioTrackBufferMs = audioTrack.bufferSizeInFrames * 1000 / format.sampleRate

        val volume = audioConfig[floatPreferencesKey(AudioConfigKeys.VOLUME)]
            ?: context.getFloat(R.string.default_volume)
        audioTrack.setVolume(volume)

        val loudnessEnhancerGain =
            (audioConfig[floatPreferencesKey(AudioConfigKeys.LOUDNESS_ENHANCER)]
                ?: context.getFloat(R.string.default_loudness_enhancer)).toInt()
        if (loudnessEnhancerGain > 0) {
            _loudnessEnhancer = LoudnessEnhancer(audioTrack.audioSessionId)
            loudnessEnhancer.setTargetGain(loudnessEnhancerGain)
            loudnessEnhancer.setEnabled(true)
        }

        return true
    }

    private fun startMixerLoop() {
        if (mixerJob != null) {
            return
        }
        mixerJob = mixerScope.launch {
            var trackStarted = false
            var startupQuantaWritten = 0
            var lastStatsUpdate = 0L
            while (running) {
                val format = synchronized(lock) { outputFormat }
                if (format == null || _audioTrack == null) {
                    delay(2)
                    continue
                }
                val quantumFrames = max(1, format.sampleRate / 100)
                val activeSources = synchronized(lock) {
                    sources.filter { it.accepted && it.streamBuffer != null }
                }
                if (activeSources.isEmpty() || activeSources.any { !it.streamBuffer!!.isReady() }) {
                    delay(2)
                    continue
                }
                val chunks = activeSources.map { source ->
                    MixerSource(
                        source.streamBuffer!!.readFrames(quantumFrames)
                            ?: PcmMixer.silence(format, quantumFrames),
                        source.server.volume,
                    )
                }
                val mixed = PcmMixer.mix(format, chunks, quantumFrames * PcmMixer.frameSize(format))
                val writeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                if (!writeFully(mixed)) {
                    trackStarted = false
                    startupQuantaWritten = 0
                    recreateAudioTrack(format)
                    continue
                }
                val renderCompletedNs = android.os.SystemClock.elapsedRealtimeNanos()
                if (trackStarted && renderCompletedNs - writeStartedNs > 20_000_000L) {
                    renderLateCount++
                }
                if (!trackStarted) {
                    startupQuantaWritten++
                    val startupQuantaTarget = when {
                        targetLatencyMs >= 100 -> 4
                        targetLatencyMs >= 50 -> 3
                        else -> 2
                    }
                    if (startupQuantaWritten >= startupQuantaTarget) {
                        audioTrack.play()
                        trackStarted = true
                    }
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastStatsUpdate >= 1000) {
                    lastStatsUpdate = now
                    updatePlaybackStats()
                }
            }
        }
    }

    private suspend fun writeFully(buffer: ByteBuffer): Boolean {
        val result = AudioBufferWriter.writeFully(
            buffer = buffer,
            write = { data, requested -> audioTrack.write(data, requested, AudioTrack.WRITE_BLOCKING) },
            onZeroWrite = { delay(1) },
        )
        shortWriteCount += result.shortWrites
        if (result.completed) return true
        writeErrorCount++
        return false
    }

    private suspend fun recreateAudioTrack(format: Client.AudioFormat): Boolean {
        releaseAudioTrack()
        return createAudioTrack(format)
    }

    private fun updatePlaybackStats() {
        val underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && _audioTrack != null) {
            audioTrack.underrunCount
        } else 0
        val lines = synchronized(lock) {
            sources.map { source ->
                val buffer = source.streamBuffer
                val stats = buffer?.stats
                if (buffer == null || stats == null) {
                    "${source.server.name}: ${source.status}"
                } else {
                    "${source.server.name}: ${buffer.queuedMilliseconds()}ms " +
                        "recv=${stats.received} loss=${stats.lost} late=${stats.late} reorder=${stats.reordered} " +
                        "retry=${stats.retransmitted} conceal=${stats.concealedFrames} " +
                        "drop=${buffer.droppedMilliseconds()}ms drift=${stats.driftCorrections} invalid=${stats.invalidPackets} " +
                        "underflow=${stats.underflowEvents} rebuffer=${stats.rebufferEvents}"
                }
            }
        }
        val text = (lines + "AudioTrack: buffer=${audioTrackBufferMs}ms underrun=$underruns shortWrite=$shortWriteCount " +
            "writeError=$writeErrorCount renderLate=$renderLateCount").joinToString("\n")
        scope.launch { message = text }
    }

    private suspend fun loadServerConfigs(): List<ServerConfig> {
        val networkConfig = context.networkConfigDataStore.data.first()
        return resolveServerConfigs(
            serversJson = networkConfig[stringPreferencesKey(NetworkConfigKeys.SERVERS_JSON)],
            legacyHost = networkConfig[stringPreferencesKey(NetworkConfigKeys.HOST)],
            legacyPort = networkConfig[intPreferencesKey(NetworkConfigKeys.PORT)],
            defaultHost = context.getString(R.string.default_host),
            defaultPort = context.getInteger(R.integer.default_port),
        )
    }

    private fun setSourceVolume(id: String, volume: Float) {
        synchronized(lock) {
            sources.forEach {
                if (it.server.id == id) {
                    it.server = it.server.copy(volume = volume)
                }
            }
        }
    }

    private fun updateMessage() {
        message = sources.joinToString("\n") { "${it.server.name}: ${it.status}" }
    }

    private fun releaseAudioTrack() {
        _loudnessEnhancer?.release()
        _loudnessEnhancer = null
        _audioTrack?.run {
            pause()
            flush()
            release()
        }
        _audioTrack = null
        audioTrackBufferMs = 0
    }

    @Suppress("DEPRECATION")
    private fun acquirePlaybackLocks() {
        try {
            val powerManager = context.getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audio-share:playback").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(mode, "audio-share:streaming").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: RuntimeException) {
            Log.w(tag, "Unable to acquire playback locks", error)
            releasePlaybackLocks()
        }
    }

    private fun releasePlaybackLocks() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (error: RuntimeException) {
            Log.w(tag, "Unable to release playback locks", error)
        } finally {
            wifiLock = null
            wakeLock = null
        }
    }

    private fun toAndroidEncoding(format: Client.AudioFormat): Int {
        return when (format.encoding) {
            Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
            Client.AudioFormat.Encoding.ENCODING_PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
            Client.AudioFormat.Encoding.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
            Client.AudioFormat.Encoding.ENCODING_PCM_24BIT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            } else {
                AudioFormat.ENCODING_INVALID
            }
            Client.AudioFormat.Encoding.ENCODING_PCM_32BIT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioFormat.ENCODING_PCM_32BIT
            } else {
                AudioFormat.ENCODING_INVALID
            }
            else -> AudioFormat.ENCODING_INVALID
        }
    }

    private fun toAndroidChannelMask(channels: Int): Int {
        return when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            5 -> AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            7 -> AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_INVALID
        }
    }

    inner class SourceCallback(private val source: SourceState) : NetClient.Callback {
        override val scope: CoroutineScope get() = source.callbackScope

        override suspend fun log(message: String) {
            source.status = message
            updateMessage()
        }

        override suspend fun onReceiveAudioFormat(format: Client.AudioFormat): Boolean {
            val accepted = ensureAudioTrack(format)
            if (!accepted) {
                source.status = context.getString(R.string.label_format_mismatch)
                updateMessage()
                return false
            }
            source.streamBuffer = AudioStreamBuffer(
                format = format,
                targetLatencyMs = targetLatencyMs,
                requestRetransmit = { sequence -> source.client?.requestRetransmit(sequence) },
            )
            source.accepted = true
            source.status = "format ok"
            updateMessage()
            return true
        }

        override suspend fun onPlaybackStarted() {
            source.status = context.getString(R.string.label_started)
            updateMessage()
            if (sources.any { it.accepted }) {
                _state = state.buildUpon()
                    .setPlaybackState(STATE_READY)
                    .build()
                invalidateState()
            }
        }

        override suspend fun onReceiveAudioData(audioData: NetworkAudioPacket) {
            if (source.accepted) source.streamBuffer?.offer(audioData)
        }

        override suspend fun onError(message: String?, cause: Throwable?) {
            restartSource(source, message ?: cause?.message)
        }
    }

    data class SourceState(
        var server: ServerConfig,
        var client: NetClient? = null,
        var callbackScope: CoroutineScope = MainScope(),
        var accepted: Boolean = false,
        var status: String = "",
        var streamBuffer: AudioStreamBuffer? = null,
    )

    /**
     * All exceptions in ListenableFuture will be suppressed, need log it
     */
    private fun future(block: suspend CoroutineScope.() -> Unit): ListenableFuture<*> {
        return scope.future {
            try {
                block()
            } catch (e: Exception) {
                Log.e(tag, e.stackTraceToString())
            }
        }
    }
}
