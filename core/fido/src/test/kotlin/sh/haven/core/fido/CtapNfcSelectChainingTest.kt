package sh.haven.core.fido

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * SELECT must drain ISO 7816-4 "more data available" (SW=61xx) via GET
 * RESPONSE instead of failing on it. The Nitrokey 3A answers SELECT with
 * SW=6106, which the old strict 9000-only check rejected as fatal (#623).
 */
class CtapNfcSelectChainingTest {

    private fun sw(hi: Int, lo: Int) = byteArrayOf(hi.toByte(), lo.toByte())

    @Test
    fun `select succeeds when the applet answers 9000 directly`() {
        var calls = 0
        CtapNfcTransport.selectApplet { apdu ->
            calls++
            assertEquals(0xA4.toByte(), apdu[1]) // INS_SELECT
            sw(0x90, 0x00)
        }
        assertEquals(1, calls)
    }

    @Test
    fun `select drains 61xx chaining via GET RESPONSE instead of failing`() {
        val fci = ByteArray(6) { 0x61.toByte() }
        val calls = mutableListOf<ByteArray>()
        CtapNfcTransport.selectApplet { apdu ->
            calls.add(apdu)
            when (calls.size) {
                1 -> fci + sw(0x61, 0x06) // SELECT: 6 bytes more available
                2 -> {
                    assertEquals(0xC0.toByte(), apdu[1]) // INS_GET_RESPONSE
                    assertEquals(6.toByte(), apdu[4]) // Le = remaining
                    sw(0x90, 0x00) // drain complete
                }
                else -> error("unexpected extra transceive")
            }
        }
        assertEquals(2, calls.size)
    }

    @Test
    fun `select drains multi-chunk 61xx chaining`() {
        var calls = 0
        CtapNfcTransport.selectApplet { _ ->
            calls++
            when (calls) {
                1 -> ByteArray(3) + sw(0x61, 0x03)
                2 -> ByteArray(3) + sw(0x61, 0x03)
                3 -> sw(0x90, 0x00)
                else -> error("unexpected extra transceive")
            }
        }
        assertEquals(3, calls)
    }

    @Test
    fun `select fails on a genuine error status word`() {
        // 6A82 = file not found: the applet is not on this tag.
        var threw = false
        try {
            CtapNfcTransport.selectApplet { sw(0x6A, 0x82) }
        } catch (e: IOException) {
            threw = true
            assertTrue(e.message!!.contains("6a82"))
        }
        assertTrue("error SW must still be fatal", threw)
    }
}
