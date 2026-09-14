package sh.haven.app.agent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM pins for the NTP wire codec: epoch math, header fields, field echo. */
class SntpPacketTest {

    @Test
    fun `unix ms maps into ntp seconds with the 1900 offset`() {
        val ts = SntpPacket.ntpFromUnixMs(1_700_000_000_000L)
        assertEquals(1_700_000_000L + SntpPacket.NTP_UNIX_OFFSET_S, ts.seconds)
        assertEquals(0, ts.fraction)
    }

    @Test
    fun `sub-ms fills the 2^32 fraction`() {
        val ts = SntpPacket.ntpFromUnixMs(1_700_000_000_000L, subMs = 0.5)
        val expected = (0.5 / 1000.0 * (1L shl 32).toDouble()).toInt()
        assertEquals(expected, ts.fraction)
        // Round-trip through the wire encoding.
        val buf = ByteArray(SntpPacket.SIZE)
        SntpPacket.writeTimestamp(buf, 0, ts)
        val back = SntpPacket.readTimestamp(buf, 0)
        assertEquals(ts.seconds, back.seconds)
        assertEquals(ts.fraction, back.fraction)
    }

    @Test
    fun `sub-ms above half encodes the full unsigned fraction`() {
        // Regression: Double.toInt() clamps at 2^31, so every sub-ms in
        // (500, 1000) used to encode as exactly 0.5 s (0x7FFFFFFF).
        val ts = SntpPacket.ntpFromUnixMs(1_700_000_000_000L, subMs = 750.0)
        assertEquals(0xC0000000L.toInt(), ts.fraction) // 0.75 of 2^32
        val buf = ByteArray(SntpPacket.SIZE)
        SntpPacket.writeTimestamp(buf, 0, ts)
        assertEquals(0xC0, buf[4].toInt() and 0xFF)
        assertEquals(ts, SntpPacket.readTimestamp(buf, 0))
    }

    @Test
    fun `client request parses only mode-3 packets`() {
        // LI=0, VN=4, mode=3 → 0b00_100_011 = 0x23.
        val req = ByteArray(SntpPacket.SIZE)
        req[0] = 0x23
        val transmit = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        transmit.copyInto(req, 40)
        val parsed = SntpPacket.parseClient(req)
        assertNotNull(parsed)
        assertEquals(4, parsed!!.version)
        assertEquals(3, parsed.mode)
        assertArrayEquals(transmit, parsed.transmit)

        // A server (mode 4) packet is not a client request.
        req[0] = 0x24
        assertNull(SntpPacket.parseClient(req))
        // Short buffer.
        assertNull(SntpPacket.parseClient(ByteArray(SntpPacket.SIZE - 1)))
    }

    @Test
    fun `server answer carries the gps discipline fields`() {
        val out = ByteArray(SntpPacket.SIZE)
        val refTs = SntpPacket.ntpFromUnixMs(1_700_000_000_000L)
        val rxTs = SntpPacket.ntpFromUnixMs(1_700_000_001_000L)
        val txTs = SntpPacket.ntpFromUnixMs(1_700_000_001_000L, subMs = 0.25)
        val req = SntpPacket.parseClient(ByteArray(SntpPacket.SIZE).also {
            it[0] = 0x23
            byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2).copyInto(it, 40)
        })!!
        SntpPacket.encodeServer(
            out = out, leap = SntpPacket.LI_NONE, version = 4,
            stratum = SntpPacket.STRATUM_GPS, poll = 6, precision = -16,
            rootDelayS = 0.0, rootDispersionS = 0.012, // 12 ms
            refId = SntpPacket.REFID_GPS,
            referenceTs = refTs, originateTs = req.transmit,
            receiveTs = rxTs, transmitTs = txTs,
        )
        // First byte: LI=0, VN=4, mode=4 → 0b00_100_100 = 0x24.
        assertEquals(0x24, out[0].toInt() and 0xFF)
        assertEquals(1, out[1].toInt()) // stratum GPS
        assertEquals(6, out[2].toInt()) // poll echo
        assertEquals(-16, out[3].toInt()) // precision
        assertArrayEquals(SntpPacket.REFID_GPS, out.copyOfRange(12, 16))
        assertArrayEquals(req.transmit, out.copyOfRange(24, 32)) // originate echo
        // rootDispersion 12 ms in 16.16 fixed point: 0.012 * 65536 = 786.4 → 786.
        val disp = ((out[8].toInt() and 0xFF) shl 24) or ((out[9].toInt() and 0xFF) shl 16) or
            ((out[10].toInt() and 0xFF) shl 8) or (out[11].toInt() and 0xFF)
        assertEquals(786, disp)
    }

    @Test
    fun `writeFixed32 clamps and encodes seconds`() {
        val out = ByteArray(4)
        SntpPacket.writeFixed32(out, 0, 0.5)
        assertEquals(32768, ((out[0].toInt() and 0xFF) shl 24) or ((out[1].toInt() and 0xFF) shl 16) or
            ((out[2].toInt() and 0xFF) shl 8) or (out[3].toInt() and 0xFF))
        SntpPacket.writeFixed32(out, 0, -1.0) // clamps to 0
        assertEquals(0, ((out[0].toInt() and 0xFF) shl 24) or ((out[1].toInt() and 0xFF) shl 16) or
            ((out[2].toInt() and 0xFF) shl 8) or (out[3].toInt() and 0xFF))
    }
}