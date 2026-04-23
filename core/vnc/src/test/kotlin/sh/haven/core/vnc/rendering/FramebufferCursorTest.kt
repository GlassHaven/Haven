package sh.haven.core.vnc.rendering

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/**
 * Drives the Cursor pseudo-encoding path with a 2x2 cursor: one opaque pixel
 * and three transparent ones, then asserts the bitmap delivered via
 * onCursorUpdate reflects both color and mask.
 */
@RunWith(RobolectricTestRunner::class)
class FramebufferCursorTest {

    private lateinit var session: VncSession
    private lateinit var fb: Framebuffer
    private var cursor: Bitmap? = null
    private var hotspot: Pair<Int, Int>? = null

    @Before
    fun setUp() {
        val config = VncConfig().apply {
            colorDepth = ColorDepth.BPP_24_TRUE
            onCursorUpdate = { bmp, hx, hy ->
                cursor = bmp
                hotspot = hx to hy
            }
        }
        session = VncSession(config, InputStream.nullInputStream(), ByteArrayOutputStream())
        session.pixelFormat = PixelFormat(
            bitsPerPixel = 32, depth = 24, bigEndian = false, trueColor = true,
            redMax = 255, greenMax = 255, blueMax = 255,
            redShift = 16, greenShift = 8, blueShift = 0,
        )
        session.framebufferWidth = 64
        session.framebufferHeight = 64
        fb = Framebuffer(session)
    }

    @Test
    fun `cursor pseudo-encoding produces masked ARGB bitmap with hotspot`() {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        val hotX = 3
        val hotY = 5
        d.writeShort(hotX)       // rect.x = hotspot x
        d.writeShort(hotY)       // rect.y = hotspot y
        d.writeShort(2)          // width
        d.writeShort(2)          // height
        d.writeInt(Encoding.CURSOR.code)

        // 4 pixels (BGRX little-endian, 4 bytes each): red, green, blue, white
        d.write(byteArrayOf(0, 0, 0xFF.toByte(), 0)) // red  (B=0, G=0, R=255, X=0)
        d.write(byteArrayOf(0, 0xFF.toByte(), 0, 0)) // green
        d.write(byteArrayOf(0xFF.toByte(), 0, 0, 0)) // blue
        d.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0)) // white

        // Mask: 2 rows, each padded to 1 byte. MSB-first within a byte.
        // Row 0: 10xx_xxxx -> pixel(0,0) opaque, pixel(1,0) transparent
        // Row 1: 01xx_xxxx -> pixel(0,1) transparent, pixel(1,1) opaque
        d.writeByte(0b10000000)
        d.writeByte(0b01000000)

        session.inputStream = ByteArrayInputStream(out.toByteArray())
        fb.processUpdate(FramebufferUpdate(numberOfRectangles = 1))

        val bmp = cursor
        assertNotNull("Cursor bitmap should have been delivered", bmp)
        assertEquals(hotX to hotY, hotspot)

        // Opaque pixels keep their color.
        assertEquals(0xFFFF0000.toInt(), bmp!!.getPixel(0, 0)) // red
        assertEquals(0xFFFFFFFF.toInt(), bmp.getPixel(1, 1))   // white
        // Transparent pixels have alpha 0.
        assertEquals(0, bmp.getPixel(1, 0))
        assertEquals(0, bmp.getPixel(0, 1))
    }
}
