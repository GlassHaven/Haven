package sh.haven.feature.terminal

import android.util.Log
import java.util.Base64

private const val TAG = "OscHandler"

/**
 * Scans terminal output for OSC sequences and strips handled ones from the
 * byte stream so the terminal emulator doesn't render garbage.
 *
 * Handled OSC types:
 *   52 — clipboard set (remote → Android)
 *    7 — current working directory
 *    8 — hyperlinks
 *    9 — notifications (iTerm2-style)
 *  777 — notifications (rxvt-unicode-style)
 * 10/11 — foreground/background color queries ("?" → onColorQuery, #867)
 *
 * Unrecognised OSC numbers (0, 1, 4, etc.) pass through unchanged so
 * the terminal emulator can handle them (e.g. OSC 0 sets the title).
 *
 * Format:
 *   ESC ] <number> ; <payload> BEL       (BEL = 0x07)
 *   ESC ] <number> ; <payload> ESC \     (ST  = 0x1B 0x5C)
 *
 * Handles partial sequences across buffer boundaries. Invalid sequences
 * flush accumulated bytes to output. Payload capped at [MAX_PAYLOAD_BYTES].
 *
 * The handler owns a reusable output buffer to avoid per-call allocations.
 * Access the filtered output via [outputBuf] / [outputLen] after [process].
 */
class OscHandler(
    var onClipboardSet: (String) -> Unit = {},
    var onHyperlink: (uri: String?) -> Unit = {},
    var onNotification: (title: String, body: String) -> Unit = { _, _ -> },
    var onCwdChanged: (String) -> Unit = {},
    /** OSC 10/11 foreground/background color query ("?" payload). The owner
     *  replies with the active scheme color via [formatColorReply] (#867). */
    var onColorQuery: (osc: Int, terminator: String) -> Unit = { _, _ -> },
) {

    private enum class State {
        GROUND,       // Waiting for ESC
        ESC,          // Got ESC (0x1B)
        OSC_BRACKET,  // Got ESC ]
        OSC_NUMBER,   // Collecting OSC number digits
        PAYLOAD,      // Collecting payload for handled OSC (stripped from output)
        PASSTHROUGH,  // Forwarding unhandled OSC to output
        ST_ESC,       // Got ESC inside PAYLOAD — expecting '\'
        PT_ST_ESC,    // Got ESC inside PASSTHROUGH — expecting '\'
    }

    companion object {
        /** Max payload before aborting (prevents memory abuse). */
        const val MAX_PAYLOAD_BYTES = 1_048_576 // 1 MB

        /** seqBuf can hold at most ESC ] <digits> ; = ~12 bytes. 64 is plenty. */
        private const val MAX_SEQ_OVERHEAD = 64

        /** OSC numbers we handle (strip from output). */
        private val HANDLED_OSC = setOf(7, 8, 9, 10, 11, 52, 777)

        /**
         * Format an OSC 10/11 color query reply (#867). [argb] is the active
         * scheme color (0xAARRGGBB); each 8-bit channel is expanded to xterm's
         * 16-bit form (0xRR → 0xRRRR). [terminator] echoes the query's (BEL or
         * ST). Example: argb 0xFFEFF1F5 → "rgb:efef/f1f1/f5f5".
         */
        fun formatColorReply(osc: Int, argb: Long, terminator: String): String {
            fun chan(shift: Int): Int = ((argb shr shift) and 0xFF).toInt()
            fun exp(v: Int): String = "%04x".format((v shl 8) or v)
            val r = exp(chan(16))
            val g = exp(chan(8))
            val b = exp(chan(0))
            val esc = 27.toChar()
            return "$esc]$osc;rgb:$r/$g/$b$terminator"
        }
    }

    private var state = State.GROUND
    private var oscNumber = 0

    // Bytes buffered while inside a potential OSC sequence prefix, so we can
    // flush them if the sequence turns out to be something else.
    private val seqBuf = SimpleByteBuffer()
    private val payloadBuf = SimpleByteBuffer()

    /** Reusable output buffer — grows as needed, never shrinks. */
    var outputBuf = ByteArray(4096)
        private set

    /** Number of valid bytes in [outputBuf] after the last [process] call. */
    var outputLen = 0
        private set

    // --- Last-seen OSC events, retained for agent test assertions. ---
    // These mirror what was last dispatched to the callbacks above; they
    // let the MCP layer assert "the OSC scanner saw X" without racing the
    // callback. Never cleared except by a newer event of the same type.

    /** Most recent OSC 52 clipboard-set payload (decoded text). */
    var lastClipboardSet: String? = null
        private set

    /** Most recent OSC 7 working directory. */
    var lastCwd: String? = null
        private set

    /** Most recent OSC 8 hyperlink URI; null after an explicit link-close. */
    var lastHyperlink: String? = null
        private set

    /** Most recent OSC 9 / OSC 777 notification as (title, body). */
    var lastNotification: Pair<String, String>? = null
        private set

    /**
     * Process a chunk of terminal output. Handled OSC sequences are consumed;
     * everything else is written to [outputBuf]. Read [outputLen] bytes
     * from [outputBuf] starting at offset 0 for the filtered result.
     */
    fun process(data: ByteArray, offset: Int, length: Int) {
        // Ensure output buffer is large enough: input + any previously buffered seq bytes
        val needed = length + MAX_SEQ_OVERHEAD
        if (outputBuf.size < needed) {
            outputBuf = ByteArray(needed)
        }
        outputLen = 0

        val end = offset + length
        for (i in offset until end) {
            val b = data[i]
            val u = b.toInt() and 0xFF

            when (state) {
                State.GROUND -> {
                    if (u == 0x1B) {
                        state = State.ESC
                        seqBuf.reset()
                        seqBuf.write(u)
                    } else {
                        emit(b)
                    }
                }

                State.ESC -> {
                    seqBuf.write(u)
                    if (u == ']'.code) {
                        state = State.OSC_BRACKET
                        oscNumber = 0
                    } else {
                        flushSeqBuf()
                    }
                }

                State.OSC_BRACKET -> {
                    seqBuf.write(u)
                    if (u in '0'.code..'9'.code) {
                        oscNumber = u - '0'.code
                        state = State.OSC_NUMBER
                    } else {
                        // Not a digit after ESC ] — flush as passthrough
                        flushSeqBuf()
                    }
                }

                State.OSC_NUMBER -> {
                    seqBuf.write(u)
                    when {
                        u in '0'.code..'9'.code -> {
                            oscNumber = oscNumber * 10 + (u - '0'.code)
                        }
                        u == ';'.code -> {
                            if (oscNumber in HANDLED_OSC) {
                                // Strip this sequence — don't output prefix
                                seqBuf.reset()
                                payloadBuf.reset()
                                state = State.PAYLOAD
                            } else {
                                // Unhandled OSC — flush prefix and pass through rest
                                flushSeqBuf()
                                state = State.PASSTHROUGH
                            }
                        }
                        else -> {
                            // Invalid character in OSC number
                            flushSeqBuf()
                        }
                    }
                }

                State.PAYLOAD -> {
                    processPayloadByte(b, u)
                }

                State.ST_ESC -> {
                    if (u == '\\'.code) {
                        dispatchOsc(27.toChar().toString() + "\\")
                        state = State.GROUND
                    } else {
                        // Not a valid ST — flush everything
                        flushAll()
                        if (u == 0x1B) {
                            state = State.ESC
                            seqBuf.reset()
                            seqBuf.write(u)
                        } else {
                            emit(b)
                        }
                    }
                }

                State.PASSTHROUGH -> {
                    when {
                        u == 0x07 -> {
                            // BEL terminates the passthrough OSC — emit BEL too
                            emit(b)
                            state = State.GROUND
                        }
                        u == 0x1B -> {
                            state = State.PT_ST_ESC
                        }
                        else -> {
                            emit(b)
                        }
                    }
                }

                State.PT_ST_ESC -> {
                    if (u == '\\'.code) {
                        // ST terminates passthrough OSC — emit ESC \ too
                        emit(0x1B.toByte())
                        emit(b)
                        state = State.GROUND
                    } else {
                        // Not ST — emit buffered ESC and continue
                        emit(0x1B.toByte())
                        if (u == 0x1B) {
                            // Another ESC — stay in PT_ST_ESC
                        } else {
                            emit(b)
                            state = State.PASSTHROUGH
                        }
                    }
                }
            }
        }
    }

    /**
     * Legacy API — process into a caller-provided output buffer.
     * @return number of bytes written to [output].
     */
    fun process(data: ByteArray, offset: Int, length: Int, output: ByteArray): Int {
        process(data, offset, length)
        System.arraycopy(outputBuf, 0, output, 0, outputLen)
        return outputLen
    }

    private fun emit(b: Byte) {
        if (outputLen >= outputBuf.size) {
            outputBuf = outputBuf.copyOf(outputBuf.size * 2)
        }
        outputBuf[outputLen++] = b
    }

    /**
     * Grow [outputBuf] so it can hold [additional] more bytes past [outputLen].
     * The per-[process] sizing only budgets for the current chunk plus a small
     * fixed overhead, but [seqBuf]/[payloadBuf] accumulate across calls (an OSC
     * number streamed digit-by-digit, or an OSC-52 payload up to 1 MB split
     * across reads). Flushing those via an unchecked arraycopy into the
     * chunk-sized buffer would overflow it — a remote-triggered crash. Grow
     * first. (#208 findings 6 & 18)
     */
    private fun ensureOutputCapacity(additional: Int) {
        val needed = outputLen + additional
        if (outputBuf.size < needed) {
            var newSize = outputBuf.size * 2
            if (newSize < needed) newSize = needed
            outputBuf = outputBuf.copyOf(newSize)
        }
    }

    private fun processPayloadByte(b: Byte, u: Int) {
        when {
            u == 0x07 -> {
                dispatchOsc(7.toChar().toString())
                state = State.GROUND
            }
            u == 0x1B -> {
                state = State.ST_ESC
            }
            payloadBuf.size() >= MAX_PAYLOAD_BYTES -> {
                Log.w(TAG, "OSC $oscNumber payload exceeded $MAX_PAYLOAD_BYTES bytes, aborting")
                flushAll()
                emit(b)
            }
            else -> {
                payloadBuf.write(u)
            }
        }
    }

    private fun dispatchOsc(terminator: String) {
        val payload = payloadBuf.toByteArray()
        payloadBuf.reset()
        seqBuf.reset()

        val payloadStr = String(payload, Charsets.UTF_8)

        when (oscNumber) {
            52 -> dispatchOsc52(payloadStr)
            7 -> dispatchOsc7(payloadStr)
            8 -> dispatchOsc8(payloadStr)
            9 -> dispatchOsc9(payloadStr)
            777 -> dispatchOsc777(payloadStr)
            // OSC 10/11 with a "?" payload is a foreground/background color
            // query; reply via the owner so remote tools can detect light vs
            // dark (#867). A non-"?" payload (a color set) is ignored.
            10, 11 -> if (payloadStr == "?") onColorQuery(oscNumber, terminator)
        }
    }

    private fun dispatchOsc52(payload: String) {
        // Format: <selection>;<base64-data>
        val semi = payload.indexOf(';')
        if (semi < 0) return

        val base64Data = payload.substring(semi + 1)
        if (base64Data.isEmpty()) return // query request — ignore

        try {
            val decoded = Base64.getMimeDecoder().decode(base64Data.toByteArray())
            val text = String(decoded, Charsets.UTF_8)
            lastClipboardSet = text
            onClipboardSet(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode OSC 52 base64 payload", e)
        }
    }

    private fun dispatchOsc7(payload: String) {
        if (payload.isNotEmpty()) {
            lastCwd = payload
            onCwdChanged(payload)
        }
    }

    private fun dispatchOsc8(payload: String) {
        // Format: <params>;<uri>
        val semi = payload.indexOf(';')
        if (semi < 0) return

        val uri = payload.substring(semi + 1)
        if (uri.isEmpty()) {
            lastHyperlink = null
            onHyperlink(null) // close link
        } else {
            lastHyperlink = uri
            onHyperlink(uri)
        }
    }

    private fun dispatchOsc9(payload: String) {
        if (payload.isNotEmpty()) {
            lastNotification = "" to payload
            onNotification("", payload)
        }
    }

    private fun dispatchOsc777(payload: String) {
        // Format: notify;<title>;<body>
        val parts = payload.split(';', limit = 3)
        if (parts.size < 3) return
        if (parts[0] != "notify") return
        lastNotification = parts[1] to parts[2]
        onNotification(parts[1], parts[2])
    }

    private fun flushSeqBuf() {
        ensureOutputCapacity(seqBuf.size())
        seqBuf.copyInto(outputBuf, outputLen)
        outputLen += seqBuf.size()
        seqBuf.reset()
        state = State.GROUND
    }

    private fun flushAll() {
        ensureOutputCapacity(seqBuf.size())
        seqBuf.copyInto(outputBuf, outputLen)
        outputLen += seqBuf.size()
        seqBuf.reset()

        ensureOutputCapacity(payloadBuf.size())
        payloadBuf.copyInto(outputBuf, outputLen)
        outputLen += payloadBuf.size()
        payloadBuf.reset()

        state = State.GROUND
    }
}

/**
 * Minimal growable byte buffer. Not thread-safe.
 */
internal class SimpleByteBuffer {
    private var buf = ByteArray(256)
    private var count = 0

    fun write(b: Int) {
        ensureCapacity(count + 1)
        buf[count++] = b.toByte()
    }

    fun size(): Int = count

    fun reset() {
        count = 0
    }

    fun toByteArray(): ByteArray = buf.copyOfRange(0, count)

    fun copyInto(dest: ByteArray, destOffset: Int) {
        System.arraycopy(buf, 0, dest, destOffset, count)
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > buf.size) {
            val newSize = maxOf(buf.size * 2, minCapacity)
            buf = buf.copyOf(newSize)
        }
    }
}
