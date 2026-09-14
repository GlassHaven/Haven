package sh.haven.core.usb.massstorage

import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbDeviceInfo
import java.io.IOException

/**
 * One SCSI/BBB session over a USB mass-storage device's bulk endpoint pair.
 *
 * A BBB transaction is strictly serial: CBW → data phase → CSW, with no
 * other command's bytes interleaved on either bulk endpoint. The USB/IP
 * server deliberately runs per-endpoint lanes (the guest's SCSI layer
 * tolerates it), but this engine drives whole commands, so every command
 * runs under one lock here. The transport's own per-call lock below us only
 * guards a single native call; this lock is what makes multi-chunk data
 * phases atomic.
 *
 * Timeouts are generous (a failing card stalls mid-transfer for seconds) but
 * bounded — the NBD server turns an exception here into an error reply so
 * `ddrescue` sees an I/O error instead of a hang.
 *
 * Writes only happen through [writeBlocks]; nothing else in the class emits
 * WRITE(10), so a read-only export cannot write to the card even if a buggy
 * NBD client asks.
 */
class MassStorageDevice(
    private val transport: ScsiTransport,
    info: UsbDeviceInfo,
    /** Command timeout; a wedged card costs at most this long per request. */
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {
    private val lock = Any()
    private var tagCounter = 1

    val bulkOutAddress: Int
    val bulkInAddress: Int

    val inquiry: InquiryResult
    /** Total readable sectors (READ CAPACITY(10) last LBA + 1). */
    val sectors: Long
    val blockSize: Int

    init {
        val (out, input) = locateBulkEndpoints(info)
        bulkOutAddress = out
        bulkInAddress = input
        inquiry = run {
            val (res, data) = command(cmdInquiry())
            if (!res.passed) throw IOException("INQUIRY failed (${res.status})")
            parseInquiry(data)
        }
        val cap = run {
            val (res, data) = command(cmdReadCapacity10())
            if (!res.passed) throw IOException("READ CAPACITY failed (${res.status})")
            parseReadCapacity10(data)
        }
        blockSize = cap.blockSize
        sectors = cap.lastLba + 1
    }

    /** "vendor product" from INQUIRY plus capacity, for the UI label. */
    fun describe(): String =
        "${inquiry.vendor} ${inquiry.product}".trim() + " (${sectors * blockSize / (1024 * 1024)} MiB)"

    /** Read [blocks] sectors at [lba] into a fresh buffer; short reads throw. */
    fun readBlocks(lba: Long, blocks: Int): ByteArray = readBlocksInto(lba, blocks, ByteArray(blocks * blockSize))

    /** Read into [dst]; returns dst. [blocks]*blockSize must fit. */
    fun readBlocksInto(lba: Long, blocks: Int, dst: ByteArray): ByteArray {
        require(dst.size >= blocks * blockSize) { "buffer too small: ${dst.size} < ${blocks * blockSize}" }
        checkSectors(lba, blocks)
        // READ(10) caps at 65535 blocks; large NBD reads arrive pre-chunked, but
        // split defensively so a future caller can't desync the transport.
        var remaining = blocks
        var current = lba
        var offset = 0
        while (remaining > 0) {
            val chunk = minOf(remaining, MAX_BLOCKS_PER_CMD)
            val (res, data) = command(cmdRead10(current, chunk))
            if (!res.passed) throw ScsiIoException("READ(10) failed at LBA $current ($res): ${senseOrBlank()}")
            if (data.size < chunk * blockSize) {
                throw ScsiIoException("READ(10) short at LBA $current: ${data.size} < ${chunk * blockSize} bytes")
            }
            data.copyInto(dst, offset, 0, chunk * blockSize)
            offset += chunk * blockSize
            current += chunk
            remaining -= chunk
        }
        return dst
    }

    /**
     * Write [blocks] sectors from [src]. The only path in the class that
     * emits WRITE(10) — the NBD server calls it only when the export was
     * opened writable.
     */
    fun writeBlocks(lba: Long, blocks: Int, src: ByteArray) {
        require(src.size >= blocks * blockSize) { "write buffer too small: ${src.size} < ${blocks * blockSize}" }
        checkSectors(lba, blocks)
        var remaining = blocks
        var current = lba
        var offset = 0
        while (remaining > 0) {
            val chunk = minOf(remaining, MAX_BLOCKS_PER_CMD)
            val (res, _) = command(cmdWrite10(current, chunk), src, offset)
            if (!res.passed) throw ScsiIoException("WRITE(10) failed at LBA $current ($res): ${senseOrBlank()}")
            offset += chunk * blockSize
            current += chunk
            remaining -= chunk
        }
    }

    /** TEST_UNIT_READY with one UNIT ATTENTION retry; throws ScsiIoException otherwise. */
    fun waitReady(retries: Int = 1) {
        for (attempt in 0..retries) {
            val (res, _) = command(cmdTestUnitReady())
            if (res.passed) return
            if (attempt == retries) {
                throw ScsiIoException("device not ready: $res, ${senseOrBlank()}")
            }
            // UNIT ATTENTION after a media change clears on the next command.
            command(cmdRequestSense())
        }
    }

    private fun checkSectors(lba: Long, blocks: Int) {
        if (lba < 0 || lba + blocks > sectors) {
            throw ScsiIoException("request out of range: LBA $lba +$blocks beyond ${sectors} sectors")
        }
    }

    private fun senseOrBlank(): String = try {
        val (_, data) = command(cmdRequestSense())
        parseSense(data).toString()
    } catch (_: Exception) {
        "no sense data"
    }

    /**
     * One full BBB transaction. Out data is taken from [outData] (starting at
     * [outOffset]) when the command's data phase is OUT.
     */
    private fun command(cmd: ScsiCommand, outData: ByteArray? = null, outOffset: Int = 0): Pair<CswResult, ByteArray> {
        synchronized(lock) {
            val tag = nextTag()
            val cbw = buildCbw(cmd, tag)
            transport.bulk(bulkOutAddress, cbw, cbw.size, timeoutMs)

            var residue = cmd.dataLength
            var inData = ByteArray(0)
            when (cmd.dataDir) {
                DataDir.IN -> {
                    val transferred = transport.bulk(bulkInAddress, null, cmd.dataLength, timeoutMs)
                    inData = transferred.data
                    residue = cmd.dataLength - transferred.bytesTransferred
                    // On a short read the device still owes us the CSW; a
                    // residue != 0 is legal and handed to parseCsw below.
                }
                DataDir.OUT -> {
                    if (outData == null) throw ScsiPhaseException("OUT command without data")
                    transport.bulk(bulkOutAddress, outData.copyOfRange(outOffset, outData.size), 0, timeoutMs)
                    residue = 0
                }
                DataDir.NONE -> {}
            }

            val cswBytes = transport.bulk(bulkInAddress, null, Csw.LENGTH, timeoutMs)
            val res = parseCsw(cswBytes.data, tag, residue)
            return res to inData
        }
    }

    private fun nextTag(): Int {
        synchronized(lock) {
            val t = tagCounter
            tagCounter = if (tagCounter == Int.MAX_VALUE) 1 else tagCounter + 1
            return t
        }
    }

    private fun locateBulkEndpoints(info: UsbDeviceInfo): Pair<Int, Int> {
        val iface = info.interfaces.firstOrNull { it.interfaceClass == 0x08 }
            ?: throw IOException("${info.deviceName} is not a USB mass-storage device (no class-8 interface)")
        var out = -1
        var input = -1
        for (ep in iface.endpoints) {
            if (ep.type != "bulk") continue
            if (ep.direction == "out") out = ep.address
            if (ep.direction == "in") input = ep.address
        }
        if (out == -1 || input == -1) {
            throw IOException("${info.deviceName}: class-8 interface ${iface.id} lacks bulk endpoints (out=$out in=$input)")
        }
        return out to input
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000
        /** READ(10)/WRITE(10) hard cap: 16-bit block count. */
        private const val MAX_BLOCKS_PER_CMD = 0xFFFF

        /** Open via the broker and construct, in one step. */
        suspend fun open(deviceName: String, broker: UsbBroker): MassStorageDevice {
            val info = broker.openDevice(deviceName)
            return MassStorageDevice(BrokerScsiTransport(deviceName, broker), info)
        }
    }
}

/** SCSI-level I/O failure after the transport stayed in sync (sense available). */
class ScsiIoException(message: String) : IOException(message)