package sh.haven.core.rdp

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #504: the KLID announced at connect must follow the device locale. The
 * reporter's session announced US English on a Polish phone — harmless
 * against VirtualBox (raw scancodes), wrong against every server that
 * builds the session layout from the announcement.
 */
class KeyboardLayoutKlidTest {

    @Test
    fun `polish maps to the polish programmers layout`() {
        assertEquals(0x0415u, keyboardLayoutKlid(Locale.forLanguageTag("pl-PL")))
    }

    @Test
    fun `english defaults to US but GB gets the UK layout`() {
        assertEquals(0x0409u, keyboardLayoutKlid(Locale.forLanguageTag("en-US")))
        assertEquals(0x0809u, keyboardLayoutKlid(Locale.forLanguageTag("en-GB")))
    }

    @Test
    fun `country variants pick their own layouts`() {
        assertEquals(0x0416u, keyboardLayoutKlid(Locale.forLanguageTag("pt-BR")))
        assertEquals(0x0816u, keyboardLayoutKlid(Locale.forLanguageTag("pt-PT")))
        assertEquals(0x0807u, keyboardLayoutKlid(Locale.forLanguageTag("de-CH")))
        assertEquals(0x0C0Cu, keyboardLayoutKlid(Locale.forLanguageTag("fr-CA")))
    }

    @Test
    fun `unknown languages fall back to US — the pre-504 behaviour`() {
        assertEquals(0x0409u, keyboardLayoutKlid(Locale.forLanguageTag("sw-KE")))
        assertEquals(0x0409u, keyboardLayoutKlid(Locale.ROOT))
    }

    @Test
    fun `explicit layout tag overrides fallback locale and auto defers to fallback`() {
        val polishLocale = Locale.forLanguageTag("pl-PL")
        assertEquals(0x0415u, keyboardLayoutKlidForTag(null, polishLocale))
        assertEquals(0x0415u, keyboardLayoutKlidForTag("", polishLocale))
        assertEquals(0x0415u, keyboardLayoutKlidForTag("auto", polishLocale))

        // GrapheneOS phone in en-US connecting to a Spanish or German SPICE/RDP guest (#606)
        val usLocale = Locale.forLanguageTag("en-US")
        assertEquals(0x040Au, keyboardLayoutKlidForTag("es-ES", usLocale))
        assertEquals(0x080Au, keyboardLayoutKlidForTag("es-419", usLocale))
        assertEquals(0x0407u, keyboardLayoutKlidForTag("de-DE", usLocale))
        assertEquals(0x0807u, keyboardLayoutKlidForTag("de-CH", usLocale))
        assertEquals(0x040Cu, keyboardLayoutKlidForTag("fr-FR", usLocale))
        assertEquals(0x080Cu, keyboardLayoutKlidForTag("fr-BE", usLocale))
        assertEquals(0x100Cu, keyboardLayoutKlidForTag("fr-CH", usLocale))
        assertEquals(0x0410u, keyboardLayoutKlidForTag("it-IT", usLocale))
        assertEquals(0x0816u, keyboardLayoutKlidForTag("pt-PT", usLocale))
        assertEquals(0x0416u, keyboardLayoutKlidForTag("pt-BR", usLocale))
        assertEquals(0x041Du, keyboardLayoutKlidForTag("sv-SE", usLocale))
        assertEquals(0x0809u, keyboardLayoutKlidForTag("en-GB", polishLocale))
        assertEquals(0x0409u, keyboardLayoutKlidForTag("en-US", polishLocale))
    }
}
