// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PcSpeakerPlayer(
    private val activity: ActivityWindow = ActivityWindow(ACTIVE_WINDOW_MS),
) {
    private val active = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(4)

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Volatile private var currentSampleRate = 48_000

    fun start(stream: InputStream) {
        if (active.getAndSet(true)) return
        queue.clear()
        _playing.value = true
        activity.markActive()

        Thread {
            val din = DataInputStream(stream)
            val header = ByteArray(6)
            val lenBytes = ByteArray(2)
            try {
                din.readFully(header)
                val sampleRate = ByteBuffer.wrap(header, 0, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int

                startPlaybackThread(sampleRate)

                while (active.get()) {
                    din.readFully(lenBytes)
                    val len = ByteBuffer.wrap(lenBytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short.toInt() and 0xFFFF

                    val pcm = ByteArray(len)
                    din.readFully(pcm)

                    if (!queue.offer(pcm)) {
                        queue.poll()
                        queue.offer(pcm)
                    }
                }
            } catch (_: Exception) {
                stop()
            }
        }.apply {
            name = "NectarLink-AudioNet"
            isDaemon = true
            start()
        }
    }

    private fun startPlaybackThread(sampleRate: Int) {
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(1920)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .apply {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                            }
                        }
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            try {
                while (active.get() && queue.isEmpty()) {
                    Thread.sleep(2)
                }
                track.play()

                while (active.get()) {
                    val frame = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                    track.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
                }
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }.apply {
            name = "NectarLink-AudioPlay"
            isDaemon = true
            start()
        }
    }

    fun playFrame(sampleRate: Int, channels: Int, pcm: ByteArray) {
        if (pcm.isEmpty() || sampleRate <= 0 || channels <= 0) return
        activity.markActive()
        _playing.value = true

        val rate = sampleRate.coerceIn(8_000, 96_000)
        if (active.compareAndSet(false, true)) {
            currentSampleRate = rate
            queue.clear()
            startPlaybackThread(rate)
        }
        enqueueFrame(pcm)
    }

    fun enqueueFrame(pcm: ByteArray) {
        if (!active.get()) return
        if (!queue.offer(pcm)) {
            queue.poll()
            queue.offer(pcm)
        }
    }

    fun stop() {
        active.set(false)
        _playing.value = false
        activity.reset()
        queue.clear()
    }

    companion object {
        const val ACTIVE_WINDOW_MS = 3_000L

        @Volatile
        var exactQuality: Boolean = true
    }
}
