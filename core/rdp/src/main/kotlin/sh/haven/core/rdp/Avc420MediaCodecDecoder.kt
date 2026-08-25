package sh.haven.core.rdp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import sh.haven.rdp.Avc420Decoder
import java.io.Closeable
import java.nio.ByteBuffer

private const val TAG = "Avc420Decoder"

/**
 * MediaCodec-backed H.264/AVC420 decoder for EGFX tiles (#425, KRDP).
 *
 * The Rust session thread owns the framebuffer and decodes every other codec
 * inline, so [decode] is a **blocking** call: one Annex-B access unit in, one
 * `width*height*4` RGBA frame out (empty on failure → the caller drops the
 * tile). A single [MediaCodec] instance persists across calls so its reference
 * picture survives — KRDP sends SPS+PPS+IDR only in the first frame and
 * P-slices thereafter. KRDP's stream is Baseline profile (no B-frames, no
 * reordering), so the 1-in/1-out poll below is valid.
 *
 * ponytail: YUV→RGBA is a straight integer BT.601 loop on the session thread —
 * the known hotspot. Correct first; move to Rust/GL if device profiling shows
 * it caps the framerate. See #425.
 */
class Avc420MediaCodecDecoder : Avc420Decoder, Closeable {

    /**
     * #466: where the per-frame decode time actually goes.
     *
     * A reporter's 1080p KRDP session reports `decode` at 120-243 ms per frame
     * while `publish` is under 3 ms, so decode is ~98% of the frame — but
     * `decode` spans MediaCodec *and* the CPU YUV->RGBA conversion, and
     * attributing it by eye is how the last three rounds of this issue went
     * wrong. A desktop-JVM benchmark puts the conversion alone at 7 ms for
     * 1080p, which extrapolates to tens of ms on a phone and does **not**
     * account for the rest. So measure the split rather than argue about it.
     *
     * Set by [RdpSession] so the line reaches the in-app verbose log (#477)
     * rather than needing adb.
     */
    var perfSink: ((String) -> Unit)? = null

    private var pFrames = 0L
    private var pCodecNs = 0L
    private var pConvertNs = 0L
    private var pPolls = 0L
    private var codec: MediaCodec? = null
    private var lastConvertNs = 0L
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var ptsUs = 0L
    @Volatile private var failed = false
    private val bufferInfo = MediaCodec.BufferInfo()
    // Reused I420 scratch, grown as needed to avoid a per-frame allocation.
    private var i420 = ByteArray(0)

    /**
     * Decode one access unit straight into the caller's buffer (#466).
     *
     * This used to return the frame as a `ByteArray` for UniFFI to marshal
     * back. That crossing cost 47 ms per 1080p frame with a decoder that did
     * no decoding at all — linear in payload at about 62 MB/s, against 45 GB/s
     * for the same copy inside Rust — and was the ~80 ms per frame neither
     * side could account for. Writing into the caller's buffer measured
     * 0.24 ms for the same 3037 KB.
     *
     * The packing itself is unchanged: [yuvImageToI420] still fills the reused
     * scratch, and one bulk copy hands it over. Keeping that split means
     * [packPlane] and its tests carry on covering the pixel work, and only the
     * handoff is new.
     *
     * SAFETY: [dstAddr] is valid for [dstLen] bytes only for the duration of
     * this call. Never retain it, and never write past [dstLen].
     */
    override fun decodeInto(
        annexB: ByteArray,
        width: UShort,
        height: UShort,
        dstAddr: ULong,
        dstLen: ULong,
    ): UInt {
        val w = width.toInt()
        val h = height.toInt()
        if (failed || w <= 0 || h <= 0 || annexB.isEmpty()) return 0u
        val mc = try {
            ensureCodec(annexB, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec init failed (${w}x${h}): ${e.message}")
            failed = true
            releaseCodec()
            return 0u
        } ?: return 0u

        return try {
            val t0 = System.nanoTime()
            queueInput(mc, annexB)
            val out = drainToI420(mc, w, h) ?: ByteArray(0)
            val written = if (out.isEmpty()) {
                0
            } else {
                val n = minOf(out.size.toLong(), dstLen.toLong()).toInt()
                // One bulk copy into the caller's memory. `getByteBuffer` hands
                // back a direct buffer over that address, so this is a memcpy
                // rather than the marshalling it replaces.
                com.sun.jna.Pointer(dstAddr.toLong())
                    .getByteBuffer(0, n.toLong())
                    .put(out, 0, n)
                n
            }
            pFrames++
            pCodecNs += System.nanoTime() - t0 - lastConvertNs
            pConvertNs += lastConvertNs
            lastConvertNs = 0L
            if (pFrames >= PERF_REPORT_FRAMES) {
                val line = "AVC420 decode split over $pFrames frames — mediacodec ${pCodecNs / pFrames / 1000}us, " +
                    "i420 pack ${pConvertNs / pFrames / 1000}us, polls/frame ${"%.1f".format(pPolls.toDouble() / pFrames)}"
                Log.i(TAG, line)
                perfSink?.invoke(line)
                pFrames = 0; pCodecNs = 0; pConvertNs = 0; pPolls = 0
            }
            written.toUInt()
        } catch (e: Exception) {
            Log.e(TAG, "AVC420 decode failed: ${e.message}")
            failed = true
            releaseCodec()
            0u
        }
    }

    /** Configure the decoder on the first frame using its in-band SPS/PPS. */
    private fun ensureCodec(annexB: ByteArray, w: Int, h: Int): MediaCodec? {
        codec?.let { return it }
        val sps = findNal(annexB, 7)
        val pps = findNal(annexB, 8)
        if (sps == null || pps == null) {
            // First AU must carry the parameter sets; a P-slice can't configure.
            Log.e(TAG, "first AVC420 AU lacks SPS/PPS — cannot configure")
            failed = true
            return null
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // Ask for a byte-buffer output format we can read as planar YUV.
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            // #477: the standard low-latency key is a request many decoders
            // ignore; the vendor extensions below are what actually collapse
            // the output pipeline on Qualcomm/MediaTek/Exynos parts. Unknown
            // keys are silently dropped by codecs that don't recognise them,
            // so setting all of them is safe everywhere. PRIORITY 0 marks the
            // session realtime, which affects codec scheduling on big.LITTLE.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
            setInteger("vendor.low-latency.enable", 1)
        }
        val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mc.configure(format, /* surface = */ null, /* crypto = */ null, /* flags = */ 0)
        mc.start()
        codec = mc
        configuredWidth = w
        configuredHeight = h
        Log.d(TAG, "AVC420 MediaCodec configured ${w}x${h} (${mc.codecInfo.name})")
        return mc
    }

    private fun queueInput(mc: MediaCodec, annexB: ByteArray) {
        // Bounded wait for an input buffer; the decoder should always have one
        // for a low-latency Baseline stream.
        val inIx = mc.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inIx < 0) throw IllegalStateException("no input buffer available")
        val inBuf = mc.getInputBuffer(inIx) ?: throw IllegalStateException("null input buffer")
        inBuf.clear()
        inBuf.put(annexB)
        mc.queueInputBuffer(inIx, 0, annexB.size, ptsUs, 0)
        ptsUs += FRAME_INTERVAL_US
    }

    /** Poll for the single output frame for the AU just queued. */
    private fun drainToI420(mc: MediaCodec, w: Int, h: Int): ByteArray? {
        var polls = 0
        while (polls++ < MAX_OUTPUT_POLLS) {
            pPolls++
            val outIx = mc.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                outIx >= 0 -> {
                    val out = try {
                        val image = mc.getOutputImage(outIx)
                        if (image != null) {
                            val c0 = System.nanoTime()
                            val r = yuvImageToI420(image, w, h)
                            lastConvertNs += System.nanoTime() - c0
                            r
                        } else {
                            null
                        }
                    } finally {
                        mc.releaseOutputBuffer(outIx, /* render = */ false)
                    }
                    if (out != null) return out
                    // No image (config-only) — keep polling.
                }
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "AVC420 output format: ${mc.outputFormat}")
                }
                outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Decoder still working on this AU; retry within budget.
                }
                else -> { /* INFO_OUTPUT_BUFFERS_CHANGED (deprecated) — ignore */ }
            }
        }
        Log.w(TAG, "AVC420 decode produced no frame within poll budget")
        return null
    }

    /**
     * Repack a YUV_420_888 [android.media.Image] into tightly-packed I420 at
     * [w]x[h], edge-replicating where the requested size exceeds the decoded
     * one. Handles planar (I420) and semi-planar (NV12/NV21) chroma via each
     * plane's row and pixel stride.
     *
     * This replaces a full BT.601 conversion to RGBA, which cost 27-109 ms per
     * frame on a reporter's 1080p session against 9-25 ms for the hardware
     * decode itself (#466). Colour conversion now happens in Rust; what leaves
     * here is 1.5 bytes per pixel instead of 4, which also cuts what the
     * Kotlin/Rust boundary has to allocate and copy — separately measured at
     * 87-112 ms per frame.
     *
     * The work left is byte movement with no arithmetic, and the common case
     * (a plane whose rowStride equals its width) is a bulk copy rather than a
     * loop.
     */
    private fun yuvImageToI420(image: android.media.Image, w: Int, h: Int): ByteArray {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val need = w * h + 2 * cw * ch
        if (i420.size < need) i420 = ByteArray(need)
        val out = i420

        val srcW = minOf(w, image.width)
        val srcH = minOf(h, image.height)
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]

        packPlane(out, 0, yP.buffer, yP.rowStride, 1, w, h, srcW, srcH)
        // Chroma is half resolution in both axes; the source's own chroma
        // extent follows from its luma extent the same way.
        val sCw = (srcW + 1) / 2
        val sCh = (srcH + 1) / 2
        packPlane(out, w * h, uP.buffer, uP.rowStride, uP.pixelStride, cw, ch, sCw, sCh)
        packPlane(out, w * h + cw * ch, vP.buffer, vP.rowStride, vP.pixelStride, cw, ch, sCw, sCh)

        return exactly(out, need)
    }

    /**
     * Exactly [need] bytes, without copying when the scratch buffer is already
     * that size (#477).
     *
     * Handing back the scratch buffer itself is safe because the caller copies
     * it into the Rust-owned frame buffer synchronously, before control
     * returns to the loop that would overwrite it, and nothing retains it
     * afterwards.
     *
     * ★ That rests on the copy being synchronous. If decoding ever becomes
     * asynchronous, or something starts holding this array across frames,
     * restore a defensive copy.
     */
    internal fun exactly(out: ByteArray, need: Int): ByteArray =
        if (out.size == need) out else out.copyOf(need)

    /**
     * Copy one plane into [out] at [offset] as [dstW]x[dstH] tightly packed,
     * reading [srcW]x[srcH] from [buf] and replicating the last row/column
     * beyond it.
     *
     * Pure over its arguments so it can be tested off-device (see
     * Avc420I420PackTest).
     */
    internal fun packPlane(
        out: ByteArray,
        offset: Int,
        buf: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        dstW: Int,
        dstH: Int,
        srcW: Int,
        srcH: Int,
    ) {
        var o = offset
        for (y in 0 until dstH) {
            val sy = if (y < srcH) y else srcH - 1
            val row = sy * rowStride
            if (pixelStride == 1 && dstW <= srcW) {
                // Planar and no replication needed — one bulk copy per row.
                val dup = buf.duplicate()
                dup.position(row)
                dup.get(out, o, dstW)
                o += dstW
            } else {
                var x = 0
                while (x < dstW) {
                    val sx = if (x < srcW) x else srcW - 1
                    out[o] = buf.get(row + sx * pixelStride)
                    o++
                    x++
                }
            }
        }
    }

    private fun releaseCodec() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }

    override fun close() {
        releaseCodec()
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 20_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val MAX_OUTPUT_POLLS = 8
        const val PERF_REPORT_FRAMES = 30L

        // Nominal 60 fps spacing; PTS ordering only, value is otherwise unused
        // for a no-reorder Baseline stream.
        const val FRAME_INTERVAL_US = 16_666L

        /**
         * Return the first Annex-B NAL (including its start code) whose
         * nal_unit_type == [type], or null. Scans for 3- and 4-byte start codes.
         */
        fun findNal(data: ByteArray, type: Int): ByteArray? {
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 3 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (data[i + 2] == 1.toByte()) {
                        starts.add(i); i += 3; continue
                    }
                    if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        starts.add(i); i += 4; continue
                    }
                }
                i++
            }
            for ((k, s) in starts.withIndex()) {
                // NAL header byte follows the start code (00 00 01 or 00 00 00 01).
                val hdr = if (s + 3 < data.size && data[s + 2] == 1.toByte()) s + 3 else s + 4
                if (hdr >= data.size) continue
                if ((data[hdr].toInt() and 0x1F) == type) {
                    val end = if (k + 1 < starts.size) starts[k + 1] else data.size
                    return data.copyOfRange(s, end)
                }
            }
            return null
        }
    }
}
