package sh.haven.feature.terminal

import android.view.KeyCharacterMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.ui.PhysicalDeadKeyComposer

class PhysicalDeadKeyComposerTest {

    @Test
    fun `explicit COMBINING_ACCENT acute and combining acute compose with vowels and Shift`() {
        val composer = PhysicalDeadKeyComposer()

        // Standard AOSP keyboard_layout_spanish.kcm: COMBINING_ACCENT | 0x00B4 (< 0)
        val deadAcute = KeyCharacterMap.COMBINING_ACCENT or 0x00B4
        assertTrue(deadAcute < 0)

        val step1 = composer.onUnicodeChar(deadAcute)
        assertTrue(step1.handled)
        assertEquals(emptyList<Int>(), step1.codePoints)

        val step2 = composer.onUnicodeChar('a'.code)
        assertTrue(step2.handled)
        assertEquals(listOf('á'.code), step2.codePoints)

        // Shift between dead key and uppercase vowel preserves pendingDeadChar
        assertTrue(composer.onUnicodeChar(deadAcute).codePoints.isEmpty())
        val upper = composer.onUnicodeChar('A'.code)
        assertEquals(listOf('Á'.code), upper.codePoints)

        // COMBINING_ACCENT | U+0301 (combining acute mark form)
        val deadCombiningAcute = KeyCharacterMap.COMBINING_ACCENT or 0x0301
        assertTrue(composer.onUnicodeChar(deadCombiningAcute).codePoints.isEmpty())
        assertEquals(listOf('é'.code), composer.onUnicodeChar('e'.code).codePoints)
    }

    @Test
    fun `spacing acute and diaeresis without COMBINING_ACCENT compose with next base char`() {
        val composer = PhysicalDeadKeyComposer()

        // U+00B4 ACUTE ACCENT without COMBINING_ACCENT
        assertEquals(emptyList<Int>(), composer.onUnicodeChar(0x00B4).codePoints)
        assertEquals(listOf('ó'.code), composer.onUnicodeChar('o'.code).codePoints)

        // U+00A8 DIAERESIS without COMBINING_ACCENT
        assertEquals(emptyList<Int>(), composer.onUnicodeChar(0x00A8).codePoints)
        assertEquals(listOf('ü'.code), composer.onUnicodeChar('u'.code).codePoints)

        // Raw U+0303 COMBINING TILDE without COMBINING_ACCENT
        assertEquals(emptyList<Int>(), composer.onUnicodeChar(0x0303).codePoints)
        assertEquals(listOf('ñ'.code), composer.onUnicodeChar('n'.code).codePoints)
    }

    @Test
    fun `ascii grave caret tilde apostrophe quote only buffer when COMBINING_ACCENT is set`() {
        val composer = PhysicalDeadKeyComposer()

        // Plain ASCII on US/non-dead layouts must emit immediately
        for (ch in charArrayOf('`', '^', '~', '\'', '"')) {
            val res = composer.onUnicodeChar(ch.code)
            assertTrue(res.handled)
            assertEquals(listOf(ch.code), res.codePoints)
        }

        // Explicit COMBINING_ACCENT on ASCII grave/caret/tilde composes cleanly
        assertTrue(composer.onUnicodeChar(KeyCharacterMap.COMBINING_ACCENT or '`'.code).codePoints.isEmpty())
        assertEquals(listOf('à'.code), composer.onUnicodeChar('a'.code).codePoints)

        assertTrue(composer.onUnicodeChar(KeyCharacterMap.COMBINING_ACCENT or '^'.code).codePoints.isEmpty())
        assertEquals(listOf('ô'.code), composer.onUnicodeChar('o'.code).codePoints)

        assertTrue(composer.onUnicodeChar(KeyCharacterMap.COMBINING_ACCENT or '~'.code).codePoints.isEmpty())
        assertEquals(listOf('ñ'.code), composer.onUnicodeChar('n'.code).codePoints)
    }

    @Test
    fun `double tap space non-combinable base and clear behave like standard dead keys`() {
        val composer = PhysicalDeadKeyComposer()
        val deadAcute = KeyCharacterMap.COMBINING_ACCENT or 0x00B4

        // Double-tap dead key -> single spacing ´
        assertTrue(composer.onUnicodeChar(deadAcute).codePoints.isEmpty())
        assertEquals(listOf(0x00B4), composer.onUnicodeChar(deadAcute).codePoints)

        // Dead key + Space -> single spacing ´
        assertTrue(composer.onUnicodeChar(0x00B4).codePoints.isEmpty())
        assertEquals(listOf(0x00B4), composer.onUnicodeChar(' '.code).codePoints)

        // Dead key + non-combinable 'q' -> ´ then q
        assertTrue(composer.onUnicodeChar(deadAcute).codePoints.isEmpty())
        assertEquals(listOf(0x00B4, 'q'.code), composer.onUnicodeChar('q'.code).codePoints)

        // Special/shortcut interruption clears pending dead key
        assertTrue(composer.onUnicodeChar(deadAcute).codePoints.isEmpty())
        composer.clear()
        assertEquals(listOf('a'.code), composer.onUnicodeChar('a'.code).codePoints)

        // Non-character key (unicodeChar == 0) is not handled and clears pending dead key
        assertTrue(composer.onUnicodeChar(deadAcute).codePoints.isEmpty())
        val zeroRes = composer.onUnicodeChar(0)
        assertFalse(zeroRes.handled)
        assertEquals(listOf('a'.code), composer.onUnicodeChar('a'.code).codePoints)
    }
}
