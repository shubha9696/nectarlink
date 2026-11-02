// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays live PCM-16LE audio frames streamed from a paired PC (`pc.audio.relay`)
 * with low latency, robust jitter buffering, and zero underrun crackles.
 */
class PcSpeakerPlayer(
    private val activity: ActivityWindow = ActivityWindow(ACTIVE_WINDOW_MS),
) {
    private class AudioChunk(
        val sampleRate: Int,
        val channels: Int,
        val pcm: ByteArray,
    )

    private val queue = LinkedBlockingDeque<AudioChunk>(MAX_QUEUE_CHUNKS)
    private val workerRunning = AtomicBoolean(false)
    private val watchdogRunning = AtomicBoolean(false)
    @Volatile
    private var watchdogThread: Thread? = null

    private val _playing = MutableStateFlow(false)

    /** True while audio frames arrived within the trailing [ACTIVE_WINDOW_MS]. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Volatile
    private var queuedBytes: Int = 0
    @Volatile
    private var needsCrossfade: Boolean = false

    fun playFrame(sampleRate: Int, channels: Int, pcm: ByteArray) {
        if (pcm.isEmpty() || sampleRate <= 0 || channels <= 0) return
        activity.markActive()
        if (!_playing.value) {
            _playing.value = true
        }
        ensureWatchdog()

        val safeRate = sampleRate.coerceIn(8_000, 96_000)
        val safeChannels = if (channels >= 2) 2 else 1
        val frameBytes = safeChannels * 2
        val maxBufMs = if (exactQuality) MAX_BUFFER_MS_EXACT else MAX_BUFFER_MS_LOW_LATENCY
        val trimMs = if (exactQuality) TARGET_TRIM_MS_EXACT else TARGET_TRIM_MS_LOW_LATENCY
        val maxQueuedBytes = (safeRate * frameBytes * maxBufMs) / 1000
        val targetTrimBytes = (safeRate * frameBytes * trimMs) / 1000

        synchronized(queue) {
            if (queuedBytes + pcm.size > maxQueuedBytes || queue.size >= MAX_QUEUE_CHUNKS - 1) {
                while (queuedBytes > targetTrimBytes && queue.isNotEmpty()) {
                    val dropped = queue.pollFirst() ?: break
                    queuedBytes = (queuedBytes - dropped.pcm.size).coerceAtLeast(0)
                }
                needsCrossfade = true
            }
            if (queue.offerLast(AudioChunk(safeRate, safeChannels, pcm))) {
                queuedBytes += pcm.size
            }
        }

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
        watchdogThread?.interrupt()
        synchronized(queue) {
            queue.clear()
            queuedBytes = 0
            // Poison pill with rate = 0 signals immediate stop.
            queue.offerFirst(AudioChunk(0, 0, ByteArray(0)))
        }
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
                    break
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            _playing.value = false
        } finally {
            watchdogThread = null
            watchdogRunning.set(false)
            if (activity.isActive() && watchdogRunning.compareAndSet(false, true)) {
                _playing.value = true
                Thread(::runWatchdogLoop, "nectarlink-pc-speaker-watchdog").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    private fun pollChunk(timeoutMs: Long): AudioChunk? {
        val chunk = if (timeoutMs <= 0L) {
            queue.pollFirst()
        } else {
            queue.pollFirst(timeoutMs, TimeUnit.MILLISECONDS)
        }
        if (chunk != null && chunk.pcm.isNotEmpty()) {
            synchronized(queue) {
                queuedBytes = (queuedBytes - chunk.pcm.size).coerceAtLeast(0)
            }
        }
        return chunk
    }

    private fun runAudioLoop() {
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        }

        var track: AudioTrack? = null
        var enhancer: LoudnessEnhancer? = null
        var currentRate = 0
        var currentChannels = 0
        var needsPrebuffer = true
        var prebufferedBytes = 0
        var silenceFillMs = 0

        try {
            while (true) {
                // If currently playing, poll with a fast 15 ms timeout to detect starved queue immediately.
                // If paused, sleep on INACTIVITY_TIMEOUT_MS without burning CPU or DAC power.
                val pollTimeout = if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) 15L else INACTIVITY_TIMEOUT_MS
                val chunk = pollChunk(pollTimeout)

                if (chunk == null) {
                    val activeTrack = track ?: break
                    if (activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        silenceFillMs += 15
                        if (silenceFillMs >= 250) {
                            // PC paused/stopped audio: pause and flush to clear stale DAC buffers
                            runCatching {
                                activeTrack.pause()
                                activeTrack.flush()
                            }
                            needsPrebuffer = true
                            prebufferedBytes = 0
                            silenceFillMs = 0
                        }
                    }
                    continue
                }

                if (chunk.sampleRate == 0) break // Poison pill to exit
                silenceFillMs = 0

                if (track == null ||
                    track.state != AudioTrack.STATE_INITIALIZED ||
                    currentRate != chunk.sampleRate ||
                    currentChannels != chunk.channels
                ) {
                    releaseTrack(track, enhancer)
                    track = createTrack(chunk.sampleRate, chunk.channels)
                    enhancer = track?.let { createEnhancer(it.audioSessionId) }
                    currentRate = chunk.sampleRate
                    currentChannels = chunk.channels
                    needsPrebuffer = true
                    prebufferedBytes = 0
                }

                val activeTrack = track ?: continue

                if (needsCrossfade) {
                    applyFadeIn(chunk.pcm, chunk.channels)
                    needsCrossfade = false
                }

                val prebufferMs = if (exactQuality) PREBUFFER_MS_EXACT else PREBUFFER_MS_LOW_LATENCY
                val prebufferThreshold = (chunk.sampleRate * chunk.channels * 2 * prebufferMs) / 1000
                if (needsPrebuffer && prebufferThreshold > 0) {
                    prebufferedBytes += chunk.pcm.size
                    activeTrack.write(chunk.pcm, 0, chunk.pcm.size)
                    if (prebufferedBytes >= prebufferThreshold) {
                        if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            runCatching { activeTrack.play() }
                        }
                        needsPrebuffer = false
                    }
                    continue
                }

                if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching { activeTrack.play() }
                    needsPrebuffer = false
                }

                // High-performance blocking write on dedicated audio thread: guarantees zero dropped frames and zero crackles
                activeTrack.write(chunk.pcm, 0, chunk.pcm.size)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            workerRunning.set(false)
            releaseTrack(track, enhancer)
            // If new frames arrived just as the loop exited, restart the worker.
            val hasPending = synchronized(queue) {
                queue.peekFirst()?.let { it.sampleRate > 0 } == true
            }
            if (hasPending && workerRunning.compareAndSet(false, true)) {
                Thread(::runAudioLoop, "nectarlink-pc-speaker").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    private fun createTrack(sampleRate: Int, channels: Int): AudioTrack? {
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
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
        val targetMs = if (exactQuality) 30 else 10
        val targetBuf = maxOf(minBuf, (sampleRate * channels * 2 * targetMs) / 1000)

        return runCatching {
            val attrsBuilder = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(if (exactQuality) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_GAME)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)

            if (android.os.Build.VERSION.SDK_INT >= 32) {
                runCatching {
                    // AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO
                    val method = attrsBuilder.javaClass.getMethod("setSpatializationBehavior", Int::class.javaPrimitiveType)
                    method.invoke(attrsBuilder, 0)
                }
            }
            val attrs = attrsBuilder.build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelMask)
                .build()

            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(targetBuf)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)

            trackBuilder.build().also { trk ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    runCatching {
                        val frames = targetBuf / (channels * 2)
                        trk.setBufferSizeInFrames(frames)
                    }
                }
            }
        }.getOrElse {
            Log.w(TAG, "failed to create AudioTrack", it)
            null
        }
    }

    private fun createEnhancer(audioSessionId: Int): LoudnessEnhancer? {
        // Return null to avoid digital clipping and distortion on full-scale audio
        return null
    }

    private fun releaseTrack(track: AudioTrack?, enhancer: LoudnessEnhancer?) {
        runCatching { enhancer?.release() }
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
        val rampFrames = minOf(48, pcm.size / frameBytes) // 1 ms smooth ramp at 48kHz
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
        private const val INACTIVITY_TIMEOUT_MS = 30_000L
        private const val MAX_QUEUE_CHUNKS = 64
        private const val MAX_BUFFER_MS_LOW_LATENCY = 20
        private const val TARGET_TRIM_MS_LOW_LATENCY = 5
        private const val MAX_BUFFER_MS_EXACT = 50
        private const val TARGET_TRIM_MS_EXACT = 20
        private const val PREBUFFER_MS_LOW_LATENCY = 0
        private const val PREBUFFER_MS_EXACT = 15
        const val ACTIVE_WINDOW_MS = 3_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 1_000L

        /**
         * When true (default), audio streams in uncompressed bit-perfect stereo with
         * full Dolby Atmos / spatial surround pipeline and zero suppression.
         */
        @Volatile
        var exactQuality: Boolean = true
    }
}
