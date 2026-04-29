package sh.haven.feature.rdp

/**
 * Maps a typed character to the appropriate RDP wire events. Modern
 * Windows shells (cmd, PowerShell, full-screen apps) silently ignore
 * `TS_FP_UNICODE_KEYBOARD_EVENT` (the `WM_UNICHAR` Windows message),
 * so ASCII input has to be sent as scancode-based key events. IME-
 * capable apps (Notepad, browsers, Word) accept both, so the
 * scancode path is fine for them too.
 *
 * Non-ASCII falls back to the Unicode path — there's no scancode for
 * an emoji on a US keyboard. Apps that ignore `WM_UNICHAR` will drop
 * those silently, but on a Windows host with a hardware keyboard, US-
 * layout typing covers ~99% of real input.
 *
 * @param ch the character the user typed
 * @param sendKey `(scancode, pressed) -> Unit` — invoke for scancode
 *   events (matches the FFI `RdpClient.sendKey(scancode, pressed)`)
 * @param sendUnicode `(codepoint) -> Unit` — invoke for the Unicode
 *   fallback (typically wraps `RdpClient.sendUnicodeKey(cp, true)`
 *   immediately followed by `(cp, false)`)
 */
fun typeRdpChar(
    ch: Char,
    sendKey: (Int, Boolean) -> Unit,
    sendUnicode: (Int) -> Unit,
) {
    val mapped = asciiCharToRdpScancode(ch)
    if (mapped != null) {
        val (scancode, shift) = mapped
        if (shift) sendKey(SC_SHIFT_L_PUBLIC, true)
        sendKey(scancode, true)
        sendKey(scancode, false)
        if (shift) sendKey(SC_SHIFT_L_PUBLIC, false)
    } else {
        sendUnicode(ch.code)
    }
}

/**
 * Convert an ASCII character to a Windows AT-keyboard Set 1 scancode
 * plus a shift indicator (true = needs left-shift held). Returns null
 * for chars that have no scancode on a US keyboard (use the Unicode
 * path for those).
 *
 * Mapping is US English (kbdusa.dll) — matches the keyboard_layout
 * (0x0409) advertised in `build_config`. Non-US layouts would emit
 * the wrong character on the server side. Out of scope for now.
 */
fun asciiCharToRdpScancode(ch: Char): Pair<Int, Boolean>? {
    val code = ch.code
    return when (ch) {
        in 'a'..'z' -> Pair(LOWER_LETTER_SC[code - 'a'.code], false)
        in 'A'..'Z' -> Pair(LOWER_LETTER_SC[code - 'A'.code], true)
        in '0'..'9' -> Pair(DIGIT_SC[code - '0'.code], false)
        ' '  -> Pair(0x39, false)
        '\t' -> Pair(0x0F, false)
        '\n', '\r' -> Pair(0x1C, false)
        '\b' -> Pair(0x0E, false)
        '`'  -> Pair(0x29, false); '~' -> Pair(0x29, true)
        '!'  -> Pair(0x02, true)
        '@'  -> Pair(0x03, true)
        '#'  -> Pair(0x04, true)
        '$'  -> Pair(0x05, true)
        '%'  -> Pair(0x06, true)
        '^'  -> Pair(0x07, true)
        '&'  -> Pair(0x08, true)
        '*'  -> Pair(0x09, true)
        '('  -> Pair(0x0A, true)
        ')'  -> Pair(0x0B, true)
        '-'  -> Pair(0x0C, false); '_' -> Pair(0x0C, true)
        '='  -> Pair(0x0D, false); '+' -> Pair(0x0D, true)
        '['  -> Pair(0x1A, false); '{' -> Pair(0x1A, true)
        ']'  -> Pair(0x1B, false); '}' -> Pair(0x1B, true)
        '\\' -> Pair(0x2B, false); '|' -> Pair(0x2B, true)
        ';'  -> Pair(0x27, false); ':' -> Pair(0x27, true)
        '\'' -> Pair(0x28, false); '"' -> Pair(0x28, true)
        ','  -> Pair(0x33, false); '<' -> Pair(0x33, true)
        '.'  -> Pair(0x34, false); '>' -> Pair(0x34, true)
        '/'  -> Pair(0x35, false); '?' -> Pair(0x35, true)
        else -> null
    }
}

// Public left-shift scancode (mirrors RdpScreen's private SC_SHIFT_L).
const val SC_SHIFT_L_PUBLIC: Int = 0x2A

// US-layout AT scancodes (Set 1) for letters a..z. Indexed by ch - 'a'.
private val LOWER_LETTER_SC = intArrayOf(
    0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24,  // a..j
    0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14,  // k..t
    0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C,                          // u..z
)

// US-layout scancodes for digits 0..9. Indexed by ch - '0'.
private val DIGIT_SC = intArrayOf(
    0x0B, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
)
