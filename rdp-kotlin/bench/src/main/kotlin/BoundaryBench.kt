@file:JvmName("BoundaryBench")

package sh.haven.rdp.bench

import sh.haven.rdp.Avc420Decoder
import sh.haven.rdp.benchmarkAvcBoundary
import com.sun.jna.Pointer
import sh.haven.rdp.FfiConverterByteArray
import sh.haven.rdp.RustBuffer
import java.nio.ByteOrder

/**
 * Measures what it costs to *cross* the Rust/Kotlin boundary for one AVC frame,
 * with nothing on the far side but a buffer (#466).
 *
 * A reporter's KRDP session spends ~80 ms per frame inside `decode_to_i420`
 * that neither side accounts for: their host timers show ~50 ms of MediaCodec
 * plus I420 packing, and a Rust copy of the same 3.11 MB costs 1-5 ms. Three
 * candidates were eliminated from their logs (payload size, the MediaCodec
 * poll loop, a lock held across the call) and the remainder is the call
 * itself. This reproduces that call without an RDP server, without H.264, and
 * without a phone.
 *
 * The stand-in decoder does no work: it returns a reused buffer of exactly the
 * size a real 1080p I420 frame would be, and writes the nanoseconds it spent
 * in its own body into the first 8 bytes. So:
 *
 *     crossing = call_us - host_us - memcpy_us
 *
 * ## What this rig can and cannot tell us
 *
 * It runs on desktop HotSpot, not Android ART, against the host build of the
 * library. JNA's marshalling and the FFI mechanics are the same code; the JIT
 * and the allocator are not. So a result in the tens of milliseconds here
 * identifies the cost outright, while microseconds here means the cost is
 * specific to the device and this rig has ruled the mechanism *in* but not
 * located it. Either outcome is worth having before touching the decode path.
 *
 * Run: `./gradlew -p rdp-kotlin/bench run` (see that build file for the
 * library path it expects).
 */
private class StandInDecoder(payloadBytes: Int) : Avc420Decoder {
    /** Reused exactly as the real decoder reuses its I420 scratch. */
    private val scratch = ByteArray(payloadBytes)

    override fun decodeInto(
        annexB: ByteArray,
        width: UShort,
        height: UShort,
        dstAddr: ULong,
        dstLen: ULong,
    ): UInt {
        val t0 = System.nanoTime()
        // The one thing a real decoder always does that we must not skip:
        // touch the buffer. Without this the JIT is free to notice the body is
        // empty, and we would be timing nothing.
        scratch[0] = annexB.size.toByte()
        scratch[scratch.size - 1] = width.toByte()
        val spentUs = (System.nanoTime() - t0) / 1000
        // Report our own cost back in-band so Rust can subtract it.
        for (i in 0 until 8) {
            scratch[i] = ((spentUs shr (8 * i)) and 0xFF).toByte()
        }
        val n = minOf(scratch.size.toLong(), dstLen.toLong()).toInt()
        Pointer(dstAddr.toLong()).getByteBuffer(0, n.toLong()).put(scratch, 0, n)
        return n.toUInt()
    }
}

fun main(args: Array<String>) {
    val width = (args.getOrNull(0)?.toIntOrNull() ?: 1920).toUShort()
    val height = (args.getOrNull(1)?.toIntOrNull() ?: 1080).toUShort()
    val iterations = args.getOrNull(2)?.toIntOrNull() ?: 200

    val w = width.toInt()
    val h = height.toInt()
    val cw = (w + 1) / 2
    val ch = (h + 1) / 2
    val payload = w * h + 2 * cw * ch

    println("AVC boundary rig — ${w}x$h, I420 payload ${payload / 1024} KB, $iterations iterations")
    println("JNA: ${runCatching { com.sun.jna.Native.VERSION }.getOrElse { "?" }}, JVM ${System.getProperty("java.version")}")

    val t = benchmarkAvcBoundary(StandInDecoder(payload), width, height, iterations.toUInt())

    val n = t.iterations.toDouble()
    val call = t.callUs.toDouble() / n
    val host = t.hostUs.toDouble() / n
    val memcpy = t.memcpyUs.toDouble() / n
    val crossing = call - host - memcpy

    println()
    println("per iteration:")
    println("  total in decode_into      %8.3f ms".format(call / 1000))
    println("  reported by the host      %8.3f ms".format(host / 1000))
    println("  Rust alloc+copy control   %8.3f ms".format(memcpy / 1000))
    println("  ---------------------------------")
    println("  crossing                  %8.3f ms".format(crossing / 1000))
    println()
    println("payload actually returned: ${t.payloadBytes.toLong() / 1024} KB")
    if (t.payloadBytes.toInt() != payload) {
        println("  WARNING: expected $payload bytes — the stand-in and Rust disagree on the frame size")
    }
    println()
    probeReturnPath(payload, iterations)

    println()
    println("Field comparison (@ysalmon, 1080p KRDP, v5.87.x): decode_to_i420 51-138 ms,")
    println("host side 15-52 ms, Rust alloc+copy of the same buffer 1-5 ms.")
}

/**
 * Times the individual steps uniffi performs on the return path, to locate the
 * per-byte cost rather than infer it.
 *
 * The generated bindings compile into this same module, so their `internal`
 * helpers are reachable here. That is the whole reason the rig shares
 * `../kotlin` as a source directory instead of depending on a built artefact.
 */
private fun probeReturnPath(payload: Int, iterations: Int) {
    val value = ByteArray(payload)

    fun time(label: String, body: () -> Unit) {
        repeat(20) { body() }               // warm up the JIT before timing
        val t0 = System.nanoTime()
        repeat(iterations) { body() }
        val ms = (System.nanoTime() - t0) / 1e6 / iterations
        val mbs = payload / 1024.0 / 1024.0 / (ms / 1000)
        println("  %-38s %8.3f ms  %8.0f MB/s".format(label, ms, mbs))
    }

    println()
    println("uniffi return path, ${payload / 1024} KB, $iterations iterations:")

    time("RustBuffer.alloc + free (no copy)") {
        RustBuffer.free(RustBuffer.alloc(payload.toULong()))
    }

    time("alloc + direct ByteBuffer put + free") {
        val rbuf = RustBuffer.alloc(payload.toULong())
        val bbuf = rbuf.data!!.getByteBuffer(0, rbuf.capacity).also { it.order(ByteOrder.BIG_ENDIAN) }
        bbuf.put(value)
        RustBuffer.free(rbuf)
    }

    time("FfiConverterByteArray.lower + free") {
        RustBuffer.free(FfiConverterByteArray.lower(value))
    }

    // Plain JVM control: the same bytes, heap to heap, no FFI involved.
    val dst = ByteArray(payload)
    time("System.arraycopy (JVM control)") {
        System.arraycopy(value, 0, dst, 0, payload)
    }
}
