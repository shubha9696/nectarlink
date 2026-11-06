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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's sound while its screen is shared: what apps play (media, games,
 * and apps that don't say), captured via Android 10+
 * [AudioPlaybackCaptureConfiguration] or via the Elevated `shell` helper's
 * internal submix (`"A"` stream) as 48 kHz stereo PCM in 5 ms packets (real-time).
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
    private var thread: Thread? = null

    private fun applyVolumeForMode(mode: Int) {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        try {
            if (mode == 0) {
                // In mode 0 (PC only), optionally mute STREAM_MUSIC so only PC plays.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0)
                }
            } else {
                // In mode 1 (Both) or mode 2 (Phone only), unmute STREAM_MUSIC so phone speakers play naturally.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
                }
            }
        } catch (_: Exception) {}
    }

    private fun restoreVolume() {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
            }
        } catch (_: Exception) {}
    }

    private fun closeElevatedSocket() {
        val s = elevatedSocket
        elevatedSocket = null
        s?.let { runCatching { it.close() } }
    }

    internal fun onModeChanged(newMode: Int) {
        applyVolumeForMode(newMode)
        if (newMode == 2) {
            closeElevatedSocket()
            if (Elevated.running) {
                Elevated.stopAudio()
            }
        } else {
            // Reconnect elevated stream to switch route flags between loopback-render (both) and loopback-only (pc)
            closeElevatedSocket()
        }
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
            var configSent = false
            var started = 0L

            runCatching { record.startRecording() }

            while (running) {
                var filled = 0
                while (filled < packet.size && running) {
                    val read = record.read(packet, filled, packet.size - filled)
                    if (read < 0) break
                    filled += read
                }
                if (!running) break
                if (filled < packet.size) continue

                // In mode 2 ("Phone only"), audio is for phone speaker only; don't stream to PC
                if (soundMode == 2) continue

                if (!configSent) {
                    configSent = true
                    started = SystemClock.elapsedRealtimeNanos()
                    if (stream.send(VideoPacketKind.CONFIG, 0u, mirrorAudioConfig(RATE.toUInt(), CHANNELS.toUByte())) == MirrorSendResult.CLOSED) {
                        running = false
                        break
                    }
                }

                val time = ((SystemClock.elapsedRealtimeNanos() - started) / 1000).toULong()
                if (stream.send(VideoPacketKind.FRAME, time, packet.copyOf()) == MirrorSendResult.CLOSED) {
                    running = false
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord sound capture stopped", e)
        } finally {
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
        applyVolumeForMode(soundMode)
        try {
            while (running) {
                // In mode 2 ("Phone only"), release elevated submix recorder so Android
                // routes audio naturally to the phone's physical speakers.
                if (soundMode == 2) {
                    closeElevatedSocket()
                    if (Elevated.running) {
                        Elevated.stopAudio()
                    }
                    while (running && soundMode == 2) {
                        try {
                            Thread.sleep(150)
                        } catch (_: InterruptedException) {
                            if (!running) break
                        }
                    }
                    if (!running) break
                }

                val modeStr = if (soundMode == 1) "both" else "pc"
                val sock = Elevated.openAudioStream(modeStr)
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
                    while (running) {
                        if (soundMode == 2) break

                        var filled = 0
                        while (filled < packet.size && running) {
                            val read = input.read(packet, filled, packet.size - filled)
                            if (read <= 0) break
                            filled += read
                        }
                        if (!running) break
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
                        if (stream.send(VideoPacketKind.FRAME, time, packet.copyOf()) == MirrorSendResult.CLOSED) {
                            running = false
                            break
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
            restoreVolume()
            closeElevatedSocket()
            activeCaptures.remove(this)
            if (Elevated.running) {
                Elevated.stopAudio()
            }
            stream.end()
            onEnded()
        }
    }

    fun stop() {
        running = false
        val rec = audioRecord
        audioRecord = null
        val th = thread
        thread = null
        restoreVolume()
        activeCaptures.remove(this)
        closeElevatedSocket()
        if (Elevated.running) {
            Elevated.stopAudio()
        }
        stream.end()
        if (rec != null) {
            Thread({
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }, "sound-stop-release").start()
        }
    }

    companion object {
        private const val TAG = "SoundCapture"
        private const val RATE = 48_000
        private const val CHANNELS = 2
        /** 10 ms of 16-bit stereo. */
        private const val PACKET_BYTES = RATE / 100 * CHANNELS * 2
        // 0: PC only, 1: Both (PC & Phone), 2: Phone only
        @Volatile var soundMode: Int = 0

        private val _soundModeState = MutableStateFlow(soundMode)
        val soundModeState = _soundModeState.asStateFlow()

        var dualPlayback: Boolean
            get() = soundMode == 1
            set(v) { 
                soundMode = if (v) 1 else 0 
                _soundModeState.value = soundMode
            }

        private val activeCaptures = CopyOnWriteArrayList<SoundCapture>()

        fun setMode(mode: String) {
            val newMode = when (mode.lowercase()) {
                "sound_mode:phone", "phone" -> 2
                "sound_mode:both", "both" -> 1
                else -> 0 // pc
            }
            soundMode = newMode
            _soundModeState.value = newMode
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
