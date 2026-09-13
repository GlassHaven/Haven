package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #442: a reporter proposed replacing the loopback TCP hop with PulseAudio's
 * shared memory to cut audio latency. Before doing that it is worth knowing
 * what the render buffer alone costs, because that is a floor no change of
 * transport can get under.
 *
 * These pin the conversion the log line uses to say so.
 */
class AudioBufferLatencyTest {

    @Test
    fun `the bridge's 64 KB floor is a third of a second of audio`() {
        // 48 kHz, 2 channels, 2 bytes per sample = 192000 B/s.
        assertEquals(341L, pcmBufferMillis(64 * 1024, 48000))
    }

    @Test
    fun `halving the buffer halves the latency`() {
        assertEquals(170L, pcmBufferMillis(32 * 1024, 48000))
        assertEquals(85L, pcmBufferMillis(16 * 1024, 48000))
    }

    /** Mono or 8-bit would double it; the format is not assumed silently. */
    @Test
    fun `channel count and sample width are part of the answer`() {
        assertEquals(682L, pcmBufferMillis(64 * 1024, 48000, channels = 1))
        assertEquals(682L, pcmBufferMillis(64 * 1024, 48000, bytesPerSample = 1))
    }

    /** A zero rate is a misconfiguration, not a divide-by-zero crash. */
    @Test
    fun `a nonsense sample rate reports zero rather than throwing`() {
        assertEquals(0L, pcmBufferMillis(64 * 1024, 0))
    }
}

class TrackBufferSizeTest {
    @Test
    fun `floor is 16 KB - 85 ms, a quarter of the old 64 KB floor`() {
        assertEquals(16 * 1024, TRACK_BUF_FLOOR_BYTES)
        assertEquals(85L, pcmBufferMillis(TRACK_BUF_FLOOR_BYTES, 48000))
    }

    @Test
    fun `device minimum gets three times headroom above the floor`() {
        assertEquals(16 * 1024, trackBufferSizeBytes(4 * 1024))
        assertEquals(24 * 1024, trackBufferSizeBytes(8 * 1024))
        assertEquals(30 * 1024, trackBufferSizeBytes(10 * 1024))
    }

    @Test
    fun `measured CPH2655 minimum lands at 360 ms`() {
        assertEquals(69192, trackBufferSizeBytes(23064))
        assertEquals(360L, pcmBufferMillis(69192, 48000))
    }
}
