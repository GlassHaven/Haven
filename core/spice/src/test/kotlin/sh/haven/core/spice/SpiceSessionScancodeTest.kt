package sh.haven.core.spice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #606: verify [spiceWireScancode] matches `spice-gtk`'s `spice_make_scancode`
 * and `spice-server`'s `inputs-channel.cpp` byte-extraction loop:
 *
 * ```cpp
 * for (i = 0; i < 4; i++) {
 *     uint8_t code = (key->code >> (i * 8)) & 0xff;
 *     if (code == 0) break;
 *     kbd_push_scan(keyboard, code);
 * }
 * ```
 */
class SpiceSessionScancodeTest {

    private fun spiceServerBytes(wireCode: UInt): List<Int> {
        val out = mutableListOf<Int>()
        for (i in 0 until 4) {
            val b = ((wireCode shr (i * 8)) and 0xFFu).toInt()
            if (b == 0) break
            out.add(b)
        }
        return out
    }

    @Test
    fun `standard make and break set bit 7 on release`() {
        // 'a' (Set-1 0x1E): make = [0x1E], break = [0x9E].
        val down = spiceWireScancode(0x1E, pressed = true)
        val up = spiceWireScancode(0x1E, pressed = false)
        assertEquals(0x1Eu, down)
        assertEquals(0x9Eu, up)
        assertEquals(listOf(0x1E), spiceServerBytes(down))
        assertEquals(listOf(0x9E), spiceServerBytes(up))
    }

    @Test
    fun `left shift make and break release cleanly instead of sticking shift`() {
        val down = spiceWireScancode(0x2A, pressed = true)
        val up = spiceWireScancode(0x2A, pressed = false)
        assertEquals(listOf(0x2A), spiceServerBytes(down))
        assertEquals(listOf(0xAA), spiceServerBytes(up))
    }

    @Test
    fun `iso 102 key and high 7-bit scancodes preserve make and break`() {
        assertEquals(listOf(0x56), spiceServerBytes(spiceWireScancode(0x56, pressed = true)))
        assertEquals(listOf(0xD6), spiceServerBytes(spiceWireScancode(0x56, pressed = false)))
        assertEquals(listOf(0x73), spiceServerBytes(spiceWireScancode(0x73, pressed = true)))
        assertEquals(listOf(0xF3), spiceServerBytes(spiceWireScancode(0x73, pressed = false)))
    }

    @Test
    fun `extended 0xE0 keys emit 0xE0 prefix byte first then make or break byte`() {
        // AltGr = 0xE038 -> wire bytes [0xE0, 0x38] on press, [0xE0, 0xB8] on release.
        val altGrDown = spiceWireScancode(0xE038, pressed = true)
        val altGrUp = spiceWireScancode(0xE038, pressed = false)
        assertEquals(0x38E0u, altGrDown)
        assertEquals(0xB8E0u, altGrUp)
        assertEquals(listOf(0xE0, 0x38), spiceServerBytes(altGrDown))
        assertEquals(listOf(0xE0, 0xB8), spiceServerBytes(altGrUp))

        // Up arrow = 0xE048 -> [0xE0, 0x48] down, [0xE0, 0xC8] up.
        assertEquals(listOf(0xE0, 0x48), spiceServerBytes(spiceWireScancode(0xE048, pressed = true)))
        assertEquals(listOf(0xE0, 0xC8), spiceServerBytes(spiceWireScancode(0xE048, pressed = false)))

        // Left Super/Win = 0xE05B -> [0xE0, 0x5B] down, [0xE0, 0xDB] up.
        assertEquals(listOf(0xE0, 0x5B), spiceServerBytes(spiceWireScancode(0xE05B, pressed = true)))
        assertEquals(listOf(0xE0, 0xDB), spiceServerBytes(spiceWireScancode(0xE05B, pressed = false)))
    }
}
