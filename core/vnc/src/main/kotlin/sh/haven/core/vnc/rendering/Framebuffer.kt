package sh.haven.core.vnc.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import sh.haven.core.vnc.VncSession
import sh.haven.core.vnc.protocol.ColorMapEntry
import sh.haven.core.vnc.protocol.Encoding
import sh.haven.core.vnc.protocol.FramebufferUpdate
import sh.haven.core.vnc.protocol.PixelFormat
import sh.haven.core.vnc.protocol.Rectangle
import sh.haven.core.vnc.protocol.SetColorMapEntries
import sh.haven.core.vnc.protocol.VncException
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

/**
 * Manages the VNC framebuffer as an Android Bitmap.
 * Renderers decode VNC encoding formats and paint pixels onto the bitmap.
 */
class Framebuffer(private val session: VncSession) {

    private val colorMap = ConcurrentHashMap<Long, ColorMapEntry>()
    private var frame: Bitmap = Bitmap.createBitmap(
        session.framebufferWidth,
        session.framebufferHeight,
        Bitmap.Config.ARGB_8888,
    )
    private val canvas = Canvas(frame)
    private val paint = Paint()

    // RFB defines separate session-continuous zlib streams per encoding.
    // Do NOT share one inflater between ZLIB and ZRLE — their streams are
    // distinct, and resetting input between rects corrupts both encodings.
    private val zlibInflater = Inflater()
    private val zlibReader = InflaterReader(zlibInflater)
    private val zrleInflater = Inflater()
    private val zrleReader = InflaterReader(zrleInflater)

    // Reusable buffer for bulk pixel reads
    private var rawBuf = ByteArray(0)
    private var pixelBuf = IntArray(0)

    fun processUpdate(update: FramebufferUpdate) {
        val input = session.inputStream
        try {
            session.lastUpdateHadRectangles = update.numberOfRectangles > 0
            for (i in 0 until update.numberOfRectangles) {
                val rect = Rectangle.decode(input)
                when (rect.encoding) {
                    Encoding.DESKTOP_SIZE -> resizeFramebuffer(rect)
                    Encoding.RAW -> renderRaw(input, rect.x, rect.y, rect.width, rect.height)
                    Encoding.COPYRECT -> renderCopyRect(input, rect)
                    Encoding.RRE -> renderRre(input, rect)
                    Encoding.HEXTILE -> renderHextile(input, rect)
                    Encoding.ZLIB -> renderZlib(input, rect)
                    Encoding.ZRLE -> renderZrle(input, rect)
                    Encoding.CURSOR -> renderCursor(input, rect)
                    null -> throw VncException("Unsupported encoding in rectangle")
                }
            }
            notifyUpdate()
            session.framebufferUpdated()
        } catch (e: IOException) {
            throw VncException("Framebuffer update failed", e)
        }
    }

    fun updateColorMap(entries: SetColorMapEntries) {
        for (i in entries.colors.indices) {
            colorMap[(i + entries.firstColor).toLong()] = entries.colors[i]
        }
    }

    private fun notifyUpdate() {
        val listener = session.config.onScreenUpdate ?: return
        // Copy the bitmap for thread-safe delivery to the UI
        listener(frame.copy(Bitmap.Config.ARGB_8888, false))
    }

    private fun resizeFramebuffer(rect: Rectangle) {
        val w = rect.width
        val h = rect.height
        session.framebufferWidth = w
        session.framebufferHeight = h
        val old = frame
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val newCanvas = Canvas(frame)
        newCanvas.drawBitmap(old, 0f, 0f, null)
        canvas.setBitmap(frame)
        old.recycle()
    }

    // ---- Raw encoding ----

    private fun renderRaw(input: InputStream, x: Int, y: Int, w: Int, h: Int) {
        val pf = session.pixelFormat!!
        val numPixels = w * h
        val numBytes = numPixels * pf.bytesPerPixel

        // Fast path: 32bpp LE true-color with standard shifts (BGRX byte order = ARGB_8888)
        if (pf.bitsPerPixel == 32 && !pf.bigEndian && pf.trueColor &&
            pf.redMax == 255 && pf.greenMax == 255 && pf.blueMax == 255 &&
            pf.redShift == 16 && pf.greenShift == 8 && pf.blueShift == 0
        ) {
            renderRawFast32(input, x, y, w, h, numPixels, numBytes)
            return
        }

        // Generic slow path for other pixel formats
        renderRawGeneric(input, x, y, w, h, pf)
    }

    private fun renderRawFast32(
        input: InputStream, x: Int, y: Int, w: Int, h: Int,
        numPixels: Int, numBytes: Int,
    ) {
        // Ensure buffers are large enough
        if (rawBuf.size < numBytes) rawBuf = ByteArray(numBytes)
        if (pixelBuf.size < numPixels) pixelBuf = IntArray(numPixels)

        // Bulk read all pixel bytes
        readFully(input, rawBuf, numBytes)

        // Convert BGRX bytes to ARGB_8888 ints using ByteBuffer
        val bb = ByteBuffer.wrap(rawBuf, 0, numBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.asIntBuffer().get(pixelBuf, 0, numPixels)

        // Set alpha to 0xFF for each pixel (X11 sends 0x00 in the alpha byte)
        for (i in 0 until numPixels) {
            pixelBuf[i] = pixelBuf[i] or 0xFF000000.toInt()
        }

        // Bulk write to bitmap
        frame.setPixels(pixelBuf, 0, w, x, y, w, h)
    }

    private fun renderRawGeneric(
        input: InputStream, x: Int, y: Int, w: Int, h: Int, pf: PixelFormat,
    ) {
        val numPixels = w * h
        if (pixelBuf.size < numPixels) pixelBuf = IntArray(numPixels)

        for (i in 0 until numPixels) {
            pixelBuf[i] = decodePixelColor(input, pf)
        }
        frame.setPixels(pixelBuf, 0, w, x, y, w, h)
    }

    // ---- CopyRect encoding ----

    private fun renderCopyRect(input: InputStream, rect: Rectangle) {
        val d = DataInputStream(input)
        val srcX = d.readUnsignedShort()
        val srcY = d.readUnsignedShort()
        val src = Bitmap.createBitmap(frame, srcX, srcY, rect.width, rect.height)
        canvas.drawBitmap(src, rect.x.toFloat(), rect.y.toFloat(), null)
        src.recycle()
    }

    // ---- RRE encoding ----

    private fun renderRre(input: InputStream, rect: Rectangle) {
        val d = DataInputStream(input)
        val pf = session.pixelFormat!!
        val numSubrects = d.readInt()
        val bgColor = decodePixelColor(input, pf)

        paint.color = bgColor
        canvas.drawRect(
            rect.x.toFloat(), rect.y.toFloat(),
            (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat(),
            paint,
        )

        for (i in 0 until numSubrects) {
            val color = decodePixelColor(input, pf)
            val sx = d.readUnsignedShort()
            val sy = d.readUnsignedShort()
            val sw = d.readUnsignedShort()
            val sh = d.readUnsignedShort()
            paint.color = color
            canvas.drawRect(
                (rect.x + sx).toFloat(), (rect.y + sy).toFloat(),
                (rect.x + sx + sw).toFloat(), (rect.y + sy + sh).toFloat(),
                paint,
            )
        }
    }

    // ---- Hextile encoding ----

    private fun renderHextile(input: InputStream, rect: Rectangle) {
        val d = DataInputStream(input)
        val pf = session.pixelFormat!!
        val tileSize = 16

        val hTiles = (rect.width + tileSize - 1) / tileSize
        val vTiles = (rect.height + tileSize - 1) / tileSize

        var lastBg = 0xFF000000.toInt()
        var lastFg = 0xFFFFFFFF.toInt()

        for (tileY in 0 until vTiles) {
            for (tileX in 0 until hTiles) {
                val tx = rect.x + tileX * tileSize
                val ty = rect.y + tileY * tileSize
                val tw = tileWidth(tileX, hTiles, rect.width, tileSize)
                val th = tileWidth(tileY, vTiles, rect.height, tileSize)

                val subencoding = d.readUnsignedByte()
                val raw = (subencoding and 0x01) != 0

                if (raw) {
                    renderRaw(input, tx, ty, tw, th)
                } else {
                    val hasBg = (subencoding and 0x02) != 0
                    val hasFg = (subencoding and 0x04) != 0
                    val hasSubrects = (subencoding and 0x08) != 0
                    val subrectsColored = (subencoding and 0x10) != 0

                    if (hasBg) lastBg = decodePixelColor(input, pf)
                    if (hasFg) lastFg = decodePixelColor(input, pf)

                    paint.color = lastBg
                    canvas.drawRect(
                        tx.toFloat(), ty.toFloat(),
                        (tx + tw).toFloat(), (ty + th).toFloat(),
                        paint,
                    )

                    if (hasSubrects) {
                        val count = d.readUnsignedByte()
                        for (s in 0 until count) {
                            val color = if (subrectsColored) decodePixelColor(input, pf) else lastFg
                            val coords = d.readUnsignedByte()
                            val dims = d.readUnsignedByte()
                            val sx = coords shr 4
                            val sy = coords and 0x0f
                            val sw = (dims shr 4) + 1
                            val sh = (dims and 0x0f) + 1
                            paint.color = color
                            canvas.drawRect(
                                (tx + sx).toFloat(), (ty + sy).toFloat(),
                                (tx + sx + sw).toFloat(), (ty + sy + sh).toFloat(),
                                paint,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun tileWidth(tileNo: Int, total: Int, rectSize: Int, tileSize: Int): Int {
        val overlap = rectSize % tileSize
        return if (tileNo == total - 1 && overlap != 0) overlap else tileSize
    }

    // ---- ZLib encoding ----

    private fun renderZlib(input: InputStream, rect: Rectangle) {
        val d = DataInputStream(input)
        val compressedLen = d.readInt()
        val compressed = ByteArray(compressedLen)
        d.readFully(compressed)

        // ZLIB is a single session-continuous zlib stream carrying raw pixels.
        // Feed this rect's compressed bytes into the reader and pull exactly
        // w*h*bpp bytes back out via the existing renderRaw path.
        zlibReader.addCompressed(compressed)
        renderRaw(zlibReader, rect.x, rect.y, rect.width, rect.height)
    }

    // ---- ZRLE encoding ----

    private fun renderZrle(input: InputStream, rect: Rectangle) {
        val d = DataInputStream(input)
        val compressedLen = d.readInt()
        val compressed = ByteArray(compressedLen)
        d.readFully(compressed)
        zrleReader.addCompressed(compressed)

        val pf = session.pixelFormat!!
        val tileSize = 64
        val hTiles = (rect.width + tileSize - 1) / tileSize
        val vTiles = (rect.height + tileSize - 1) / tileSize

        for (tileY in 0 until vTiles) {
            for (tileX in 0 until hTiles) {
                val tx = rect.x + tileX * tileSize
                val ty = rect.y + tileY * tileSize
                val tw = tileWidth(tileX, hTiles, rect.width, tileSize)
                val th = tileWidth(tileY, vTiles, rect.height, tileSize)
                decodeZrleTile(pf, tx, ty, tw, th)
            }
        }
    }

    private fun decodeZrleTile(pf: PixelFormat, tx: Int, ty: Int, tw: Int, th: Int) {
        val subencoding = zrleReader.read()
        if (subencoding < 0) throw IOException("ZRLE underflow reading subencoding")
        val numPixels = tw * th

        when {
            subencoding == 0 -> {
                // Raw CPIXEL stream.
                if (pixelBuf.size < numPixels) pixelBuf = IntArray(numPixels)
                for (i in 0 until numPixels) pixelBuf[i] = readCpixel(pf)
                frame.setPixels(pixelBuf, 0, tw, tx, ty, tw, th)
            }
            subencoding == 1 -> {
                // Solid tile: one CPIXEL fills the whole tile.
                val color = readCpixel(pf)
                if (pixelBuf.size < numPixels) pixelBuf = IntArray(numPixels)
                for (i in 0 until numPixels) pixelBuf[i] = color
                frame.setPixels(pixelBuf, 0, tw, tx, ty, tw, th)
            }
            subencoding in 2..16 -> {
                // Packed palette tile.
                val paletteSize = subencoding
                val palette = IntArray(paletteSize) { readCpixel(pf) }
                val bitsPerIndex = when {
                    paletteSize == 2 -> 1
                    paletteSize <= 4 -> 2
                    else -> 4
                }
                if (pixelBuf.size < numPixels) pixelBuf = IntArray(numPixels)
                unpackPaletteIndices(palette, bitsPerIndex, tw, th, pixelBuf)
                frame.setPixels(pixelBuf, 0, tw, tx, ty, tw, th)
            }
            subencoding == 128 -> {
                // Plain RLE: runs of <CPIXEL, length>.
                if (pixelBuf.size < numPixels) pixelBuf = IntArray(numPixels)
                var filled = 0
                while (filled < numPixels) {
                    val color = readCpixel(pf)
                    val runLen = readRunLength()
                    val end = minOf(filled + runLen, numPixels)
                    for (i in filled until end) pixelBuf[i] = color
                    filled = end
                }
                frame.setPixels(pixelBuf, 0, tw, tx, ty, tw, th)
            }
            subencoding in 130..255 -> {
                // Palette RLE: palette first, then runs of <index[, length]>.
                // Palette size = subencoding - 128 (so range 2..127).
                val paletteSize = subencoding - 128
                val palette = IntArray(paletteSize) { readCpixel(pf) }
                if (pixelBuf.size < numPixels) pixelBuf = IntArray(numPixels)
                var filled = 0
                while (filled < numPixels) {
                    val raw = zrleReader.read()
                    if (raw < 0) throw IOException("ZRLE underflow in palette RLE")
                    val runTop = (raw and 0x80) != 0
                    val idx = raw and 0x7F
                    val color = if (idx < palette.size) palette[idx] else 0xFF000000.toInt()
                    val runLen = if (runTop) readRunLength() else 1
                    val end = minOf(filled + runLen, numPixels)
                    for (i in filled until end) pixelBuf[i] = color
                    filled = end
                }
                frame.setPixels(pixelBuf, 0, tw, tx, ty, tw, th)
            }
            else -> throw VncException("Unsupported ZRLE subencoding: $subencoding")
        }
    }

    /** Read a ZRLE run-length: byte chunks until one is <255, sum(all bytes)+1. */
    private fun readRunLength(): Int {
        var length = 1
        while (true) {
            val b = zrleReader.read()
            if (b < 0) throw IOException("ZRLE underflow reading run length")
            length += b
            if (b < 255) return length
        }
    }

    /**
     * Unpack packed palette indices. Each row is padded to a byte boundary.
     * bitsPerIndex is 1, 2, or 4; highest-order bits come first within a byte.
     */
    private fun unpackPaletteIndices(
        palette: IntArray, bitsPerIndex: Int, tw: Int, th: Int, dst: IntArray,
    ) {
        val bytesPerRow = (tw * bitsPerIndex + 7) / 8
        val rowBuf = ByteArray(bytesPerRow)
        val mask = (1 shl bitsPerIndex) - 1
        var dstOffset = 0
        for (row in 0 until th) {
            readFully(zrleReader, rowBuf, bytesPerRow)
            for (col in 0 until tw) {
                val bitPos = col * bitsPerIndex
                val byteIdx = bitPos ushr 3
                val shift = 8 - bitsPerIndex - (bitPos and 7)
                val idx = (rowBuf[byteIdx].toInt() ushr shift) and mask
                dst[dstOffset + col] = if (idx < palette.size) palette[idx] else 0xFF000000.toInt()
            }
            dstOffset += tw
        }
    }

    /** Read one CPIXEL from the ZRLE stream and return an ARGB_8888 int. */
    private fun readCpixel(pf: PixelFormat): Int {
        if (isCpixelCompact(pf)) {
            val b0 = zrleReader.read()
            val b1 = zrleReader.read()
            val b2 = zrleReader.read()
            if ((b0 or b1 or b2) < 0) throw IOException("ZRLE underflow reading CPIXEL")
            // CPIXEL omits the byte that the server's PIXEL shifts never touch.
            // For LE bpp=32 shifts R16/G8/B0, the bytes are [B,G,R]; for BE it
            // would be [R,G,B]. We reconstruct the 32-bit value per pf byte
            // order and then use the pf's own shifts to pick out channels.
            val value = if (pf.bigEndian) {
                ((b0.toLong() and 0xFF) shl 16) or
                    ((b1.toLong() and 0xFF) shl 8) or
                    (b2.toLong() and 0xFF)
            } else {
                (b0.toLong() and 0xFF) or
                    ((b1.toLong() and 0xFF) shl 8) or
                    ((b2.toLong() and 0xFF) shl 16)
            }
            val r = ((value shr pf.redShift) and pf.redMax.toLong()).toInt()
            val g = ((value shr pf.greenShift) and pf.greenMax.toLong()).toInt()
            val b = ((value shr pf.blueShift) and pf.blueMax.toLong()).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return decodePixelColor(zrleReader, pf)
    }

    private fun isCpixelCompact(pf: PixelFormat): Boolean {
        return pf.bitsPerPixel == 32 &&
            pf.trueColor &&
            pf.depth <= 24 &&
            pf.redMax == 255 && pf.greenMax == 255 && pf.blueMax == 255
    }

    // ---- Cursor pseudo-encoding ----

    /**
     * Decode the Cursor pseudo-encoding (RFC 6143 §7.8.1): rect.width×rect.height
     * pixels followed by a 1-bit bitmask (rows padded to a byte boundary, MSB
     * first). Mask bits select which pixels are opaque; zero pixels become
     * transparent. rect.x/rect.y carry the hotspot, not a framebuffer position.
     */
    private fun renderCursor(input: InputStream, rect: Rectangle) {
        val w = rect.width
        val h = rect.height
        val hotspotX = rect.x
        val hotspotY = rect.y
        val listener = session.config.onCursorUpdate

        if (w == 0 || h == 0) {
            listener?.invoke(null, 0, 0)
            return
        }

        val pf = session.pixelFormat!!
        val pixelBytes = pf.bytesPerPixel * w * h
        val maskBytes = ((w + 7) / 8) * h
        val d = DataInputStream(input)
        val pixelData = ByteArray(pixelBytes)
        d.readFully(pixelData)
        val maskData = ByteArray(maskBytes)
        d.readFully(maskData)

        if (listener == null) return  // Decoded but no consumer — drop silently.

        val pixelStream = java.io.ByteArrayInputStream(pixelData)
        val argb = IntArray(w * h)
        val stride = (w + 7) / 8
        for (row in 0 until h) {
            for (col in 0 until w) {
                val color = decodePixelColor(pixelStream, pf)
                val maskByte = maskData[row * stride + (col ushr 3)].toInt() and 0xFF
                val bit = (maskByte ushr (7 - (col and 7))) and 1
                argb[row * w + col] = if (bit == 1) color else 0
            }
        }
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        listener(bmp, hotspotX, hotspotY)
    }

    // ---- Pixel decoding ----

    private fun decodePixelColor(input: InputStream, pf: PixelFormat): Int {
        val bytesToRead = pf.bytesPerPixel
        var value = 0L
        if (pf.bigEndian) {
            for (i in 0 until bytesToRead) {
                value = (value shl 8) or (input.read().toLong() and 0xFF)
            }
        } else {
            for (i in 0 until bytesToRead) {
                value = value or ((input.read().toLong() and 0xFF) shl (i * 8))
            }
        }

        val r: Int
        val g: Int
        val b: Int

        if (pf.trueColor) {
            r = stretch(((value shr pf.redShift) and pf.redMax.toLong()).toInt(), pf.redMax)
            g = stretch(((value shr pf.greenShift) and pf.greenMax.toLong()).toInt(), pf.greenMax)
            b = stretch(((value shr pf.blueShift) and pf.blueMax.toLong()).toInt(), pf.blueMax)
        } else {
            val entry = colorMap[value]
            if (entry != null) {
                r = (entry.red.toDouble() / 257).toInt()
                g = (entry.green.toDouble() / 257).toInt()
                b = (entry.blue.toDouble() / 257).toInt()
            } else {
                r = 0; g = 0; b = 0
            }
        }

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun stretch(value: Int, max: Int): Int {
        return if (max == 255) value else (value * (255.0 / max)).toInt()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n < 0) throw IOException("Unexpected end of stream")
            offset += n
        }
    }

    /**
     * InputStream adapter over a session-scoped Inflater. Each ZLIB/ZRLE rect
     * feeds its compressed bytes via [addCompressed]; the consumer reads
     * decompressed bytes on demand. The inflater state persists across rects
     * (mandatory for ZRLE; also correct for ZLIB per RFC 6143 §7.7.5).
     */
    private class InflaterReader(private val inflater: Inflater) : InputStream() {
        private val buf = ByteArray(8192)
        private var pos = 0
        private var limit = 0

        fun addCompressed(compressed: ByteArray) {
            inflater.setInput(compressed)
        }

        override fun read(): Int {
            if (pos >= limit) refill()
            return buf[pos++].toInt() and 0xFF
        }

        override fun read(dst: ByteArray, off: Int, n: Int): Int {
            if (n == 0) return 0
            if (pos >= limit) refill()
            val avail = minOf(n, limit - pos)
            System.arraycopy(buf, pos, dst, off, avail)
            pos += avail
            return avail
        }

        private fun refill() {
            pos = 0
            limit = 0
            while (limit == 0) {
                limit = inflater.inflate(buf)
                if (limit == 0) {
                    if (inflater.finished() || inflater.needsInput()) {
                        throw IOException("Inflater underflow: no more input available")
                    }
                }
            }
        }
    }
}
