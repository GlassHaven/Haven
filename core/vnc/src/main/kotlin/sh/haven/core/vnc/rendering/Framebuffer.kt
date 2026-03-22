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
    private val inflater = Inflater()

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
                    Encoding.CURSOR, null -> {
                        if (rect.encoding == null) {
                            throw VncException("Unsupported encoding in rectangle")
                        }
                        skipCursor(input, rect)
                    }
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

        inflater.setInput(compressed)
        val pf = session.pixelFormat!!
        val decompressed = ByteArray(pf.bytesPerPixel * rect.width * rect.height)
        val len = inflater.inflate(decompressed)

        val decompStream = java.io.ByteArrayInputStream(decompressed, 0, len)
        renderRaw(decompStream, rect.x, rect.y, rect.width, rect.height)
    }

    // ---- Cursor (skip) ----

    private fun skipCursor(input: InputStream, rect: Rectangle) {
        val pf = session.pixelFormat!!
        val pixelBytes = pf.bytesPerPixel * rect.width * rect.height
        val maskBytes = ((rect.width + 7) / 8) * rect.height
        val d = DataInputStream(input)
        d.readFully(ByteArray(pixelBytes + maskBytes))
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
}
