// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcSpeakerPlayerTest {
    @Test
    fun invalidFramesDoNotSetPlaying() {
        var currentTime = 1_000L
        val window = ActivityWindow(windowMs = 3_000L, now = { currentTime })
        val player = PcSpeakerPlayer(window)

        assertFalse(player.playing.value)

        // Empty PCM
        player.playFrame(44_100, 2, ByteArray(0))
        assertFalse(player.playing.value)

        // Invalid sample rate
        player.playFrame(0, 2, ByteArray(16))
        assertFalse(player.playing.value)

        // Invalid channels
        player.playFrame(44_100, 0, ByteArray(16))
        assertFalse(player.playing.value)
    }

    @Test
    fun validFrameSetsPlayingAndStopResets() {
        var currentTime = 1_000L
        val window = ActivityWindow(windowMs = 3_000L, now = { currentTime })
        val player = PcSpeakerPlayer(window)

        assertFalse(player.playing.value)

        player.playFrame(44_100, 2, ByteArray(16))
        assertTrue(player.playing.value)

        player.stop()
        assertFalse(player.playing.value)
    }

    @Test
    fun jitterBufferBuffersAndTrimsCorrectly() {
        val jitter = AudioJitterBuffer(48_000, 2)
        val out = ByteArray(1920) // 10 ms

        // Before startBytes (10ms = 1920 bytes), fills silence
        jitter.fill(out)
        assertTrue(out.all { it == 0.toByte() })

        // Push 10 packets of 1920 bytes (100 ms total)
        for (i in 0 until 10) {
            val pkt = ByteArray(1920) { 0x42 }
            jitter.push(pkt)
        }

        // Buffer should have trimmed down to TRIM_TO_MS (15ms = 2880 bytes)
        // Read 10 ms (1920 bytes)
        val readBytes = jitter.fill(out)
        assertEquals(1920, readBytes)
        // Should contain non-zero audio bytes
        assertTrue(out.any { it != 0.toByte() })

        // Read another 10 ms: 2880 - 1920 = 960 bytes left, rest zero-padded
        jitter.fill(out)
        // First 960 bytes have audio, tail 960 bytes padded with 0
        assertTrue(out.take(960).any { it != 0.toByte() })
        assertTrue(out.drop(960).all { it == 0.toByte() })
    }
}
