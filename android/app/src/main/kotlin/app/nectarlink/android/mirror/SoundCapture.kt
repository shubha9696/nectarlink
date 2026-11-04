// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.nectarlink.android.elevated.Elevated
import app.nectarlink.core.MirrorSendResult
import app.nectarlink.core.MirrorStream
import app.nectarlink.core.VideoPacketKind
import app.nectarlink.core.mirrorAudioConfig
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's sound while its screen is shared: what apps play (media, games,
 * and apps that don't say), captured via Android 10+
 * [AudioPlaybackCaptureConfiguration] or via the Elevated `shell` helper's
 * internal submix (`"A"` stream) as 48 kHz stereo PCM in 10 ms packets.
 */
class SoundCapture(
    private val projection: MediaProjection?,
    private val stream: MirrorStream,
    private val context: Context? = null,
    /** Called once the sound stops (the PC stopped listening, or capture failed). */
    private val onEnded: () -> Unit = {},
) {
    @Volatile private var running = false
    @Volatile private var elevatedSocket: Socket? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var localPlayerTrack: AudioTrack? = null
    private val localQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(8)
    @Volatile private var localRunning = false
    private var localThread: Thread? = null
    private var thread: Thread? = null

    private fun applyVolumeForMode(mode: Int) {
        // No-op: Never mute STREAM_MUSIC, as doing so silences application output
        // before audio capture, causing both PC and phone to receive silence.
        // In Elevated mode, REMOTE_SUBMIX naturally silences phone speakers for PC only.
    }

    private fun restoreVolume() {
    }

    private fun closeElevatedSocket() {
        val s = elevatedSocket
        elevatedSocket = null
        s?.let { runCatching { it.close() } }
    }

    private fun startLocalWorker() {
        if (localThread != null && localRunning) return
        localRunning = true
        localThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(maxOf(minBuf * 4, PACKET_BYTES * 8))
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                localPlayerTrack = track
                track.play()

                while (localRunning && running) {
                    val pkt = localQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    track.write(pkt, 0, pkt.size)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.w(TAG, "Local player error", e)
            } finally {
                val t = localPlayerTrack
                localPlayerTrack = null
                t?.runCatching { stop() }
                t?.runCatching { release() }
            }
        }, "sound-local-player").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun playLocal(packet: ByteArray) {
        if (!localRunning || localThread == null) {
            startLocalWorker()
        }
        if (localQueue.remainingCapacity() == 0) {
            localQueue.poll()
        }
        localQueue.offer(packet.copyOf())
    }

    private fun stopLocal() {
        localRunning = false
        localThread?.interrupt()
        localThread = null
        localQueue.clear()
        val track = localPlayerTrack
        localPlayerTrack = null
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }

    internal fun onModeChanged(newMode: Int) {
        applyVolumeForMode(newMode)
        if (newMode != 1) {
            stopLocal()
        }
        // When switching to mode 2 ("phone only"), immediately close the capture socket / stop AudioRecord.
        // This releases REMOTE_SUBMIX in InputServer, allowing Android OS to route all audio directly to
        // the phone's physical speakers.
        if (newMode == 2) {
            closeElevatedSocket()
            if (Elevated.running) {
                Elevated.stopAudio()
            }
            audioRecord?.let { runCatching { it.stop() } }
        }
        thread?.interrupt()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        activeCaptures.add(this)

        // 1. Prefer MediaProjection AudioPlaybackCaptureConfiguration:
        // Native Android 10+ crystal clear capture at 48kHz stereo, lossless!
        val proj = projection
        if (proj != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val record = runCatching {
                val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(android.os.Process.myUid())
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minimum, PACKET_BYTES * 8))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            }.getOrNull()

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                running = true
                thread = Thread({ runRecord(record) }, "sound-capture").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
                return
            }
            record?.runCatching { release() }
        }

        // 2. Fall back to Elevated internal audio submix (InputServer REMOTE_SUBMIX)
        // when projection is null (e.g. standalone app windows without MediaProjection):
        if (Elevated.running) {
            running = true
            thread = Thread({ runElevated() }, "sound-capture-elevated").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            return
        }

        // Never fall back to recording microphone: internal audio playback requires MediaProjection or Elevated.

        Log.w(TAG, "no sound capture available")
        activeCaptures.remove(this)
        stream.end()
        onEnded()
    }

    private fun runRecord(record: AudioRecord) {
        audioRecord = record
        applyVolumeForMode(soundMode)
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val packet = ByteArray(PACKET_BYTES)
            var recordingStarted = false
            var configSent = false
            var started = 0L

            while (running) {
                if (soundMode == 2) {
                    if (recordingStarted) {
                        runCatching { record.stop() }
                        recordingStarted = false
                    }
                    while (running && soundMode == 2) {
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                            if (!running) break
                        }
                    }
                    if (!running) break
                }

                if (!recordingStarted) {
                    runCatching { record.startRecording() }
                    recordingStarted = true
                }

                var filled = 0
                while (filled < packet.size && running && soundMode != 2) {
                    val read = record.read(packet, filled, packet.size - filled)
                    if (read < 0) break
                    filled += read
                }
                if (!running || soundMode == 2) continue
                if (filled < packet.size) continue

                if (!configSent) {
                    configSent = true
                    started = SystemClock.elapsedRealtimeNanos()
                    if (stream.send(VideoPacketKind.CONFIG, 0u, mirrorAudioConfig(RATE.toUInt(), CHANNELS.toUByte())) == MirrorSendResult.CLOSED) {
                        running = false
                        break
                    }
                }

                val time = ((SystemClock.elapsedRealtimeNanos() - started) / 1000).toULong()
                if (soundMode != 2) {
                    if (stream.send(VideoPacketKind.FRAME, time, packet.copyOf()) == MirrorSendResult.CLOSED) {
                        running = false
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord sound capture stopped", e)
        } finally {
            stopLocal()
            restoreVolume()
            audioRecord = null
            activeCaptures.remove(this)
            runCatching { record.stop() }
            record.release()
            stream.end()
            onEnded()
        }
    }

    private fun runElevated() {
        try {
            while (running) {
                // If soundMode is 2 ("phone only"), keep socket closed so InputServer releases REMOTE_SUBMIX,
                // letting the phone output audio natively to its physical speakers.
                if (soundMode == 2) {
                    closeElevatedSocket()
                    while (running && soundMode == 2) {
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                            if (!running) break
                        }
                    }
                    if (!running) break
                }

                val sock = Elevated.openAudioStream()
                if (sock == null) {
                    try {
                        Thread.sleep(250)
                    } catch (_: InterruptedException) {
                        if (!running) break
                    }
                    continue
                }
                elevatedSocket = sock
                val input = sock.getInputStream()
                val packet = ByteArray(PACKET_BYTES)
                var configSent = false
                var started = 0L

                try {
                    while (running && soundMode != 2) {
                        var filled = 0
                        while (filled < packet.size && running && soundMode != 2) {
                            val read = input.read(packet, filled, packet.size - filled)
                            if (read <= 0) break
                            filled += read
                        }
                        if (!running || soundMode == 2) break
                        if (filled < packet.size) break

                        if (!configSent) {
                            configSent = true
                            started = SystemClock.elapsedRealtimeNanos()
                            if (stream.send(VideoPacketKind.CONFIG, 0u, mirrorAudioConfig(RATE.toUInt(), CHANNELS.toUByte())) == MirrorSendResult.CLOSED) {
                                running = false
                                break
                            }
                        }

                        val time = ((SystemClock.elapsedRealtimeNanos() - started) / 1000).toULong()
                        if (soundMode != 2) {
                            if (stream.send(VideoPacketKind.FRAME, time, packet.copyOf()) == MirrorSendResult.CLOSED) {
                                running = false
                                break
                            }
                        }
                        if (soundMode == 1) {
                            playLocal(packet)
                        } else {
                            stopLocal()
                        }
                    }
                } catch (e: Exception) {
                    if (running && soundMode != 2) {
                        Log.d(TAG, "Elevated sound capture socket closed: ${e.message}")
                    }
                } finally {
                    closeElevatedSocket()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Elevated sound capture stopped", e)
        } finally {
            stopLocal()
            closeElevatedSocket()
            if (Elevated.running) {
                Elevated.stopAudio()
            }
            activeCaptures.remove(this)
            stream.end()
            onEnded()
        }
    }

    fun stop() {
        running = false
        stopLocal()
        restoreVolume()
        activeCaptures.remove(this)
        closeElevatedSocket()
        if (Elevated.running) {
            Elevated.stopAudio()
        }
        audioRecord?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        audioRecord = null
        stream.end()
        thread?.interrupt()
        thread = null
    }

    companion object {
        private const val TAG = "SoundCapture"
        private const val RATE = 48_000
        private const val CHANNELS = 2
        /** 10 ms of 16-bit stereo. */
        private const val PACKET_BYTES = RATE / 100 * CHANNELS * 2
        // 0: PC only, 1: Both (PC & Phone), 2: Phone only
        @Volatile var soundMode: Int = 0

        var dualPlayback: Boolean
            get() = soundMode == 1
            set(v) { soundMode = if (v) 1 else 0 }

        private val activeCaptures = CopyOnWriteArrayList<SoundCapture>()

        fun setMode(mode: String) {
            val newMode = when (mode.lowercase()) {
                "sound_mode:phone", "phone" -> 2
                "sound_mode:both", "both" -> 1
                else -> 0 // pc
            }
            soundMode = newMode
            if (newMode == 2 && Elevated.running) {
                Elevated.stopAudio()
            }
            Log.i(TAG, "Audio mode set to: $mode (code $soundMode, ${activeCaptures.size} active captures)")
            for (capture in activeCaptures) {
                capture.onModeChanged(newMode)
            }
        }

        /** Whether this phone can share its sound (Elevated helper, active MediaProjection, or Android 10+ with RECORD_AUDIO). */
        fun canCapture(context: Context, projection: MediaProjection? = null): Boolean =
            Elevated.running ||
                projection != null ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
}
