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
 * Uses a smooth, crackle-free continuous DAC playout architecture:
 *
 * - Studio / Dolby Quality: Deep jitter-absorbing buffer (120ms), normal performance mode,
 *   continuous playout with silence interpolation during Wi-Fi pauses, eliminating DAC underruns.
 * - Low Latency (0 ms): Minimal buffer (25ms), instant gaming/video sync.
 * - Smooth 2ms fade-in ramp on kept audio when queue trims excess packets.
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
                priority = Thread.MAX_PRIORITY
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
        var waitingForPrebuffer = true
        var silenceChunk = ByteArray(1920)

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
                    val frameBytes = channels * 2
                    val silenceFrames = rate * 10 / 1000 // 10ms
                    silenceChunk = ByteArray(silenceFrames * frameBytes)
                    waitingForPrebuffer = true
                }

                val activeTrack = track ?: break

                val prebufferTarget = if (exact) PREBUFFER_PACKETS_EXACT else PREBUFFER_PACKETS_LOW_LATENCY

                if (waitingForPrebuffer) {
                    val currentCount = synchronized(queue) { queue.size }
                    if (currentCount < prebufferTarget) {
                        val pkt = queue.poll(10, TimeUnit.MILLISECONDS)
                        if (pkt != null) {
                            synchronized(queue) { queue.offerFirst(pkt) }
                        }
                        if (!activity.isActive() && queue.isEmpty()) {
                            val next = queue.poll(1000, TimeUnit.MILLISECONDS)
                            if (next != null) {
                                synchronized(queue) { queue.offerFirst(next) }
                            } else {
                                break
                            }
                        }
                        continue
                    }
                    waitingForPrebuffer = false
                    if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { activeTrack.play() }
                    }
                }

                val packet = queue.poll(12, TimeUnit.MILLISECONDS)
                if (packet != null && packet.isNotEmpty()) {
                    if (needsFadeIn) {
                        needsFadeIn = false
                        applyFadeIn(packet, channels)
                    }
                    if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        runCatching { activeTrack.play() }
                    }
                    activeTrack.write(packet, 0, packet.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    // Momentary network jitter: feed silence to hardware DAC so it NEVER underruns
                    if (activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        if (activity.isActive()) {
                            activeTrack.write(silenceChunk, 0, silenceChunk.size, AudioTrack.WRITE_BLOCKING)
                        } else {
                            runCatching {
                                activeTrack.pause()
                                activeTrack.flush()
                            }
                            waitingForPrebuffer = true
                            val resumePkt = queue.poll(3000, TimeUnit.MILLISECONDS)
                            if (resumePkt != null) {
                                synchronized(queue) { queue.offerFirst(resumePkt) }
                            } else {
                                break // Exit idle thread after 3s of total inactivity
                            }
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

            val perfMode = if (exact) AudioTrack.PERFORMANCE_MODE_NONE else AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            val usage = if (exact) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_GAME
            val contentType = if (exact) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SONIFICATION

            val attrBuilder = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)

            if (exact && Build.VERSION.SDK_INT >= 32) {
                try {
                    AudioAttributes.Builder::class.java
                        .getMethod("setSpatializationBehavior", Int::class.javaPrimitiveType)
                        .invoke(attrBuilder, 0)
                } catch (_: Throwable) {}
            }

            val bufferSize = if (exact) {
                maxOf(minBuf * 4, sampleRate * (channels * 2) * 120 / 1000)
            } else {
                maxOf(minBuf, sampleRate * (channels * 2) * 25 / 1000)
            }

            AudioTrack.Builder()
                .setAudioAttributes(attrBuilder.build())
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .setPerformanceMode(perfMode)
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

        // Studio / Dolby Quality: 40ms pre-buffering, 100ms max buffer, trimmed to 40ms
        private const val PREBUFFER_PACKETS_EXACT = 4
        private const val MAX_PACKETS_EXACT = 10
        private const val TRIM_TARGET_EXACT = 4

        // Ultra-low Latency (0 ms): 15ms pre-buffering, 30ms max buffer, trimmed to 15ms
        private const val PREBUFFER_PACKETS_LOW_LATENCY = 3
        private const val MAX_PACKETS_LOW_LATENCY = 6
        private const val TRIM_TARGET_LOW_LATENCY = 3

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
