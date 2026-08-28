package sh.haven.core.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.DataInputStream

/** Coverage for the URB->transfer bridge (the device-independent half of Slice 0). */
class UsbIpServerTest {

    /** Records the last call and returns canned IN data. */
    private class FakeBackend(
        private val inData: ByteArray = ByteArray(0),
        private val fail: Boolean = false,
        @Volatile var alive: Boolean = true,
    ) : UsbIpBackend {
        var lastControl: IntArray? = null
        var lastBulkEndpoint: Int? = null
        var lastOut: ByteArray? = null

        override fun isAlive(): Boolean = alive

        override fun control(requestType: Int, request: Int, value: Int, index: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray {
            if (fail) throw java.io.IOException("boom")
            lastControl = intArrayOf(requestType, request, value, index, length)
            lastOut = out
            return inData
        }

        override fun bulk(endpointAddress: Int, out: ByteArray, length: Int, timeoutMs: Int): ByteArray {
            if (fail) throw java.io.IOException("boom")
            lastBulkEndpoint = endpointAddress
            lastOut = out
            return inData
        }
    }

    private fun submit(
        seqnum: Int,
        direction: Int,
        ep: Int,
        transferBufferLength: Int,
        setup: ByteArray = ByteArray(8),
        out: ByteArray = ByteArray(0),
    ) = UsbIpProtocol.Urb.Submit(
        seqnum = seqnum, devid = 0, direction = direction, ep = ep,
        transferFlags = 0, transferBufferLength = transferBufferLength, interval = 0,
        setup = setup, out = out,
    )

    private fun replyStatusAndLength(reply: ByteArray): Pair<Int, Int> =
        DataInputStream(reply.inputStream()).use { din ->
            repeat(5) { din.readInt() } // command, seqnum, devid, direction, ep
            din.readInt() to din.readInt() // status, actual_length
        }

    @Test
    fun `control IN parses little-endian setup and returns data`() {
        val backend = FakeBackend(inData = ByteArray(18) { it.toByte() })
        // GET_DESCRIPTOR(device): bmRequestType 0x80, bRequest 6, wValue 0x0100, wIndex 0, wLength 0x12.
        val setup = byteArrayOf(0x80.toByte(), 6, 0x00, 0x01, 0, 0, 0x12, 0)
        val reply = UsbIpServer.bridgeSubmit(submit(1, UsbIpProtocol.DIR_IN, ep = 0, transferBufferLength = 0x12, setup = setup), backend, 1000)

        assertArrayEquals(intArrayOf(0x80, 6, 0x0100, 0, 0x12), backend.lastControl)
        val (status, actualLength) = replyStatusAndLength(reply)
        assertEquals(0, status)
        assertEquals(18, actualLength)
        assertEquals(48 + 18, reply.size)
    }

    @Test
    fun `interrupt IN maps ep to 0x80 address and carries data`() {
        val backend = FakeBackend(inData = byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        val reply = UsbIpServer.bridgeSubmit(submit(2, UsbIpProtocol.DIR_IN, ep = 4, transferBufferLength = 64), backend, 1000)
        assertEquals(0x84, backend.lastBulkEndpoint)
        val (status, actualLength) = replyStatusAndLength(reply)
        assertEquals(0, status)
        assertEquals(2, actualLength)
    }

    @Test
    fun `interrupt OUT maps ep to plain address and reports bytes sent`() {
        val backend = FakeBackend()
        val payload = byteArrayOf(1, 2, 3, 4)
        val reply = UsbIpServer.bridgeSubmit(submit(3, UsbIpProtocol.DIR_OUT, ep = 4, transferBufferLength = payload.size, out = payload), backend, 1000)
        assertEquals(0x04, backend.lastBulkEndpoint)
        assertArrayEquals(payload, backend.lastOut)
        val (status, actualLength) = replyStatusAndLength(reply)
        assertEquals(0, status)
        assertEquals(4, actualLength) // bytes sent, no IN payload
        assertEquals(48, reply.size)
    }

    @Test
    fun `pollIn returns an IN reply once data arrives`() {
        val backend = FakeBackend(inData = byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        val reply = UsbIpServer.pollIn(submit(5, UsbIpProtocol.DIR_IN, ep = 4, transferBufferLength = 64), backend, emptySet(), 10)
        assertNotNull(reply)
        assertEquals(0x84, backend.lastBulkEndpoint)
        val (status, actualLength) = replyStatusAndLength(reply!!)
        assertEquals(0, status)
        assertEquals(2, actualLength)
    }

    @Test
    fun `pollIn stops without polling once the device is gone`() {
        // A standing interrupt-IN poll on an idle connection must bail when the
        // device is unplugged / re-enumerated, not spin on failing transfers.
        val backend = FakeBackend(inData = byteArrayOf(1), alive = false)
        val reply = UsbIpServer.pollIn(submit(7, UsbIpProtocol.DIR_IN, ep = 4, transferBufferLength = 64), backend, emptySet(), 10)
        assertNull(reply)
        assertNull(backend.lastBulkEndpoint) // never touched the dead device
    }

    @Test
    fun `pollIn drops an already-unlinked URB without polling`() {
        val backend = FakeBackend(inData = byteArrayOf(1))
        val reply = UsbIpServer.pollIn(submit(6, UsbIpProtocol.DIR_IN, ep = 4, transferBufferLength = 64), backend, setOf(6), 10)
        assertNull(reply)
        assertNull(backend.lastBulkEndpoint) // never touched the device
    }

    @Test
    fun `filterToFidoInterface keeps only interface 0 and drops CCID`() {
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        val iface0 = bytes(9, 0x04, 0, 0, 2, 3, 0, 0, 0) +   // interface 0, class 3 (HID)
            bytes(7, 0x05, 0x04, 0x03, 64, 0, 1) + bytes(7, 0x05, 0x84, 0x03, 64, 0, 1)
        val iface1 = bytes(9, 0x04, 1, 0, 2, 11, 0, 0, 0) +  // interface 1, class 11 (CCID)
            bytes(7, 0x05, 0x02, 0x02, 64, 0, 0) + bytes(7, 0x05, 0x82, 0x02, 64, 0, 0)
        val header = bytes(9, 0x02, 0, 0, 2, 1, 0, 0x80, 50)
        val full = header + iface0 + iface1
        full[2] = (full.size and 0xFF).toByte()
        full[3] = ((full.size shr 8) and 0xFF).toByte()

        val filtered = UsbIpServer.filterToFidoInterface(full)
        assertEquals(1, filtered[4].toInt())                 // bNumInterfaces
        assertEquals(9 + iface0.size, filtered.size)         // header + interface-0 block only
        val wTotal = (filtered[2].toInt() and 0xFF) or ((filtered[3].toInt() and 0xFF) shl 8)
        assertEquals(filtered.size, wTotal)                  // corrected wTotalLength
    }

    @Test
    fun `filterToFidoInterface passes a CDC serial config through unchanged`() {
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        // ESP32-S3 shape: interface 0 = CDC control (class 2), interface 1 = CDC data
        // (class 10) with the bulk endpoints. Stripping #1 would kill cdc_acm binding.
        val iface0 = bytes(9, 0x04, 0, 0, 1, 2, 2, 0, 0) +    // interface 0, class 2 (CDC control)
            bytes(7, 0x05, 0x83, 0x03, 64, 0, 1)             // interrupt IN
        val iface1 = bytes(9, 0x04, 1, 0, 2, 10, 0, 0, 0) +  // interface 1, class 10 (CDC data)
            bytes(7, 0x05, 0x02, 0x02, 64, 0, 0) + bytes(7, 0x05, 0x82, 0x02, 64, 0, 0)
        val header = bytes(9, 0x02, 0, 0, 2, 1, 0, 0x80, 50)
        val full = header + iface0 + iface1
        full[2] = (full.size and 0xFF).toByte()
        full[3] = ((full.size shr 8) and 0xFF).toByte()

        val filtered = UsbIpServer.filterToFidoInterface(full)
        assertArrayEquals(full, filtered)            // untouched: both interfaces kept
        assertEquals(2, filtered[4].toInt())         // bNumInterfaces still 2
        assertEquals(false, UsbIpServer.interface0IsHid(full))
    }

    @Test
    fun `transfer failure becomes negative-errno reply`() {
        val reply = UsbIpServer.bridgeSubmit(submit(4, UsbIpProtocol.DIR_IN, ep = 4, transferBufferLength = 64), FakeBackend(fail = true), 1000)
        val (status, actualLength) = replyStatusAndLength(reply)
        assertEquals(-32, status) // -EPIPE
        assertEquals(0, actualLength)
        assertEquals(48, reply.size)
    }

    // #506: a SuperSpeed flash drive imported as high-speed fails guest
    // enumeration ("Invalid ep0 maxpacket: 9"). Speed detection must report SUPER.
    private fun configWithBulk(mps: Int): ByteArray {
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        val iface = bytes(9, 0x04, 0, 0, 2, 8, 6, 50, 0) +            // mass-storage interface
            bytes(7, 0x05, 0x81, 0x02, mps and 0xFF, (mps shr 8) and 0xFF, 0) +
            bytes(7, 0x05, 0x02, 0x02, mps and 0xFF, (mps shr 8) and 0xFF, 0)
        val header = bytes(9, 0x02, 0, 0, 1, 1, 0, 0x80, 50)
        header[2] = ((header.size + iface.size) and 0xFF).toByte()
        header[3] = (((header.size + iface.size) shr 8) and 0xFF).toByte()
        return header + iface
    }

    @Test
    fun `detectSpeed full-speed bulk 64 reports FULL`() {
        assertEquals(2, UsbIpServer.detectSpeed(configWithBulk(64), ep0MaxPacket = 64))
    }

    @Test
    fun `detectSpeed high-speed bulk 512 reports HIGH`() {
        assertEquals(3, UsbIpServer.detectSpeed(configWithBulk(512), ep0MaxPacket = 64))
    }

    @Test
    fun `detectSpeed super-speed bulk 1024 reports SUPER`() {
        assertEquals(4, UsbIpServer.detectSpeed(configWithBulk(1024), ep0MaxPacket = 9))
    }

    @Test
    fun `detectSpeed ep0 maxpacket 9 promotes to SUPER even at bulk 512`() {
        // SS device whose config only shows 512-byte endpoints; the encoded
        // ep0 size 9 (512 @ SuperSpeed) is the decisive signal.
        assertEquals(4, UsbIpServer.detectSpeed(configWithBulk(512), ep0MaxPacket = 9))
    }
}
