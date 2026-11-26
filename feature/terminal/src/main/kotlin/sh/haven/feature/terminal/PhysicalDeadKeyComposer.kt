package sh.haven.feature.terminal

import android.view.KeyCharacterMap
import java.text.Normalizer

/**
 * Stateful dead-key composer for [TerminalScreen]'s Activity-level physical
 * keyboard interceptor (`KeyEventInterceptor` -> `handleLayoutAwareKeyEvent`) (#759).
 *
 * `MainActivity.dispatchKeyEvent` invokes `handleLayoutAwareKeyEvent` before
 * `termlib`'s `KeyboardHandler` sees physical key events. Previously
 * `handleLayoutAwareKeyEvent` ignored negative `COMBINING_ACCENT` codepoints
 * (`unicodeChar <= 0`) while consuming subsequent positive base letters
 * (`'a'`), and dispatched spacing dead accents (`´`, `¨`) directly to
 * `TerminalEmulator.dispatchCharacter`, bypassing `KeyboardHandler`'s dead-key
 * buffer in both Raw and Standard modes.
 */
internal class PhysicalDeadKeyComposer {

    data class Result(
        val handled: Boolean,
        val codePoints: List<Int>,
    )

    private var pendingDeadChar: Int = 0

    fun clear() {
        pendingDeadChar = 0
    }

    fun onUnicodeChar(rawUnicodeChar: Int): Result {
        if (rawUnicodeChar == 0) {
            pendingDeadChar = 0
            return NOT_HANDLED
        }

        val dead = pendingDeadChar
        val isExplicitDead = (rawUnicodeChar and KeyCharacterMap.COMBINING_ACCENT) != 0
        val masked = if (isExplicitDead) {
            rawUnicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            rawUnicodeChar
        }
        if (masked <= 0) {
            pendingDeadChar = 0
            return NOT_HANDLED
        }

        val base: Int
        if (isExplicitDead || isStandaloneDeadAccent(masked)) {
            base = deadToSpacingChar(masked)
            if (dead == 0) {
                pendingDeadChar = base
                return BUFFERED
            }
            // Consecutive dead key (e.g. double-tapping ´) falls through to
            // composeDeadAndBase so it emits the spacing accent.
        } else {
            base = masked
        }

        pendingDeadChar = 0

        if (dead != 0) {
            val composed = composeDeadAndBase(dead, base)
            return if (composed != 0) {
                Result(handled = true, codePoints = listOf(composed))
            } else {
                Result(handled = true, codePoints = listOf(deadToSpacingChar(dead), base))
            }
        }

        return Result(handled = true, codePoints = listOf(base))
    }

    companion object {
        private val NOT_HANDLED = Result(handled = false, codePoints = emptyList())
        private val BUFFERED = Result(handled = true, codePoints = emptyList())

        private fun isStandaloneDeadAccent(codePoint: Int): Boolean = when (codePoint) {
            0x00A8, // ¨ DIAERESIS
            0x00AF, // ¯ MACRON
            0x00B4, // ´ ACUTE ACCENT
            0x00B8, // ¸ CEDILLA
            0x02C6, // ˆ MODIFIER LETTER CIRCUMFLEX ACCENT
            0x02C7, // ˇ CARON
            0x02C9, // ˉ MODIFIER LETTER MACRON
            0x02D8, // ˘ BREVE
            0x02D9, // ˙ DOT ABOVE
            0x02DA, // ˚ RING ABOVE
            0x02DB, // ˛ OGONEK
            0x02DC, // ˜ SMALL TILDE
            0x02DD, // ˝ DOUBLE ACUTE ACCENT
            0x0384, // ΄ GREEK TONOS
            0x0385, // ΅ GREEK DIALYTIKA TONOS
            -> true
            in 0x0300..0x036F -> true
            else -> false
        }

        private fun deadToSpacingChar(codePoint: Int): Int = when (codePoint) {
            0x0300 -> '`'.code
            0x0301 -> 0x00B4 // ´
            0x0302 -> 0x02C6 // ˆ
            0x0303 -> 0x02DC // ˜
            0x0304 -> 0x00AF // ¯
            0x0306 -> 0x02D8 // ˘
            0x0307 -> 0x02D9 // ˙
            0x0308 -> 0x00A8 // ¨
            0x030A -> 0x02DA // ˚
            0x030B -> 0x02DD // ˝
            0x030C -> 0x02C7 // ˇ
            0x0327 -> 0x00B8 // ¸
            0x0328 -> 0x02DB // ˛
            else -> codePoint
        }

        private fun deadToCombiningMarks(dead: Int): String? = when (dead) {
            '`'.code, 0x0300 -> "\u0300"
            '\''.code, 0x00B4, 0x0301, 0x0384 -> "\u0301"
            '^'.code, 0x02C6, 0x0302 -> "\u0302"
            '~'.code, 0x02DC, 0x0303 -> "\u0303"
            0x00AF, 0x02C9, 0x0304 -> "\u0304"
            0x02D8, 0x0306 -> "\u0306"
            0x02D9, 0x0307 -> "\u0307"
            '"'.code, 0x00A8, 0x0308 -> "\u0308"
            0x00B0, 0x02DA, 0x030A -> "\u030A"
            0x02DD, 0x030B -> "\u030B"
            0x02C7, 0x030C -> "\u030C"
            0x00B8, 0x0327 -> "\u0327"
            0x02DB, 0x0328 -> "\u0328"
            0x0385 -> "\u0308\u0301"
            in 0x0300..0x036F -> String(Character.toChars(dead))
            else -> null
        }

        private fun composeDeadAndBase(dead: Int, base: Int): Int {
            val spacingDead = deadToSpacingChar(dead)
            if (base == dead || base == spacingDead || base == ' '.code) {
                return spacingDead
            }
            val kcmComposed = KeyCharacterMap.getDeadChar(spacingDead, base)
            if (kcmComposed != 0) return kcmComposed

            val combining = deadToCombiningMarks(dead) ?: return 0
            val candidate = String(Character.toChars(base)) + combining
            val normalized = Normalizer.normalize(candidate, Normalizer.Form.NFC)
            return if (normalized.codePointCount(0, normalized.length) == 1) {
                normalized.codePointAt(0)
            } else {
                0
            }
        }
    }
}
