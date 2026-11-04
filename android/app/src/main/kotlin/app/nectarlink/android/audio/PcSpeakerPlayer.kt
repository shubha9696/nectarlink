// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays live PCM-16LE audio streamed from PC loopback (`pc.audio.relay`).
 * Uses a smooth, crackle-free jitter buffer matching the PC's audio_out architecture:
 *
 * - Pre-buffers 40ms in Exact/Studio mode (20ms in 0ms mode) before AudioTrack playout,
 *   absorbing network jitter without buffer underruns.
 * - When Wi-Fi congestion creates a backlog, trims excess packets and applies a 2ms
 *   smooth fade-in ramp to eliminate DC offsets, pops, and clicks completely.
 * - Native AudioTrack in WRITE_BLOCKING mode clocked by hardware DAC.
 */
class PcSpeakerPlayer(
    private val activity: ActivityWindow = ActivityWindow(ACTIVE_WINDOW_MS),
) {
    private val queue = LinkedBlockingDeque<ByteArray>(MAX_QUEUED_PACKETS)
    private val workerRunning = AtomicBoolean(false)
    private val watchdogRunning = AtomicBoolean(false)
    @Volatile private var watchdogThread: Thread? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Volatile private var stopRequested = false
    @Volatile private var lastSampleRate = 48_000
    @Volatile private var lastChannels = 2
    @Volatile private var needsFadeIn = false

    fun playFrame(sampleRate: Int, channels: Int, pcm: ByteArray) {
        if (pcm.isEmpty() || sampleRate <= 0 || channels <= 0) return
        activity.markActive()
        if (!_playing.value) {
            _playing.value = true
        }
        ensureWatchdog()

        val safeRate = sampleRate.coerceIn(8_000, 96_000)
        val safeChannels = if (channels >= 2) 2 else 1
        lastSampleRate = safeRate
        lastChannels = safeChannels

        val exact = exactQuality
        val maxPackets = if (exact) MAX_PACKETS_EXACT else MAX_PACKETS_LOW_LATENCY
        val trimTarget = if (exact) TRIM_TARGET_EXACT else TRIM_TARGET_LOW_LATENCY

        synchronized(queue) {
            if (queue.size >= maxPackets) {
                while (queue.size > trimTarget) {
                    queue.pollFirst()
                }
                needsFadeIn = true
            }
            queue.offerLast(pcm)
        }

        stopRequested = false
        if (workerRunning.compareAndSet(false, true)) {
            Thread(::runAudioLoop, "nectarlink-pc-speaker").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        activity.reset()
        _playing.value = false
        stopRequested = true
        synchronized(queue) { queue.clear() }
        watchdogThread?.interrupt()
    }

    private fun ensureWatchdog() {
        if (watchdogRunning.compareAndSet(false, true)) {
            Thread(::runWatchdogLoop, "nectarlink-pc-speaker-watchdog").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runWatchdogLoop() {
        watchdogThread = Thread.currentThread()
        try {
            while (true) {
                Thread.sleep(WATCHDOG_CHECK_INTERVAL_MS)
                if (!activity.isActive()) {
                    _playing.value = false
                    stopRequested = true
                    synchronized(queue) { queue.clear() }
                    break
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            watchdogRunning.set(false)
        }
    }

    private fun runAudioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var track: AudioTrack? = null
        var currentRate = 0
        var currentChannels = 0
        var currentExact = exactQuality
        var consecutiveEmptyPolls = 0
        var waitingForPrebuffer = true

        try {
            while (!stopRequested) {
                val exact = exactQuality
                val rate = lastSampleRate
                val channels = lastChannels

                if (track == null ||
                    track.state != AudioTrack.STATE_INITIALIZED ||
                    currentRate != rate ||
                    currentChannels != channels ||
                    currentExact != exact ||
                    trackRecreateRequested.getAndSet(false)
                ) {
                    releaseTrack(track)
                    track = createTrack(rate, channels, exact)
                    currentRate = rate
                    currentChannels = channels
                    currentExact = exact
                    consecutiveEmptyPolls = 0
                    waitingForPrebuffer = true
                }

                val activeTrack = track ?: break

                val prebufferTarget = if (exact) PREBUFFER_PACKETS_EXACT else PREBUFFER_PACKETS_LOW_LATENCY

                if (waitingForPrebuffer) {
                    val currentCount = synchronized(queue) { queue.size }
                    if (currentCount < prebufferTarget) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            break
                        }
                        consecutiveEmptyPolls++
                        if (consecutiveEmptyPolls > 200 && !activity.isActive()) {
                            break
                        }
                        continue
                    }
                    waitingForPrebuffer = false
                    consecutiveEmptyPolls = 0
                }

                val packet = synchronized(queue) { queue.pollFirst() }
                if (packet != null && packet.isNotEmpty()) {
                    consecutiveEmptyPolls = 0

                    if (needsFadeIn) {
                        needsFadeIn = false
                        applyFadeIn(packet, channels)
                    }

                    if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { activeTrack.play() }
                    }
                    activeTrack.write(packet, 0, packet.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    consecutiveEmptyPolls++
                    // Queue starved: re-enable pre-buffering to avoid repetitive DAC underruns
                    waitingForPrebuffer = true

                    if (consecutiveEmptyPolls > 40) { // ~400ms of silence
                        if (activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            runCatching {
                                activeTrack.pause()
                                activeTrack.flush()
                            }
                        }
                        if (consecutiveEmptyPolls > 150 && !activity.isActive()) {
                            break // Exit thread on sustained inactivity
                        }
                    }
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            workerRunning.set(false)
            releaseTrack(track)
        }
    }

    private fun createTrack(sampleRate: Int, channels: Int, exact: Boolean): AudioTrack? {
        val channelMask = if (channels >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(TAG, "invalid minBufferSize=$minBuf for rate=$sampleRate channels=$channels")
            return null
        }

        return runCatching {
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelMask)
                .build()

            val attrBuilder = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)

            if (exact && Build.VERSION.SDK_INT >= 32) {
                try {
                    AudioAttributes.Builder::class.java
                        .getMethod("setSpatializationBehavior", Int::class.javaPrimitiveType)
                        .invoke(attrBuilder, 0)
                } catch (_: Throwable) {}
            }

            // Allocate at least 4x minimum buffer size for flawless, glitch-free hardware streaming
            val bufferSize = maxOf(minBuf * 4, sampleRate * (channels * 2) * 120 / 1000)

            AudioTrack.Builder()
                .setAudioAttributes(attrBuilder.build())
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        }.getOrElse {
            Log.w(TAG, "failed to create AudioTrack", it)
            null
        }
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        }
        runCatching { track.release() }
    }

    private fun applyFadeIn(pcm: ByteArray, channels: Int) {
        val frameBytes = channels * 2
        val rampFrames = minOf(96, pcm.size / frameBytes) // 2 ms smooth ramp at 48kHz
        if (rampFrames <= 0) return
        for (i in 0 until rampFrames) {
            val factor = i.toFloat() / rampFrames.toFloat()
            val offset = i * frameBytes
            for (ch in 0 until channels) {
                val bytePos = offset + ch * 2
                if (bytePos + 1 < pcm.size) {
                    val sample = (pcm[bytePos].toInt() and 0xFF) or (pcm[bytePos + 1].toInt() shl 8)
                    val s16 = sample.toShort()
                    val faded = (s16 * factor).toInt().coerceIn(-32768, 32767).toShort()
                    pcm[bytePos] = (faded.toInt() and 0xFF).toByte()
                    pcm[bytePos + 1] = ((faded.toInt() shr 8) and 0xFF).toByte()
                }
            }
        }
    }

    companion object {
        private const val TAG = "PcSpeakerPlayer"
        const val ACTIVE_WINDOW_MS = 3_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 1_000L
        private const val MAX_QUEUED_PACKETS = 32

        // Exact / Studio Quality: 40ms pre-buffering, 120ms max buffer, trimmed to 40ms
        private const val PREBUFFER_PACKETS_EXACT = 4
        private const val MAX_PACKETS_EXACT = 12
        private const val TRIM_TARGET_EXACT = 4

        // Ultra-low Latency (0 ms): 20ms pre-buffering, 60ms max buffer, trimmed to 20ms
        private const val PREBUFFER_PACKETS_LOW_LATENCY = 2
        private const val MAX_PACKETS_LOW_LATENCY = 6
        private const val TRIM_TARGET_LOW_LATENCY = 2

        private val trackRecreateRequested = AtomicBoolean(false)

        @Volatile
        var exactQuality: Boolean = true
            set(value) {
                if (field != value) {
                    field = value
                    trackRecreateRequested.set(true)
                }
            }
    }
}
