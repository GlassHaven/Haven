package sh.haven.core.usb.massstorage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.usb.TransferResult

/** CBW/CSW byte layout and SCSI builders/parsers — the wire contract. */
class ScsiBbbTest {

    @Test
    fun `CBW layout matches the BBB spec`() {
        val cbw = buildCbw(cmdRead10(0x12345678L, 0x0100), tag = 0xDEADBEEF.toInt())
        assertEquals(31, cbw.size)
        assertEquals(0x43425355L, readU32Le(cbw, 0))
        assertEquals(0xDEADBEEFL, readU32Le(cbw, 4))
        assertEquals(0x0100L * 512, readU32Le(cbw, 8)) // dCBWDataTransferLength
        assertEquals(0x80, cbw[12].toInt() and 0xFF) // direction IN
        assertEquals(0, cbw[13].toInt()) // LUN 0
        assertEquals(10, cbw[14].toInt()) // CDB length
        assertEquals(0x28, cbw[15].toInt()) // READ_10
        // big-endian LBA in CDB bytes 2..5
        assertEquals(0x12, cbw[17].toInt() and 0xFF)
        assertEquals(0x34, cbw[18].toInt() and 0xFF)
        assertEquals(0x56, cbw[19].toInt() and 0xFF)
        assertEquals(0x78, cbw[20].toInt() and 0xFF)
        assertEquals(0x01, cbw[22].toInt() and 0xFF) // block count high
        assertEquals(0x00, cbw[23].toInt()) // block count low
    }

    @Test
    fun `OUT CBW sets direction bit 0`() {
        val cbw = buildCbw(cmdWrite10(0, 2), tag = 7)
        assertEquals(0, cbw[12].toInt() and 0x80)
        assertEquals(2L * 512, readU32Le(cbw, 8))
        assertEquals(0x2A, cbw[15].toInt()) // WRITE_10
    }

    @Test
    fun `CSW parse passes on good status`() {
        val res = parseCsw(goodCsw(tag = 42, status = 0), expectedTag = 42, dataResidue = 0)
        assertTrue(res.passed)
        assertEquals(0, res.residue)
    }

    @Test
    fun `CSW parse rejects bad signature`() {
        val csw = goodCsw(tag = 1, status = 0)
        writeU32Le(csw, 0, 0x12345678)
        try {
            parseCsw(csw, expectedTag = 1, dataResidue = 0)
            fail("expected ScsiPhaseException")
        } catch (e: ScsiPhaseException) {
            assertTrue(e.message!!.contains("signature"))
        }
    }

    @Test
    fun `CSW parse rejects tag mismatch`() {
        try {
            parseCsw(goodCsw(tag = 99, status = 0), expectedTag = 1, dataResidue = 0)
            fail("expected ScsiPhaseException")
        } catch (e: ScsiPhaseException) {
            assertTrue(e.message!!.contains("tag"))
        }
    }

    @Test
    fun `CSW parse rejects phase error status`() {
        try {
            parseCsw(goodCsw(tag = 1, status = Csw.STATUS_PHASE_ERROR), expectedTag = 1, dataResidue = 0)
            fail("expected ScsiPhaseException")
        } catch (e: ScsiPhaseException) {
            assertTrue(e.message!!.contains("phase"))
        }
    }

    @Test
    fun `READ CAPACITY parser`() {
        val data = ByteArray(8)
        writeU32Be(data, 0, 0x1D3FFF) // last LBA of a 1.5 GiB card
        writeU32Be(data, 4, 512)
        val cap = parseReadCapacity10(data)
        assertEquals(0x1D3FFFL, cap.lastLba)
        assertEquals(512, cap.blockSize)
        // sectors = lastLba + 1, exactly what the NBD export size must be
        assertEquals(0x1D4000L * 512, (cap.lastLba + 1) * cap.blockSize)
    }

    @Test
    fun `INQUIRY parser extracts vendor product and removable bit`() {
        val data = ByteArray(36)
        "KIOXIA  ".toByteArray(Charsets.US_ASCII).copyInto(data, 8)
        "TRANSMEMORY    ".toByteArray(Charsets.US_ASCII).copyInto(data, 16)
        "PMAP".toByteArray(Charsets.US_ASCII).copyInto(data, 32)
        data[1] = 0x80.toByte()
        val q = parseInquiry(data)
        assertEquals("KIOXIA", q.vendor)
        assertEquals("TRANSMEMORY", q.product)
        assertEquals("PMAP", q.revision)
        assertTrue(q.removable)
    }

    @Test
    fun `sense parser maps MEDIUM ERROR and UNIT ATTENTION`() {
        val sense = goodSense(key = 0x03, asc = 0x11, ascq = 0x00) // MEDIUM ERROR, UNRECOVERED READ
        val s = parseSense(sense)
        assertTrue(s.mediumError)
        assertFalse(s.unitAttention)
        assertEquals(0x11, s.asc)

        val s2 = parseSense(goodSense(key = 0x06, asc = 0x28, ascq = 0x00)) // UNIT ATTENTION, media change
        assertTrue(s2.unitAttention)
        assertFalse(s2.mediumError)
    }

    @Test
    fun `read 10 rejects out of range arguments`() {
        try {
            cmdRead10(0x1_0000_0000L, 1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            cmdRead10(0, 0x10000)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun goodCsw(tag: Int, status: Int): ByteArray {
        val csw = ByteArray(13)
        writeU32Le(csw, 0, Csw.SIGNATURE)
        writeU32Le(csw, 4, tag)
        writeU32Le(csw, 8, 0)
        csw[12] = status.toByte()
        return csw
    }

    private fun goodSense(key: Int, asc: Int, ascq: Int): ByteArray {
        val sense = ByteArray(18)
        sense[0] = 0x70.toByte()
        sense[2] = key.toByte()
        sense[12] = asc.toByte()
        sense[13] = ascq.toByte()
        return sense
    }
}

/**
 * A scripted BBB device over a 1 MiB backing store: parses CBWs off the OUT
 * stream, serves reads from the backing bytes, records WRITEs, and returns
 * canned CSWs (status injectable for failure tests).
 */
private class FakeScsiTransport : ScsiTransport {
    val backing = ByteArray(1024 * 1024)
    var totalSectors = backing.size / SECTOR_SIZE
    val writes = mutableListOf<Pair<Long, Int>>() // lba to blocks
    val cbws = mutableListOf<ByteArray>()
    var nextCswStatus = 0

    override fun bulk(endpointAddress: Int, data: ByteArray?, length: Int, timeoutMs: Int): TransferResult {
        return if (endpointAddress == OUT) {
            data ?: throw AssertionError("OUT without data")
            if (data.size == 31) {
                cbws.add(data.copyOf())
            } else {
                // WRITE(10) data phase: apply to the backing store at the last WRITE CBW's LBA.
                val cbw = cbws.last()
                require((cbw[15].toInt() and 0xFF) == ScsiOps.WRITE_10) { "unexpected data phase" }
                val lba = lbaOf(cbw)
                val blocks = ((cbw[22].toInt() and 0xFF) shl 8) or (cbw[23].toInt() and 0xFF)
                writes.add(lba to blocks)
                data.copyInto(backing, (lba * SECTOR_SIZE).toInt())
            }
            TransferResult(data.size, ByteArray(0))
        } else {
            // IN endpoint. Length 13 = CSW; length 18 after REQUEST_SENSE = sense;
            // anything else = READ data phase.
            if (length == Csw.LENGTH) {
                val csw = ByteArray(13)
                writeU32Le(csw, 0, Csw.SIGNATURE)
                writeU32Le(csw, 4, tagOf(cbws.last()))
                writeU32Le(csw, 8, 0)
                csw[12] = nextCswStatus.toByte()
                return TransferResult(13, csw)
            }
            val cbw = cbws.last()
            when (cbw[15].toInt() and 0xFF) {
                ScsiOps.REQUEST_SENSE -> {
                    val sense = ByteArray(18)
                    sense[0] = 0x70.toByte()
                    sense[2] = 0x03
                    sense[12] = 0x11
                    return TransferResult(18, sense)
                }
                ScsiOps.READ_10 -> {
                    val off = (lbaOf(cbw) * SECTOR_SIZE).toInt()
                    return TransferResult(length, backing.copyOfRange(off, off + length))
                }
                ScsiOps.INQUIRY -> {
                    val q = ByteArray(36)
                    "FAKE    ".toByteArray(Charsets.US_ASCII).copyInto(q, 8)
                    "CARD            ".toByteArray(Charsets.US_ASCII).copyInto(q, 16)
                    q[1] = 0x80.toByte()
                    return TransferResult(36, q)
                }
                ScsiOps.READ_CAPACITY_10 -> {
                    val cap = ByteArray(8)
                    writeU32Be(cap, 0, totalSectors - 1L)
                    writeU32Be(cap, 4, 512)
                    return TransferResult(8, cap)
                }
                ScsiOps.TEST_UNIT_READY -> TransferResult(0, ByteArray(0))
                else -> throw AssertionError("unexpected IN for opcode ${"%02x".format(cbw[15].toInt())}")
            }
        }
    }

    override fun isAlive() = true

    private fun tagOf(cbw: ByteArray) = readU32Le(cbw, 4).toInt()
    private fun lbaOf(cbw: ByteArray): Long =
        ((cbw[17].toLong() and 0xFF) shl 24) or ((cbw[18].toLong() and 0xFF) shl 16) or
            ((cbw[19].toLong() and 0xFF) shl 8) or (cbw[20].toLong() and 0xFF)

    companion object {
        const val OUT = 0x01
        const val IN = 0x81
    }
}

/** Round-trip through [MassStorageDevice] against the scripted fake transport. */
class MassStorageDeviceTest {

    private fun fakeWithDevice(): Pair<FakeScsiTransport, MassStorageDevice> {
        val fake = FakeScsiTransport()
        val dev = MassStorageDevice(fake, massStorageInfo(FakeScsiTransport.OUT, FakeScsiTransport.IN))
        return fake to dev
    }

    @Test
    fun `init discovers capacity from READ CAPACITY`() {
        val (_, dev) = fakeWithDevice()
        assertEquals(1024L * 1024 / 512, dev.sectors)
        assertEquals(512, dev.blockSize)
        assertEquals("FAKE CARD", "${dev.inquiry.vendor} ${dev.inquiry.product}".trim())
    }

    @Test
    fun `read returns backing bytes at the right offset`() {
        val (fake, dev) = fakeWithDevice()
        val pattern = ByteArray(1536) { (it % 251).toByte() }
        pattern.copyInto(fake.backing, 512 * 3)
        val read = dev.readBlocks(3, 3)
        assertArrayEquals(pattern, read)
    }

    @Test
    fun `write goes through WRITE_10 and lands in the backing store`() {
        val (fake, dev) = fakeWithDevice()
        val payload = ByteArray(1024) { (it xor 0x5A).toByte() }
        dev.writeBlocks(10, 2, payload)
        assertEquals(listOf(10L to 2), fake.writes)
        assertArrayEquals(payload, fake.backing.copyOfRange(512 * 10, 512 * 10 + 1024))
    }

    @Test
    fun `read beyond capacity raises`() {
        val (_, dev) = fakeWithDevice()
        try {
            dev.readBlocks(dev.sectors, 1)
            fail("expected ScsiIoException")
        } catch (e: ScsiIoException) {
            assertTrue(e.message!!.contains("out of range"))
        }
    }

    @Test
    fun `failed CSW raises and sense is fetched`() {
        val (fake, dev) = fakeWithDevice()
        fake.nextCswStatus = Csw.STATUS_FAILED
        try {
            dev.readBlocks(0, 1)
            fail("expected ScsiIoException")
        } catch (e: ScsiIoException) {
            // the sense follow-up ran; its MEDIUM ERROR key is in the message
            assertTrue(e.message!!.contains("0x03"))
        }
    }

    @Test
    fun `non mass-storage info raises at construction`() {
        val fake = FakeScsiTransport()
        try {
            MassStorageDevice(fake, hidOnlyInfo())
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("not a USB mass-storage"))
        }
    }

    private fun massStorageInfo(out: Int, input: Int): sh.haven.core.usb.UsbDeviceInfo {
        val eps = listOf(
            sh.haven.core.usb.UsbEndpointInfo(out, "out", "bulk", 512),
            sh.haven.core.usb.UsbEndpointInfo(input, "in", "bulk", 512),
        )
        val iface = sh.haven.core.usb.UsbInterfaceInfo(0, 0x08, 6, 0x50, eps)
        return sh.haven.core.usb.UsbDeviceInfo(
            deviceName = "/dev/bus/usb/001/002", vendorId = 0x1234, productId = 0x5678,
            deviceClass = 0, manufacturerName = null, productName = null, serialNumber = null,
            hasPermission = true, isOpen = true, interfaces = listOf(iface),
        )
    }

    private fun hidOnlyInfo(): sh.haven.core.usb.UsbDeviceInfo {
        val iface = sh.haven.core.usb.UsbInterfaceInfo(0, 0x03, 0, 0, emptyList())
        return sh.haven.core.usb.UsbDeviceInfo(
            deviceName = "/dev/bus/usb/001/003", vendorId = 0x1234, productId = 0x5678,
            deviceClass = 0, manufacturerName = null, productName = null, serialNumber = null,
            hasPermission = true, isOpen = true, interfaces = listOf(iface),
        )
    }
}