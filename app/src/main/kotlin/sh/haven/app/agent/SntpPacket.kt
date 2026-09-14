package sh.haven.app.agent

/**
 * NTPv4 packet codec for [SntpServer] (RFC 5905 header, RFC 4330-sized
 * request/response — the subset a client `server` poll uses). Pure object:
 * JVM tests drive it without a socket.
 *
 * Timestamps are the NTP 64-bit fixed-point format (16 bits of seconds
 * since 1900, 32 bits of fraction-of-second); unixMs↔NTP conversion lives
 * here so the wire math is testable without Android.
 */
internal object SntpPacket {

    const val SIZE = 48

    /** Seconds between the NTP epoch (1900-01-01) and the Unix epoch. */
    const val NTP_UNIX_OFFSET_S: Long = 2_208_988_800L

    /** Leap-indicator values. */
    const val LI_NONE = 0
    const val LI_UNSYNCHRONISED = 3

    /** Stratum the GPS-disciplined server answers with (refclock = GNSS). */
    const val STRATUM_GPS = 1

    /** Reference id when disciplined: ASCII "GPS". */
    val REFID_GPS = byteArrayOf(0x47, 0x46, 0x50, 0x53) // "GPS"

    /** A parsed client request: just what the server must echo/answer. */
    data class ClientRequest(
        val leap: Int,
        val version: Int,
        val mode: Int,
        /** Client transmit timestamp, raw 8 bytes (echoed as originate). */
        val transmit: ByteArray,
    )

    /** A decoded NTP timestamp: seconds + 1/2³² fraction. */
    data class NtpTimestamp(val seconds: Long, val fraction: Int)

    /** Unix ms (+ optional sub-ms, 0..999.999) → NTP timestamp. The sub-ms
     * part fills the 2³² fraction so a disciplined answer keeps its µs
     * resolution on the wire instead of quantising to whole ms. */
    fun ntpFromUnixMs(unixMs: Long, subMs: Double = 0.0): NtpTimestamp {
        val secs = unixMs / 1000 + NTP_UNIX_OFFSET_S
        // The fraction spans the full unsigned 32-bit range; .toInt() after a
        // Double clamps at 2^31 (sub-ms ≥ 500 all encoded as exactly 0.5 s),
        // so reinterpret through Long for the top half.
        val frac = (((unixMs % 1000) + subMs) / 1000.0 * (1L shl 32).toDouble())
            .coerceIn(0.0, (1L shl 32) - 1.0).toLong().toInt()
        return NtpTimestamp(secs, frac)
    }

    /** Parse the 8-byte NTP timestamp at [off]. */
    fun readTimestamp(buf: ByteArray, off: Int): NtpTimestamp {
        var secs = 0L
        for (i in 0 until 4) secs = (secs shl 8) or (buf[off + i].toLong() and 0xFF)
        var frac = 0
        for (i in 0 until 4) frac = (frac shl 8) or (buf[off + 4 + i].toInt() and 0xFF)
        return NtpTimestamp(secs, frac)
    }

    /** Parse a client request packet (first [SIZE] bytes). Returns null when
     * too short or not a client-mode request (mode 3) — the server drops those. */
    fun parseClient(buf: ByteArray): ClientRequest? {
        if (buf.size < SIZE) return null
        val mode = (buf[0].toInt() shr 0) and 0x07
        val version = (buf[0].toInt() shr 3) and 0x07
        val leap = (buf[0].toInt() shr 6) and 0x03
        // SNTP client requests are mode 3; a mode-4/5 packet is another
        // server talking, not a client — ignore.
        if (mode != 3) return null
        val transmit = buf.copyOfRange(SIZE - 8, SIZE)
        return ClientRequest(leap, version, mode, transmit)
    }

    /**
     * Encode a server response into [out] (must be ≥ [SIZE]).
     *
     * `poll` echoes the client's (log2 s); `precision` is log2 seconds of
     * the clock's resolution; `rootDelayMs`/`rootDispersionMs` go into the
     * NTP 16.16 fixed-point fields in *seconds*; `referenceTs` is the time
     * of the last GPS sample (0 = unknown); `originateTs` echoes the
     * client's transmit; `receiveTs`/`transmitTs` bracket our handling.
     */
    fun encodeServer(
        out: ByteArray,
        leap: Int,
        version: Int,
        stratum: Int,
        poll: Int,
        precision: Int,
        rootDelayS: Double,
        rootDispersionS: Double,
        refId: ByteArray,
        referenceTs: NtpTimestamp,
        originateTs: ByteArray,
        receiveTs: NtpTimestamp,
        transmitTs: NtpTimestamp,
    ) {
        out[0] = ((leap and 0x03) shl 6 or ((version and 0x07) shl 3) or (4 and 0x07)).toByte()
        out[1] = stratum.toByte()
        out[2] = poll.toByte()
        out[3] = precision.toByte()
        writeFixed32(out, 4, rootDelayS)
        writeFixed32(out, 8, rootDispersionS)
        refId.copyInto(out, 12, 0, 4)
        writeTimestamp(out, 16, referenceTs)
        // originate = the client's transmit, verbatim
        originateTs.copyInto(out, 24, 0, 8)
        writeTimestamp(out, 32, receiveTs)
        writeTimestamp(out, 40, transmitTs)
    }

    /** 16.16 fixed-point seconds (the NTP rootDelay/rootDispersion encoding). */
    internal fun writeFixed32(out: ByteArray, off: Int, seconds: Double) {
        val v = (seconds * 65536.0).toLong().coerceIn(0, 0xFFFFL shl 16 or 0xFFFFL).toInt()
        out[off] = (v shr 24).toByte()
        out[off + 1] = (v shr 16 and 0xFF).toByte()
        out[off + 2] = (v shr 8 and 0xFF).toByte()
        out[off + 3] = (v and 0xFF).toByte()
    }

    internal fun writeTimestamp(out: ByteArray, off: Int, ts: NtpTimestamp) {
        var s = ts.seconds
        for (i in 3 downTo 0) {
            out[off + i] = (s and 0xFF).toByte()
            s = s shr 8
        }
        val f = ts.fraction
        for (i in 3 downTo 0) {
            out[off + 4 + i] = (f shr (8 * (3 - i)) and 0xFF).toByte()
        }
    }
}