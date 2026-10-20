package sh.haven.feature.terminal

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * 16-entry ANSI palette derived from a live Material 3 [ColorScheme] (#139) —
 * the palette behind the MATERIAL_YOU terminal scheme, so SGR-coloured text
 * tracks the wallpaper-driven theme the same way the terminal's default
 * fg/bg already do (TerminalScreen reads surface / onSurface for it).
 *
 * Neutral slots (black / white / greys) come from the theme's surface roles,
 * so their tints follow the wallpaper. Chroma slots keep recognisable
 * standard hues (red, green, yellow, blue, magenta): terminals encode
 * meaning in SGR colours (diffs, mutt, git), and remapping those to the
 * wallpaper's hue is the regression #407 shipped the opt-in palette toggle
 * to avoid. Cyan breaks the rule deliberately — it takes the theme's
 * tertiary complement, and bright cyan takes the primary accent, the way
 * Material-You terminals (Rin) map the accent into the palette.
 */
fun materialYouAnsiPalette(cs: ColorScheme): IntArray {
    // Branch on the live theme's tone rather than an isDarkTheme flag, so
    // the palette matches exactly the scheme being read.
    val dark = cs.surface.luminance() < 0.5f
    return if (dark) {
        intArrayOf(
            cs.surfaceContainerLow.toArgb(),      // 0  black
            cs.error.toArgb(),                    // 1  red
            0xFF4CAF50.toInt(),                   // 2  green
            0xFFFFEB3B.toInt(),                   // 3  yellow
            0xFF2196F3.toInt(),                   // 4  blue
            0xFF9C27B0.toInt(),                   // 5  magenta
            cs.tertiary.toArgb(),                 // 6  cyan
            cs.onSurfaceVariant.toArgb(),         // 7  white
            cs.outlineVariant.toArgb(),           // 8  bright black
            lighten(cs.error, 0.35f),             // 9  bright red
            0xFFB9F6CA.toInt(),                   // 10 bright green
            0xFFFFF59D.toInt(),                   // 11 bright yellow
            0xFF82B1FF.toInt(),                   // 12 bright blue
            0xFFEA80FC.toInt(),                   // 13 bright magenta
            cs.primary.toArgb(),                  // 14 bright cyan
            cs.surfaceBright.toArgb(),            // 15 bright white
        )
    } else {
        intArrayOf(
            cs.inverseSurface.toArgb(),           // 0  black
            0xFFD32F2F.toInt(),                   // 1  red
            0xFF388E3C.toInt(),                   // 2  green
            0xFFFBC02D.toInt(),                   // 3  yellow
            0xFF1976D2.toInt(),                   // 4  blue
            0xFF7B1FA2.toInt(),                   // 5  magenta
            cs.tertiary.toArgb(),                 // 6  cyan
            cs.onSurfaceVariant.toArgb(),         // 7  white
            cs.outline.toArgb(),                  // 8  bright black
            lighten(Color(0xFFD32F2F), 0.35f),    // 9  bright red
            lighten(Color(0xFF388E3C), 0.35f),    // 10 bright green
            lighten(Color(0xFFFBC02D), 0.35f),    // 11 bright yellow
            lighten(Color(0xFF1976D2), 0.35f),    // 12 bright blue
            lighten(Color(0xFF7B1FA2), 0.35f),    // 13 bright magenta
            cs.primary.toArgb(),                  // 14 bright cyan
            Color.White.toArgb(),                 // 15 bright white
        )
    }
}

private fun lighten(color: Color, factor: Float): Int = Color(
    red = color.red + (1f - color.red) * factor,
    green = color.green + (1f - color.green) * factor,
    blue = color.blue + (1f - color.blue) * factor,
).toArgb()