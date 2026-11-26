package sh.haven.core.rdp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class RdpMicrophoneBackendTest {

    private class FakeMicSource : RdpMicrophoneBackend.MicSourceAdapter {
        var permitted: Boolean = false
        var opens: Int = 0
        var closes: Int = 0
        var lastPacketBytes: Int = 0
        var emitPacket: ((ByteArray) -> Unit)? = null

        override fun hasMicPermission(): Boolean = permitted

        override fun openCapture(
            sampleRate: Int,
            channels: Int,
            packetBytes: Int,
            onPacket: (ByteArray) -> Unit,
        ): Closeable? {
            if (!permitted) return null
            opens += 1
            lastPacketBytes = packetBytes
            emitPacket = onPacket
            return Closeable { closes += 1 }
        }
    }

    @Test
    fun `onMicOpen declines without permission and streams exact packet size once permitted`() {
        val fake = FakeMicSource()
        val forwarded = mutableListOf<ByteArray>()
        val backend = RdpMicrophoneBackend(
            source = fake,
            pushPcm = { bytes ->
                forwarded.add(bytes)
                true
            },
        )

        assertFalse(backend.onMicOpen(48_000u, 1u, 480u))
        assertEquals(0, fake.opens)

        fake.permitted = true
        assertTrue(backend.onMicOpen(48_000u, 1u, 480u))
        assertEquals(1, fake.opens)
        assertEquals(960, fake.lastPacketBytes)

        val pcm = ByteArray(960) { (it and 0x7F).toByte() }
        fake.emitPacket!!.invoke(pcm)
        assertEquals(1, forwarded.size)
        assertArrayEquals(pcm, forwarded[0])

        // Format change reopens cleanly and closes previous capture handle.
        assertTrue(backend.onMicOpen(16_000u, 2u, 320u))
        assertEquals(2, fake.opens)
        assertEquals(1, fake.closes)
        assertEquals(1280, fake.lastPacketBytes)

        backend.onMicClose()
        assertEquals(2, fake.closes)
        backend.close()
        assertEquals(2, fake.closes)
    }
}
