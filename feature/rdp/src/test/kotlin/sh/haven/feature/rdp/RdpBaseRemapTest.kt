package sh.haven.feature.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #504 follow-up: base-remapped layouts (German QWERTZ, French AZERTY, UK).
 * The announced KLID makes the server resolve scancodes through that layout,
 * so each layout carries its own full char→position table; chars needing a
 * dead-key composition fall to unicode, never to the US table.
 */
class RdpBaseRemapTest {

    private data class Typed(val events: List<Pair<Int, Boolean>>, val unicode: Boolean)

    private fun type(ch: Char, klid: UInt): Typed {
        val events = mutableListOf<Pair<Int, Boolean>>()
        var unicode = false
        typeRdpChar(
            ch = ch,
            sendKey = { sc, pressed -> events.add(sc to pressed) },
            sendUnicode = { unicode = true },
            layoutKlid = klid,
        )
        return Typed(events, unicode)
    }

    private fun plainTap(sc: Int) = listOf(sc to true, sc to false)

    private fun shiftTap(sc: Int) = listOf(
        SC_SHIFT_L_PUBLIC to true, sc to true, sc to false, SC_SHIFT_L_PUBLIC to false,
    )

    private fun altGrTap(sc: Int) = listOf(
        SC_ALTGR_PUBLIC to true, sc to true, sc to false, SC_ALTGR_PUBLIC to false,
    )

    // --- German QWERTZ ---

    @Test
    fun `german swaps y and z`() {
        assertEquals(plainTap(0x15), type('z', GERMAN_QWERTZ_KLID).events)
        assertEquals(plainTap(0x2C), type('y', GERMAN_QWERTZ_KLID).events)
        assertEquals(shiftTap(0x15), type('Z', GERMAN_QWERTZ_KLID).events)
    }

    @Test
    fun `german umlauts sit on their own keys`() {
        assertEquals(plainTap(0x28), type('ä', GERMAN_QWERTZ_KLID).events)
        assertEquals(shiftTap(0x28), type('Ä', GERMAN_QWERTZ_KLID).events)
        assertEquals(plainTap(0x0C), type('ß', GERMAN_QWERTZ_KLID).events)
    }

    @Test
    fun `german shifted digit row symbols`() {
        assertEquals(shiftTap(0x08), type('/', GERMAN_QWERTZ_KLID).events)
        assertEquals(shiftTap(0x0B), type('=', GERMAN_QWERTZ_KLID).events)
        assertEquals(shiftTap(0x0C), type('?', GERMAN_QWERTZ_KLID).events)
    }

    @Test
    fun `german altgr characters`() {
        assertEquals(altGrTap(0x10), type('@', GERMAN_QWERTZ_KLID).events)
        assertEquals(altGrTap(0x12), type('€', GERMAN_QWERTZ_KLID).events)
        assertEquals(altGrTap(0x09), type('[', GERMAN_QWERTZ_KLID).events)
        assertEquals(altGrTap(0x0C), type('\\', GERMAN_QWERTZ_KLID).events)
    }

    @Test
    fun `german dead-key chars fall to unicode, not US positions`() {
        for (ch in charArrayOf('`', '^', '´')) {
            val typed = type(ch, GERMAN_QWERTZ_KLID)
            assertTrue("$ch should use unicode", typed.unicode)
            assertTrue("$ch must not emit scancodes", typed.events.isEmpty())
        }
    }

    // --- French AZERTY ---

    @Test
    fun `french remaps a q z w m`() {
        assertEquals(plainTap(0x10), type('a', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x1E), type('q', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x11), type('z', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x2C), type('w', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x27), type('m', FRENCH_AZERTY_KLID).events)
    }

    @Test
    fun `french digits need shift and accents ride the plain row`() {
        assertEquals(shiftTap(0x02), type('1', FRENCH_AZERTY_KLID).events)
        assertEquals(shiftTap(0x0B), type('0', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x03), type('é', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x08), type('è', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x0A), type('ç', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x0B), type('à', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x28), type('ù', FRENCH_AZERTY_KLID).events)
    }

    @Test
    fun `french altgr characters`() {
        assertEquals(altGrTap(0x0B), type('@', FRENCH_AZERTY_KLID).events)
        assertEquals(altGrTap(0x0A), type('^', FRENCH_AZERTY_KLID).events)
        assertEquals(altGrTap(0x12), type('€', FRENCH_AZERTY_KLID).events)
    }

    @Test
    fun `french punctuation trio`() {
        assertEquals(plainTap(0x32), type(',', FRENCH_AZERTY_KLID).events)
        assertEquals(shiftTap(0x32), type('?', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x35), type('!', FRENCH_AZERTY_KLID).events)
    }

    @Test
    fun `french capital accents and dead keys fall to unicode`() {
        for (ch in charArrayOf('É', 'È', 'Ç', 'À', 'Ù', '~', '`')) {
            val typed = type(ch, FRENCH_AZERTY_KLID)
            assertTrue("$ch should use unicode", typed.unicode)
            assertTrue("$ch must not emit scancodes", typed.events.isEmpty())
        }
    }

    // --- UK ---

    @Test
    fun `uk swaps at and quote, keeps letters at US positions`() {
        assertEquals(shiftTap(0x28), type('@', UK_ENGLISH_KLID).events)
        assertEquals(shiftTap(0x03), type('"', UK_ENGLISH_KLID).events)
        assertEquals(plainTap(0x2B), type('#', UK_ENGLISH_KLID).events)
        assertEquals(plainTap(SC_ISO_102), type('\\', UK_ENGLISH_KLID).events)
        assertEquals(shiftTap(0x04), type('£', UK_ENGLISH_KLID).events)
        assertEquals(plainTap(0x1E), type('a', UK_ENGLISH_KLID).events)
    }

    // --- Regressions on the existing paths ---

    @Test
    fun `us default keeps the pre-existing mapping`() {
        assertEquals(plainTap(0x2C), type('z', 0x0409u).events)
        assertEquals(shiftTap(0x03), type('@', 0x0409u).events)
    }

    @Test
    fun `polish overlay is untouched by the remap layer`() {
        val typed = type('ą', POLISH_PROGRAMMERS_KLID)
        assertEquals(
            listOf(
                SC_ALTGR_PUBLIC to true,
                0x1E to true, 0x1E to false,
                SC_ALTGR_PUBLIC to false,
            ),
            typed.events,
        )
    }

    @Test
    fun `control keys work in remapped layouts`() {
        assertEquals(plainTap(0x39), type(' ', GERMAN_QWERTZ_KLID).events)
        assertEquals(plainTap(0x1C), type('\n', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x0F), type('\t', SPANISH_KLID).events)
    }

    // --- Spanish (es-ES & es-419, #606) ---

    @Test
    fun `spanish maps ntilde and @empanadablues punctuation vector cleanly`() {
        // Exact keys reported broken in #606: ñ/Ñ, @#€%&-+() *"':;!?
        assertEquals(plainTap(0x27), type('ñ', SPANISH_KLID).events)
        assertEquals(shiftTap(0x27), type('Ñ', SPANISH_KLID).events)
        assertEquals(altGrTap(0x03), type('@', SPANISH_KLID).events)
        assertEquals(altGrTap(0x04), type('#', SPANISH_KLID).events)
        assertEquals(altGrTap(0x12), type('€', SPANISH_KLID).events)
        assertEquals(shiftTap(0x06), type('%', SPANISH_KLID).events)
        assertEquals(shiftTap(0x07), type('&', SPANISH_KLID).events)
        assertEquals(plainTap(0x35), type('-', SPANISH_KLID).events)
        assertEquals(plainTap(0x1B), type('+', SPANISH_KLID).events)
        assertEquals(shiftTap(0x09), type('(', SPANISH_KLID).events)
        assertEquals(shiftTap(0x0A), type(')', SPANISH_KLID).events)
        assertEquals(shiftTap(0x1B), type('*', SPANISH_KLID).events)
        assertEquals(shiftTap(0x03), type('"', SPANISH_KLID).events)
        assertEquals(plainTap(0x0C), type('\'', SPANISH_KLID).events)
        assertEquals(shiftTap(0x34), type(':', SPANISH_KLID).events)
        assertEquals(shiftTap(0x33), type(';', SPANISH_KLID).events)
        assertEquals(shiftTap(0x02), type('!', SPANISH_KLID).events)
        assertEquals(shiftTap(0x0C), type('?', SPANISH_KLID).events)
        assertEquals(plainTap(0x0D), type('¡', SPANISH_KLID).events)
        assertEquals(shiftTap(0x0D), type('¿', SPANISH_KLID).events)

        // Dead keys ` ^ ´ ¨ must fall through to unicode rather than pressing 0x1A/0x28.
        for (ch in charArrayOf('`', '^', '´', '¨')) {
            val typed = type(ch, SPANISH_KLID)
            assertTrue("$ch should use unicode on es-ES", typed.unicode)
            assertTrue("$ch must not emit dead-key scancodes on es-ES", typed.events.isEmpty())
        }
    }

    @Test
    fun `latin american spanish maps @ to AltGr+Q and braces to direct keys`() {
        assertEquals(plainTap(0x27), type('ñ', LATIN_AMERICAN_SPANISH_KLID).events)
        assertEquals(altGrTap(0x10), type('@', LATIN_AMERICAN_SPANISH_KLID).events)
        assertEquals(plainTap(0x28), type('{', LATIN_AMERICAN_SPANISH_KLID).events)
        assertEquals(shiftTap(0x28), type('[', LATIN_AMERICAN_SPANISH_KLID).events)
        assertEquals(plainTap(0x2B), type('}', LATIN_AMERICAN_SPANISH_KLID).events)
        assertEquals(shiftTap(0x2B), type(']', LATIN_AMERICAN_SPANISH_KLID).events)
    }

    // --- Additional European & ABNT2 layouts (#606) ---

    @Test
    fun `italian swiss belgian portuguese brazilian and swedish layouts map national and symbol keys`() {
        // Italian: accented vowels on home/top rows, @ on AltGr+0x27, { on Shift+AltGr+0x1A.
        assertEquals(plainTap(0x1A), type('è', ITALIAN_KLID).events)
        assertEquals(altGrTap(0x27), type('@', ITALIAN_KLID).events)
        assertEquals(
            listOf(
                SC_ALTGR_PUBLIC to true, SC_SHIFT_L_PUBLIC to true,
                0x1A to true, 0x1A to false,
                SC_SHIFT_L_PUBLIC to false, SC_ALTGR_PUBLIC to false,
            ),
            type('{', ITALIAN_KLID).events,
        )

        // Swiss German vs Swiss French swap unshifted/shifted on 0x1A, 0x27, 0x28.
        assertEquals(plainTap(0x15), type('z', SWISS_GERMAN_KLID).events)
        assertEquals(plainTap(0x1A), type('ü', SWISS_GERMAN_KLID).events)
        assertEquals(shiftTap(0x1A), type('è', SWISS_GERMAN_KLID).events)
        assertEquals(plainTap(0x1A), type('è', SWISS_FRENCH_KLID).events)
        assertEquals(shiftTap(0x1A), type('ü', SWISS_FRENCH_KLID).events)

        // Belgian AZERTY: ! unshifted on 0x09, - on 0x0D, = on 0x35.
        assertEquals(plainTap(0x10), type('a', BELGIAN_AZERTY_KLID).events)
        assertEquals(plainTap(0x09), type('!', BELGIAN_AZERTY_KLID).events)
        assertEquals(plainTap(0x0D), type('-', BELGIAN_AZERTY_KLID).events)
        assertEquals(plainTap(0x35), type('=', BELGIAN_AZERTY_KLID).events)

        // Portuguese (PT) & Brazilian ABNT2 (BR).
        assertEquals(plainTap(0x27), type('ç', PORTUGUESE_KLID).events)
        assertEquals(altGrTap(0x08), type('{', PORTUGUESE_KLID).events)
        assertEquals(plainTap(0x27), type('ç', BRAZILIAN_ABNT2_KLID).events)
        assertEquals(altGrTap(0x10), type('/', BRAZILIAN_ABNT2_KLID).events)
        assertEquals(altGrTap(0x11), type('?', BRAZILIAN_ABNT2_KLID).events)

        // Swedish / Finnish: å ä ö on 0x1A / 0x28 / 0x27, $ on AltGr+4.
        assertEquals(plainTap(0x1A), type('å', SWEDISH_KLID).events)
        assertEquals(plainTap(0x28), type('ä', SWEDISH_KLID).events)
        assertEquals(plainTap(0x27), type('ö', SWEDISH_KLID).events)
        assertEquals(altGrTap(0x05), type('$', FINNISH_KLID).events)
    }
}
