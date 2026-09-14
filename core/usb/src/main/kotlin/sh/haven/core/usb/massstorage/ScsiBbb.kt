package sh.haven.core.usb.massstorage

/**
 * SCSI command blocks and Bulk-Only Transport (BBT/BBB) framing for a USB
 * mass-storage device, as spoken to a phone-attached reader through
 * [sh.haven.core.usb.UsbBroker.bulkTransfer].
 *
 * Pure byte layout — no Android or I/O dependencies, so every constant and
 * builder is unit-testable. The framing follows the USB Mass Storage Class
 * Bulk-Only Transport spec (rev 1.0): a 31-byte Command Block Wrapper, an
 * optional data phase, then a 13-byte Command Status Wrapper.
 */

/** One direction for a BBB data phase. */
enum class DataDir { IN, OUT, NONE }

/** A SCSI command block ready to be wrapped in a CBW. */
data class ScsiCommand(
    /** CDB bytes (6/10/12/16 bytes per opcode). */
    val cdb: ByteArray,
    val dataDir: DataDir,
    /** dCBWDataTransferLength — bytes the host expects to move in the data phase. */
    val dataLength: Int,
)

object ScsiOps {
    const val TEST_UNIT_READY = 0x00
    const val REQUEST_SENSE = 0x03
    const val INQUIRY = 0x12
    const val READ_10 = 0x28
    const val WRITE_10 = 0x2A
    const val READ_CAPACITY_10 = 0x25
}

object Cbw {
    const val SIGNATURE = 0x43425355 // 'USBC'
    const val LENGTH = 31
}

object Csw {
    const val SIGNATURE = 0x53425355 // 'USBS'
    const val LENGTH = 13

    /** bCSWStatus values. */
    const val STATUS_PASSED = 0
    const val STATUS_FAILED = 1
    const val STATUS_PHASE_ERROR = 2
}

/** Build the 31-byte CBW for [cmd]. [tag] must differ per command (CSW echoes it). */
fun buildCbw(cmd: ScsiCommand, tag: Int): ByteArray {
    require(cmd.cdb.size in 1..16) { "CDB size ${cmd.cdb.size} out of range" }
    val cbw = ByteArray(Cbw.LENGTH)
    writeU32Le(cbw, 0, Cbw.SIGNATURE)
    writeU32Le(cbw, 4, tag)
    writeU32Le(cbw, 8, cmd.dataLength)
    cbw[12] = if (cmd.dataDir == DataDir.IN) 0x80.toByte() else 0x00
    cbw[13] = 0 // bCBWLUN 0 — one LUN per broker handle for now
    cbw[14] = cmd.cdb.size.toByte()
    cmd.cdb.copyInto(cbw, 15)
    return cbw
}

/** Parse a 13-byte CSW. Throws [ScsiPhaseException] on a signature/phase mismatch. */
fun parseCsw(csw: ByteArray, expectedTag: Int, dataResidue: Int): CswResult {
    if (csw.size < Csw.LENGTH) throw ScsiPhaseException("CSW truncated: ${csw.size} bytes")
    val sig = readU32Le(csw, 0)
    if (sig != Csw.SIGNATURE.toLong()) throw ScsiPhaseException("bad CSW signature 0x${"%08x".format(sig)}")
    val tag = readU32Le(csw, 4)
    if (tag != expectedTag.toLong()) throw ScsiPhaseException("CSW tag mismatch: got $tag, expected $expectedTag")
    val residue = readU32Le(csw, 8)
    val sent = dataResidue.toLong() and 0xFFFFFFFFL
    if (residue != sent) {
        // The device may legitimately report leftover data phase bytes on a
        // short read; only a residue larger than we sent is a protocol error.
        if (residue > sent) throw ScsiPhaseException("CSW residue $residue > sent $sent")
    }
    val status = csw[12].toInt() and 0xFF
    if (status == Csw.STATUS_PHASE_ERROR) throw ScsiPhaseException("CSW phase error")
    return CswResult(
        status = status,
        residue = residue,
    )
}

/** Outcome of a BBB transaction's status phase. */
data class CswResult(val status: Int, val residue: Long) {
    val passed: Boolean get() = status == Csw.STATUS_PASSED
}

class ScsiPhaseException(message: String) : Exception(message)

// ---- Command builders ---------------------------------------------------

fun cmdTestUnitReady(): ScsiCommand =
    ScsiCommand(cdb = byteArrayOf(ScsiOps.TEST_UNIT_READY.toByte(), 0, 0, 0, 0, 0), DataDir.NONE, 0)

/** 36-byte standard INQUIRY (EVPD=0). */
fun cmdInquiry(): ScsiCommand =
    ScsiCommand(
        cdb = byteArrayOf(ScsiOps.INQUIRY.toByte(), 0, 0, 0, 36, 0),
        DataDir.IN, 36,
    )

/** 18-byte sense buffer. */
fun cmdRequestSense(): ScsiCommand =
    ScsiCommand(
        cdb = byteArrayOf(ScsiOps.REQUEST_SENSE.toByte(), 0, 0, 0, 18, 0),
        DataDir.IN, 18,
    )

/** 8-byte READ CAPACITY(10) — last LBA + block size; 0xFFFFFFFF means >2 TiB. */
fun cmdReadCapacity10(): ScsiCommand =
    ScsiCommand(
        cdb = byteArrayOf(ScsiOps.READ_CAPACITY_10.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0),
        DataDir.IN, 8,
    )

/** READ(10): up to 65535 blocks (128 KiB at 512 B/sector) per command. */
fun cmdRead10(lba: Long, blocks: Int): ScsiCommand {
    require(lba in 0..0xFFFFFFFFL) { "READ(10) LBA out of range: $lba" }
    require(blocks in 1..0xFFFF) { "READ(10) block count out of range: $blocks" }
    return ScsiCommand(
        cdb = byteArrayOf(
            ScsiOps.READ_10.toByte(), 0,
            ((lba shr 24) and 0xFF).toByte(), ((lba shr 16) and 0xFF).toByte(),
            ((lba shr 8) and 0xFF).toByte(), (lba and 0xFF).toByte(),
            0,
            ((blocks shr 8) and 0xFF).toByte(), (blocks and 0xFF).toByte(),
            0,
        ),
        DataDir.IN, blocks * SECTOR_SIZE,
    )
}

/** WRITE(10). Callers must not build this unless a write is actually requested. */
fun cmdWrite10(lba: Long, blocks: Int): ScsiCommand {
    require(lba in 0..0xFFFFFFFFL) { "WRITE(10) LBA out of range: $lba" }
    require(blocks in 1..0xFFFF) { "WRITE(10) block count out of range: $blocks" }
    return ScsiCommand(
        cdb = byteArrayOf(
            ScsiOps.WRITE_10.toByte(), 0,
            ((lba shr 24) and 0xFF).toByte(), ((lba shr 16) and 0xFF).toByte(),
            ((lba shr 8) and 0xFF).toByte(), (lba and 0xFF).toByte(),
            0,
            ((blocks shr 8) and 0xFF).toByte(), (blocks and 0xFF).toByte(),
            0,
        ),
        DataDir.OUT, blocks * SECTOR_SIZE,
    )
}

/** Bytes per logical block the NBD/SCSI path assumes; readers report their own. */
const val SECTOR_SIZE = 512

// ---- Parsers --------------------------------------------------------------

data class InquiryResult(val vendor: String, val product: String, val revision: String, val removable: Boolean)

fun parseInquiry(data: ByteArray): InquiryResult {
    require(data.size >= 36) { "INQUIRY data too short: ${data.size}" }
    val removable = (data[1].toInt() and 0x80) != 0
    // Fields are space-padded, but NUL padding also appears in the wild.
    fun str(from: Int, len: Int) = String(data, from, len, Charsets.US_ASCII).trim { it <= ' ' }
    return InquiryResult(
        vendor = str(8, 8),
        product = str(16, 16),
        revision = str(32, 4),
        removable = removable,
    )
}

data class ReadCapacityResult(val lastLba: Long, val blockSize: Int)

fun parseReadCapacity10(data: ByteArray): ReadCapacityResult {
    require(data.size >= 8) { "READ CAPACITY data too short: ${data.size}" }
    // SCSI response fields are big-endian (only the USB CBW/CSW wrappers are LE).
    val lba = readU32Be(data, 0)
    val block = readU32Be(data, 4)
    if (block == 0L) throw ScsiPhaseException("READ CAPACITY reported block size 0")
    if (block > Int.MAX_VALUE.toLong()) throw ScsiPhaseException("READ CAPACITY block size $block unsupported")
    return ReadCapacityResult(lba, block.toInt())
}

/** REQUEST_SENSE response: sense key / ASC / ASCQ, the triage an operator sees. */
data class SenseResult(val senseKey: Int, val asc: Int, val ascq: Int) {
    val mediumError: Boolean get() = senseKey == 0x03
    val unitAttention: Boolean get() = senseKey == 0x06
    val illegalRequest: Boolean get() = senseKey == 0x05

    override fun toString(): String = "sense key 0x${"%02x".format(senseKey)} ASC/ASCQ ${"%02x".format(asc)}/${"%02x".format(ascq)}"
}

fun parseSense(data: ByteArray): SenseResult {
    require(data.size >= 18) { "sense data too short: ${data.size}" }
    val valid = (data[0].toInt() and 0x80) != 0
    if (!valid && (data[0].toInt() and 0x70) == 0) {
        throw ScsiPhaseException("sense data has no valid error code (byte0=0x${"%02x".format(data[0])})")
    }
    return SenseResult(
        senseKey = data[2].toInt() and 0x0F,
        asc = data[12].toInt() and 0xFF,
        ascq = data[13].toInt() and 0xFF,
    )
}

// ---- little-endian helpers ----------------------------------------------

fun writeU32Le(b: ByteArray, off: Int, v: Int) = writeU32Le(b, off, v.toLong())

fun writeU32Le(b: ByteArray, off: Int, v: Long) {
    b[off] = (v and 0xFF).toByte()
    b[off + 1] = ((v shr 8) and 0xFF).toByte()
    b[off + 2] = ((v shr 16) and 0xFF).toByte()
    b[off + 3] = ((v shr 24) and 0xFF).toByte()
}

fun readU32Le(b: ByteArray, off: Int): Long =
    (b[off].toLong() and 0xFF) or
        ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or
        ((b[off + 3].toLong() and 0xFF) shl 24)

fun writeU32Be(b: ByteArray, off: Int, v: Long) {
    b[off] = ((v shr 24) and 0xFF).toByte()
    b[off + 1] = ((v shr 16) and 0xFF).toByte()
    b[off + 2] = ((v shr 8) and 0xFF).toByte()
    b[off + 3] = (v and 0xFF).toByte()
}

fun readU32Be(b: ByteArray, off: Int): Long =
    ((b[off].toLong() and 0xFF) shl 24) or
        ((b[off + 1].toLong() and 0xFF) shl 16) or
        ((b[off + 2].toLong() and 0xFF) shl 8) or
        (b[off + 3].toLong() and 0xFF)

fun writeU64Le(b: ByteArray, off: Int, v: Long) {
    writeU32Le(b, off, v and 0xFFFFFFFFL)
    writeU32Le(b, off + 4, (v ushr 32) and 0xFFFFFFFFL)
}

fun readU64Le(b: ByteArray, off: Int): Long = readU32Le(b, off) or (readU32Le(b, off + 4) shl 32)