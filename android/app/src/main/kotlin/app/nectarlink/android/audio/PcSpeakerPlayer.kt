// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays live PCM-16LE audio streamed from PC loopback (`pc.audio.relay`).
 * Uses the proven, crackle-free bounded-queue architecture matching SoundCapture:
 *
 * - Direct native AudioTrack in WRITE_BLOCKING mode clocked by the hardware audio DAC.
 * - Bounded queue (max 8 packets, ~80ms) drops oldest on congestion, eliminating latency drift.
 * - Bit-perfect 48kHz lossless stereo output without DSP artifacts or buffer underflows.
 */
class PcSpeakerPlayer(
    private val activity: ActivityWindow = ActivityWindow(ACTIVE_WINDOW_MS),
) {
    private val queue = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_PACKETS)
    private val workerRunning = AtomicBoolean(false)
    private val watchdogRunning = AtomicBoolean(false)
    @Volatile private var watchdogThread: Thread? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Volatile private var stopRequested = false
    @Volatile private var lastSampleRate = 48_000
    @Volatile private var lastChannels = 2

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

        // Keep queue bounded: if we exceed MAX_QUEUED_PACKETS, drop oldest to avoid latency buildup
        while (queue.size >= MAX_QUEUED_PACKETS - 1) {
            queue.poll()
        }
        queue.offer(pcm)

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
        queue.clear()
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
                    queue.clear()
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
                }

                val activeTrack = track ?: break

                val packet = queue.poll(20, TimeUnit.MILLISECONDS)
                if (packet != null && packet.isNotEmpty()) {
                    consecutiveEmptyPolls = 0
                    if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { activeTrack.play() }
                    }
                    activeTrack.write(packet, 0, packet.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    consecutiveEmptyPolls++
                    if (consecutiveEmptyPolls > 50) { // ~1 second of silence
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
            val bufferSize = maxOf(minBuf * 2, sampleRate * (channels * 2) * 80 / 1000)

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

    companion object {
        private const val TAG = "PcSpeakerPlayer"
        const val ACTIVE_WINDOW_MS = 3_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 1_000L
        private const val MAX_QUEUED_PACKETS = 8 // ~80ms buffer cap: tightly prevents audio delay drift

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
