package sh.haven.feature.terminal

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #139: the MATERIAL_YOU scheme's 16-entry ANSI palette is derived from the
 * live Material 3 colour scheme instead of the enum's static fallback.
 */
class TerminalAnsiPaletteTest {

    @Test
    fun `dark scheme derives sixteen opaque entries`() {
        val cs = darkColorScheme()
        val palette = materialYouAnsiPalette(cs)
        assertEquals(16, palette.size)
        assertTrue(palette.all { (it ushr 24) == 0xFF })
        assertTrue(palette.distinct().size >= 10)
    }

    @Test
    fun `chroma slots map to theme roles`() {
        val cs = darkColorScheme()
        val palette = materialYouAnsiPalette(cs)
        assertEquals(cs.error.toArgb(), palette[1])
        assertEquals(cs.tertiary.toArgb(), palette[6])
        assertEquals(cs.primary.toArgb(), palette[14])
    }

    @Test
    fun `light and dark branches differ`() {
        val dark = materialYouAnsiPalette(darkColorScheme())
        val light = materialYouAnsiPalette(lightColorScheme())
        assertFalse(dark.contentEquals(light))
    }
}