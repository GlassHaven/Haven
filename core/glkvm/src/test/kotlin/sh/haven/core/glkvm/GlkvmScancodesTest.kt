package sh.haven.core.glkvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlkvmScancodesTest {

    @Test
    fun `character positions map 1 to 1`() {
        assertEquals("KeyA", GlkvmScancodes.codeFor(0x1E))
        assertEquals("KeyZ", GlkvmScancodes.codeFor(0x2C))
        assertEquals("KeyQ", GlkvmScancodes.codeFor(0x10))
        assertEquals("Digit1", GlkvmScancodes.codeFor(0x02))
        assertEquals("Digit0", GlkvmScancodes.codeFor(0x0B))
        assertEquals("Space", GlkvmScancodes.codeFor(0x39))
        assertEquals("Semicolon", GlkvmScancodes.codeFor(0x27))
    }

    @Test
    fun `plain nav-cluster values mean numpad`() {
        // Without the E0 flag those bases are the numpad keys.
        assertEquals("Numpad8", GlkvmScancodes.codeFor(0x48))
        assertEquals("Numpad4", GlkvmScancodes.codeFor(0x4B))
        assertEquals("Numpad6", GlkvmScancodes.codeFor(0x4D))
        assertEquals("Numpad2", GlkvmScancodes.codeFor(0x50))
    }

    @Test
    fun `e0-flagged codes are the extended keys`() {
        // 16-bit lane values carry 0xE0 in the high byte (SC_ALTGR_PUBLIC = 0xE038 style).
        assertEquals("ArrowUp", GlkvmScancodes.codeFor(0xE048))
        assertEquals("ArrowDown", GlkvmScancodes.codeFor(0xE050))
        assertEquals("ArrowLeft", GlkvmScancodes.codeFor(0xE04B))
        assertEquals("ArrowRight", GlkvmScancodes.codeFor(0xE04D))
        assertEquals("Delete", GlkvmScancodes.codeFor(0xE053))
        assertEquals("NumpadDivide", GlkvmScancodes.codeFor(0xE035))
        assertEquals("AltRight", GlkvmScancodes.codeFor(0xE038))
        assertEquals("NumpadEnter", GlkvmScancodes.codeFor(0xE01C))
        assertEquals("ControlRight", GlkvmScancodes.codeFor(0xE01D))
        assertEquals("MetaLeft", GlkvmScancodes.codeFor(0xE05B))
        assertEquals("MetaRight", GlkvmScancodes.codeFor(0xE05C))
        assertEquals("ContextMenu", GlkvmScancodes.codeFor(0xE05D))
    }

    @Test
    fun `lock keys map to position codes`() {
        assertEquals("CapsLock", GlkvmScancodes.codeFor(0x3A))
        assertEquals("NumLock", GlkvmScancodes.codeFor(0x45))
        assertEquals("ScrollLock", GlkvmScancodes.codeFor(0x46))
    }

    @Test
    fun `iso 102nd key and function row`() {
        assertEquals("IntlBackslash", GlkvmScancodes.codeFor(0x56))
        assertEquals("F1", GlkvmScancodes.codeFor(0x3B))
        assertEquals("F10", GlkvmScancodes.codeFor(0x44))
        assertEquals("F11", GlkvmScancodes.codeFor(0x57))
        assertEquals("F12", GlkvmScancodes.codeFor(0x58))
    }

    @Test
    fun `unknown codes drop`() {
        assertNull(GlkvmScancodes.codeFor(0x00))
        assertNull(GlkvmScancodes.codeFor(0x70))
        assertNull(GlkvmScancodes.codeFor(0xE0FF))
    }
}