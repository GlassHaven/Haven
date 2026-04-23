package sh.haven.core.vnc.rendering

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.haven.core.vnc.ColorDepth
import sh.haven.core.vnc.VncConfig
import sh.haven.core.vnc.VncSession
import sh.haven.core.vnc.protocol.Encoding
import sh.haven.core.vnc.protocol.FramebufferUpdate
import sh.haven.core.vnc.protocol.PixelFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater

/**
 * Drives the ZRLE decoder with hand-crafted rectangles. Verifies the three
 * most common subencodings (solid, raw CPIXEL, packed palette) paint the
 * expected pixels, by catching the final [Bitmap] via onScreenUpdate.
 *
 * The pixel format here is BPP_24_TRUE (our default), where CPIXEL is 3 bytes
 * in LE order [B, G, R] because the unused byte is the MSB.
 */
@RunWith(RobolectricTestRunner::class)
class FramebufferZrleTest {

    private lateinit var session: VncSession
    private lateinit var fb: Framebuffer
    private var lastBitmap: Bitmap? = null

    @Before
    fun setUp() {
        val config = VncConfig().apply {
            colorDepth = ColorDepth.BPP_24_TRUE
            onScreenUpdate = { bmp -> lastBitmap = bmp }
        }
        session = VncSession(config, InputStream.nullInputStream(), ByteArrayOutputStream())
        session.pixelFormat = PixelFormat(
            bitsPerPixel = 32, depth = 24, bigEndian = false, trueColor = true,
            redMax = 255, greenMax = 255, blueMax = 255,
            redShift = 16, greenShift = 8, blueShift = 0,
        )
        session.framebufferWidth = 128
        session.framebufferHeight = 128
        fb = Framebuffer(session)
    }

    @Test
    fun `solid tile paints whole rect one color`() {
        val red = 0xFFFF0000.toInt()
        val bytes = zrleRectBytes(x = 0, y = 0, w = 4, h = 4) {
            // One 4x4 tile. subencoding=1 (solid), cpixel = [B,G,R] = [0,0,255]
            writeByte(1)
            writeBytes(byteArrayOf(0, 0, 0xFF.toByte()))
        }

        feed(bytes)
        val bmp = lastBitmap!!
        for (y in 0 until 4) for (x in 0 until 4) {
            assertEquals("pixel ($x,$y)", red, bmp.getPixel(x, y))
        }
    }

    @Test
    fun `raw CPIXEL tile paints each pixel independently`() {
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val bytes = zrleRectBytes(x = 0, y = 0, w = 2, h = 2) {
            writeByte(0) // subencoding 0 = raw CPIXEL stream
            // four pixels: green, blue, blue, green (row-major)
            writeBytes(byteArrayOf(0, 0xFF.toByte(), 0)) // green
            writeBytes(byteArrayOf(0xFF.toByte(), 0, 0)) // blue
            writeBytes(byteArrayOf(0xFF.toByte(), 0, 0)) // blue
            writeBytes(byteArrayOf(0, 0xFF.toByte(), 0)) // green
        }

        feed(bytes)
        val bmp = lastBitmap!!
        assertEquals(green, bmp.getPixel(0, 0))
        assertEquals(blue, bmp.getPixel(1, 0))
        assertEquals(blue, bmp.getPixel(0, 1))
        assertEquals(green, bmp.getPixel(1, 1))
    }

    @Test
    fun `packed palette tile unpacks 1-bit indices`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        // 2x2 tile, palette of 2 colors, indices packed 1 bit/pixel, row-padded to byte.
        // Row 0: 10xx_xxxx (index 1, 0)
        // Row 1: 01xx_xxxx (index 0, 1)
        val bytes = zrleRectBytes(x = 0, y = 0, w = 2, h = 2) {
            writeByte(2) // subencoding 2 = packed palette, size=2
            // palette[0] = black
            writeBytes(byteArrayOf(0, 0, 0))
            // palette[1] = white
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
            writeByte(0b10000000) // row 0
            writeByte(0b01000000) // row 1
        }

        feed(bytes)
        val bmp = lastBitmap!!
        assertEquals(white, bmp.getPixel(0, 0))
        assertEquals(black, bmp.getPixel(1, 0))
        assertEquals(black, bmp.getPixel(0, 1))
        assertEquals(white, bmp.getPixel(1, 1))
    }

    // ---- helpers ----

    private fun feed(rectBytes: ByteArray) {
        // Simulate one FramebufferUpdate containing one ZRLE rect.
        // FramebufferUpdate header is "type + padding + numRects" — all
        // already consumed in the real path; here processUpdate takes the
        // post-header update object so we just pass numberOfRectangles=1
        // and then construct the rectangle's bytes on the wire after.
        val header = ByteArrayOutputStream().apply {
            val d = DataOutputStream(this)
            d.writeShort(0) // x — overwritten by rect fields
            d.writeShort(0)
            d.writeShort(0)
            d.writeShort(0)
            d.writeInt(Encoding.ZRLE.code)
        }.toByteArray()
        // For the real test we bypass the FramebufferUpdate rectangle decode
        // and let processUpdate pull the full rect from inputStream.
        session.inputStream = ByteArrayInputStream(rectBytes)
        fb.processUpdate(FramebufferUpdate(numberOfRectangles = 1))
    }

    /**
     * Build the wire bytes for a single ZRLE rectangle: rect header + 4-byte
     * compressed length + zlib-compressed tile payload produced by [payload].
     */
    private fun zrleRectBytes(
        x: Int, y: Int, w: Int, h: Int, payload: ZrleWriter.() -> Unit,
    ): ByteArray {
        val uncompressed = ByteArrayOutputStream().also { ZrleWriter(it).payload() }.toByteArray()
        val deflater = Deflater()
        deflater.setInput(uncompressed)
        deflater.finish()
        val compressedBuf = ByteArray(uncompressed.size * 2 + 256)
        val compressedLen = deflater.deflate(compressedBuf)
        deflater.end()

        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(x)
        d.writeShort(y)
        d.writeShort(w)
        d.writeShort(h)
        d.writeInt(Encoding.ZRLE.code)
        d.writeInt(compressedLen)
        d.write(compressedBuf, 0, compressedLen)
        return out.toByteArray()
    }

    private class ZrleWriter(private val out: OutputStream) {
        fun writeByte(b: Int) {
            out.write(b and 0xFF)
        }

        fun writeBytes(bytes: ByteArray) {
            out.write(bytes)
        }
    }
}
