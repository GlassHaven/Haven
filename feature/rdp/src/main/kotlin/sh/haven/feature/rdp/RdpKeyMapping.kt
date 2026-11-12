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
    layoutKlid: UInt = sh.haven.core.rdp.keyboardLayoutKlid(),
) {
    // #504 follow-up: base-remapped layouts (QWERTZ, AZERTY, UK). The KLID we
    // announce makes the server interpret scancodes through THAT layout, so
    // the US char→position table is wrong for them — 'z' at the US position
    // types 'y' on a QWERTZ server. Each such layout gets a self-contained
    // table; a char it can't produce falls straight to unicode, NEVER to the
    // US table, because a US position can land on the layout's dead key and
    // silently poison the following keystroke.
    val remapped = baseRemapTableFor(layoutKlid)
    if (remapped != null) {
        val stroke = remapped[ch] ?: CONTROL_KEYSTROKES[ch] ?: run {
            sendUnicode(ch.code)
            return
        }
        emitKeystroke(stroke, sendKey)
        return
    }
    val mapped = asciiCharToRdpScancode(ch)
    if (mapped != null) {
        val (scancode, shift) = mapped
        emitKeystroke(LayoutKeystroke(scancode, shift = shift), sendKey)
        return
    }
    // #504: scancodes-only servers (VirtualBox VRDP) discard the unicode
    // fallback, so layouts with an AltGr overlay get their characters
    // synthesised as real key sequences the guest's own keymap resolves.
    val altGr = altGrCharToRdpScancode(ch, layoutKlid)
    if (altGr != null) {
        val (scancode, shift) = altGr
        emitKeystroke(LayoutKeystroke(scancode, shift = shift, altGr = true), sendKey)
        return
    }
    sendUnicode(ch.code)
}

/**
 * One synthesised key tap: the base [scancode] with the modifiers that make
 * the server's own keymap produce the intended character.
 */
data class LayoutKeystroke(
    val scancode: Int,
    val shift: Boolean = false,
    val altGr: Boolean = false,
)

private fun emitKeystroke(stroke: LayoutKeystroke, sendKey: (Int, Boolean) -> Unit) {
    if (stroke.altGr) sendKey(SC_ALTGR_PUBLIC, true)
    if (stroke.shift) sendKey(SC_SHIFT_L_PUBLIC, true)
    sendKey(stroke.scancode, true)
    sendKey(stroke.scancode, false)
    if (stroke.shift) sendKey(SC_SHIFT_L_PUBLIC, false)
    if (stroke.altGr) sendKey(SC_ALTGR_PUBLIC, false)
}

/**
 * AltGr-overlay synthesis (#504): for layouts whose base is exactly US and
 * whose national characters live on AltGr combinations, a non-ASCII char
 * becomes AltGr(+Shift)+basekey — key events every server understands,
 * resolved by the SERVER side's layout for the announced KLID. This is what
 * makes Polish typing reach a VirtualBox guest whose RDP server accepts only
 * scancodes (the unicode path is discarded there).
 *
 * Only pure-overlay layouts belong here. Layouts that REMAP the base (German
 * QWERTZ, French AZERTY, Spanish...) can't be served by an overlay table —
 * their fix is a full base-map swap, tracked separately. Returns
 * (base scancode, needs-shift) or null when [layoutKlid] has no overlay
 * entry for [ch].
 */
fun altGrCharToRdpScancode(ch: Char, layoutKlid: UInt): Pair<Int, Boolean>? {
    val overlay = when (layoutKlid) {
        POLISH_PROGRAMMERS_KLID -> POLISH_ALTGR_OVERLAY
        else -> return null
    }
    overlay[ch.lowercaseChar()]?.let { baseScancode ->
        return Pair(baseScancode, ch.isUpperCase())
    }
    return null
}

/** Right Alt (AltGr): the extended twin of left Alt 0x38. */
const val SC_ALTGR_PUBLIC: Int = 0xE038

/** Polish (programmers), the standard Polish layout: US base + AltGr. */
const val POLISH_PROGRAMMERS_KLID: UInt = 0x0415u

// AltGr + <US base key> for each Polish national letter (lowercase form;
// uppercase adds Shift). kbdpl1.dll's overlay, verbatim.
private val POLISH_ALTGR_OVERLAY: Map<Char, Int> = mapOf(
    'ą' to 0x1E, // AltGr+A
    'ć' to 0x2E, // AltGr+C
    'ę' to 0x12, // AltGr+E
    'ł' to 0x26, // AltGr+L
    'ń' to 0x31, // AltGr+N
    'ó' to 0x18, // AltGr+O
    'ś' to 0x1F, // AltGr+S
    'ź' to 0x2D, // AltGr+X
    'ż' to 0x2C, // AltGr+Z
)

/** German QWERTZ (kbdgr.dll). */
const val GERMAN_QWERTZ_KLID: UInt = 0x0407u

/** Swiss German QWERTZ (kbdsg.dll / xkb `ch(de)`). */
const val SWISS_GERMAN_KLID: UInt = 0x0807u

/** French AZERTY (kbdfr.dll). */
const val FRENCH_AZERTY_KLID: UInt = 0x040Cu

/** Belgian AZERTY (kbdbe.dll / xkb `be`). */
const val BELGIAN_AZERTY_KLID: UInt = 0x080Cu

/** Swiss French QWERTZ (kbdsf.dll / xkb `ch(fr)`). */
const val SWISS_FRENCH_KLID: UInt = 0x100Cu

/** UK English (kbduk.dll) — US base with a handful of moved symbols. */
const val UK_ENGLISH_KLID: UInt = 0x0809u

/** Spanish — Spain (kbdsp.dll / xkb `es`, #606). */
const val SPANISH_KLID: UInt = 0x040Au

/** Spanish — Latin America (kbdla.dll / xkb `latam`, #606). */
const val LATIN_AMERICAN_SPANISH_KLID: UInt = 0x080Au

/** Italian (kbdit.dll / xkb `it`, #606). */
const val ITALIAN_KLID: UInt = 0x0410u

/** Portuguese — Portugal (kbdpo.dll / xkb `pt`, #606). */
const val PORTUGUESE_KLID: UInt = 0x0816u

/** Portuguese — Brazil ABNT2 (kbdbr.dll / xkb `br`, #606). */
const val BRAZILIAN_ABNT2_KLID: UInt = 0x0416u

/** Swedish / Finnish (kbdsw.dll / kbdfi.dll / xkb `se`, #606). */
const val SWEDISH_KLID: UInt = 0x041Du
const val FINNISH_KLID: UInt = 0x040Bu

/** The ISO 102nd key (between left Shift and Z on non-US boards). */
const val SC_ISO_102: Int = 0x56

/**
 * Full char→keystroke table for a base-remapped [layoutKlid], or null when
 * the layout is US-based (overlay layouts included) and the plain ASCII path
 * applies. Returned tables are SELF-CONTAINED: a missing char means the
 * layout needs a dead-key composition for it, and the caller must use the
 * unicode fallback, not the US table.
 */
fun baseRemapTableFor(layoutKlid: UInt): Map<Char, LayoutKeystroke>? = when (layoutKlid) {
    GERMAN_QWERTZ_KLID -> GERMAN_QWERTZ_BASE
    SWISS_GERMAN_KLID -> SWISS_GERMAN_BASE
    FRENCH_AZERTY_KLID -> FRENCH_AZERTY_BASE
    BELGIAN_AZERTY_KLID -> BELGIAN_AZERTY_BASE
    SWISS_FRENCH_KLID -> SWISS_FRENCH_BASE
    UK_ENGLISH_KLID -> UK_ENGLISH_BASE
    SPANISH_KLID -> SPANISH_BASE
    LATIN_AMERICAN_SPANISH_KLID -> LATIN_AMERICAN_SPANISH_BASE
    ITALIAN_KLID -> ITALIAN_BASE
    PORTUGUESE_KLID -> PORTUGUESE_BASE
    BRAZILIAN_ABNT2_KLID -> BRAZILIAN_ABNT2_BASE
    SWEDISH_KLID, FINNISH_KLID -> SWEDISH_BASE
    else -> null
}

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

// Control keys sit on the same scancodes in every layout.
private val CONTROL_KEYSTROKES: Map<Char, LayoutKeystroke> = mapOf(
    ' ' to LayoutKeystroke(0x39),
    '\t' to LayoutKeystroke(0x0F),
    '\n' to LayoutKeystroke(0x1C),
    '\r' to LayoutKeystroke(0x1C),
    '\b' to LayoutKeystroke(0x0E),
)

// German QWERTZ (kbdgr.dll), T1. Dead keys ´ ` ^ have no entry — they need a
// two-key composition (dead + space) the synthesis deliberately avoids; those
// chars take the unicode fallback.
private val GERMAN_QWERTZ_BASE: Map<Char, LayoutKeystroke> = buildMap {
    // Letters: US positions except the y/z swap.
    "abcdefghijklmnopqrstuvwx".forEach { c ->
        put(c, LayoutKeystroke(LOWER_LETTER_SC[c - 'a']))
        put(c.uppercaseChar(), LayoutKeystroke(LOWER_LETTER_SC[c - 'a'], shift = true))
    }
    put('y', LayoutKeystroke(0x2C)); put('Y', LayoutKeystroke(0x2C, shift = true))
    put('z', LayoutKeystroke(0x15)); put('Z', LayoutKeystroke(0x15, shift = true))
    // Digit row: plain digits; shifted symbols; AltGr brackets.
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "!\"§$%&/()=".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    put('²', LayoutKeystroke(0x03, altGr = true))
    put('³', LayoutKeystroke(0x04, altGr = true))
    put('{', LayoutKeystroke(0x08, altGr = true))
    put('[', LayoutKeystroke(0x09, altGr = true))
    put(']', LayoutKeystroke(0x0A, altGr = true))
    put('}', LayoutKeystroke(0x0B, altGr = true))
    // ß key (right of 0) and its friends.
    put('ß', LayoutKeystroke(0x0C))
    put('?', LayoutKeystroke(0x0C, shift = true))
    put('\\', LayoutKeystroke(0x0C, altGr = true))
    // Umlaut keys and neighbours.
    put('ü', LayoutKeystroke(0x1A)); put('Ü', LayoutKeystroke(0x1A, shift = true))
    put('+', LayoutKeystroke(0x1B))
    put('*', LayoutKeystroke(0x1B, shift = true))
    put('~', LayoutKeystroke(0x1B, altGr = true))
    put('ö', LayoutKeystroke(0x27)); put('Ö', LayoutKeystroke(0x27, shift = true))
    put('ä', LayoutKeystroke(0x28)); put('Ä', LayoutKeystroke(0x28, shift = true))
    put('°', LayoutKeystroke(0x29, shift = true))
    put('#', LayoutKeystroke(0x2B))
    put('\'', LayoutKeystroke(0x2B, shift = true))
    put(',', LayoutKeystroke(0x33)); put(';', LayoutKeystroke(0x33, shift = true))
    put('.', LayoutKeystroke(0x34)); put(':', LayoutKeystroke(0x34, shift = true))
    put('-', LayoutKeystroke(0x35)); put('_', LayoutKeystroke(0x35, shift = true))
    put('<', LayoutKeystroke(SC_ISO_102))
    put('>', LayoutKeystroke(SC_ISO_102, shift = true))
    put('|', LayoutKeystroke(SC_ISO_102, altGr = true))
    put('@', LayoutKeystroke(0x10, altGr = true)) // AltGr+Q
    put('€', LayoutKeystroke(0x12, altGr = true)) // AltGr+E
    put('µ', LayoutKeystroke(0x32, altGr = true)) // AltGr+M
}

// French AZERTY (kbdfr.dll). Dead keys (^ ¨ on 0x1A; AltGr ~ and `) have no
// entry; capital accented letters (É È Ç À Ù) need Caps Lock on a real French
// board, so they take the unicode fallback too.
private val FRENCH_AZERTY_BASE: Map<Char, LayoutKeystroke> = buildMap {
    val letterPos = mapOf(
        'a' to 0x10, 'z' to 0x11, 'e' to 0x12, 'r' to 0x13, 't' to 0x14,
        'y' to 0x15, 'u' to 0x16, 'i' to 0x17, 'o' to 0x18, 'p' to 0x19,
        'q' to 0x1E, 's' to 0x1F, 'd' to 0x20, 'f' to 0x21, 'g' to 0x22,
        'h' to 0x23, 'j' to 0x24, 'k' to 0x25, 'l' to 0x26, 'm' to 0x27,
        'w' to 0x2C, 'x' to 0x2D, 'c' to 0x2E, 'v' to 0x2F, 'b' to 0x30,
        'n' to 0x31,
    )
    letterPos.forEach { (c, sc) ->
        put(c, LayoutKeystroke(sc))
        put(c.uppercaseChar(), LayoutKeystroke(sc, shift = true))
    }
    // Digit row: plain gives the national chars, SHIFT gives the digits.
    "&é\"'(-è_çà".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    // AltGr row (non-dead only; ~ and ` are dead on AltGr+2/AltGr+7).
    put('#', LayoutKeystroke(0x04, altGr = true))
    put('{', LayoutKeystroke(0x05, altGr = true))
    put('[', LayoutKeystroke(0x06, altGr = true))
    put('|', LayoutKeystroke(0x07, altGr = true))
    put('\\', LayoutKeystroke(0x09, altGr = true))
    put('^', LayoutKeystroke(0x0A, altGr = true)) // AltGr+9: NON-dead caret
    put('@', LayoutKeystroke(0x0B, altGr = true))
    put('€', LayoutKeystroke(0x12, altGr = true))
    put(')', LayoutKeystroke(0x0C))
    put('°', LayoutKeystroke(0x0C, shift = true))
    put(']', LayoutKeystroke(0x0C, altGr = true))
    put('=', LayoutKeystroke(0x0D))
    put('+', LayoutKeystroke(0x0D, shift = true))
    put('}', LayoutKeystroke(0x0D, altGr = true))
    put('$', LayoutKeystroke(0x1B))
    put('£', LayoutKeystroke(0x1B, shift = true))
    put('¤', LayoutKeystroke(0x1B, altGr = true))
    put('ù', LayoutKeystroke(0x28))
    put('%', LayoutKeystroke(0x28, shift = true))
    put('²', LayoutKeystroke(0x29))
    put('*', LayoutKeystroke(0x2B))
    put('µ', LayoutKeystroke(0x2B, shift = true))
    put(',', LayoutKeystroke(0x32)); put('?', LayoutKeystroke(0x32, shift = true))
    put(';', LayoutKeystroke(0x33)); put('.', LayoutKeystroke(0x33, shift = true))
    put(':', LayoutKeystroke(0x34)); put('/', LayoutKeystroke(0x34, shift = true))
    put('!', LayoutKeystroke(0x35)); put('§', LayoutKeystroke(0x35, shift = true))
    put('<', LayoutKeystroke(SC_ISO_102))
    put('>', LayoutKeystroke(SC_ISO_102, shift = true))
}

// UK (kbduk.dll): US base plus the handful of moved symbols. Built as the US
// table with overrides, so it stays self-contained like the others.
private val UK_ENGLISH_BASE: Map<Char, LayoutKeystroke> = buildMap {
    ('a'..'z').forEach { c ->
        put(c, LayoutKeystroke(LOWER_LETTER_SC[c - 'a']))
        put(c.uppercaseChar(), LayoutKeystroke(LOWER_LETTER_SC[c - 'a'], shift = true))
    }
    "0123456789".forEach { c -> put(c, LayoutKeystroke(DIGIT_SC[c - '0'])) }
    "!$%^&*()".forEachIndexed { i, c ->
        // UK shift row matches US except positions 2 and 3 (see overrides).
        put(c, LayoutKeystroke(intArrayOf(0x02, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B)[i], shift = true))
    }
    put('-', LayoutKeystroke(0x0C)); put('_', LayoutKeystroke(0x0C, shift = true))
    put('=', LayoutKeystroke(0x0D)); put('+', LayoutKeystroke(0x0D, shift = true))
    put('[', LayoutKeystroke(0x1A)); put('{', LayoutKeystroke(0x1A, shift = true))
    put(']', LayoutKeystroke(0x1B)); put('}', LayoutKeystroke(0x1B, shift = true))
    put(';', LayoutKeystroke(0x27)); put(':', LayoutKeystroke(0x27, shift = true))
    put(',', LayoutKeystroke(0x33)); put('<', LayoutKeystroke(0x33, shift = true))
    put('.', LayoutKeystroke(0x34)); put('>', LayoutKeystroke(0x34, shift = true))
    put('/', LayoutKeystroke(0x35)); put('?', LayoutKeystroke(0x35, shift = true))
    // The UK differences: @ and " swap, £ on 2... on 3, # gets its own key,
    // backslash moves to the 102nd key, ` gains ¬, € rides AltGr+4.
    put('\'', LayoutKeystroke(0x28)); put('@', LayoutKeystroke(0x28, shift = true))
    put('"', LayoutKeystroke(0x03, shift = true))
    put('£', LayoutKeystroke(0x04, shift = true))
    put('#', LayoutKeystroke(0x2B)); put('~', LayoutKeystroke(0x2B, shift = true))
    put('\\', LayoutKeystroke(SC_ISO_102)); put('|', LayoutKeystroke(SC_ISO_102, shift = true))
    put('`', LayoutKeystroke(0x29)); put('¬', LayoutKeystroke(0x29, shift = true))
    put('€', LayoutKeystroke(0x05, altGr = true))
}

private fun MutableMap<Char, LayoutKeystroke>.putQwertyLetters() {
    ('a'..'z').forEach { c ->
        put(c, LayoutKeystroke(LOWER_LETTER_SC[c - 'a']))
        put(c.uppercaseChar(), LayoutKeystroke(LOWER_LETTER_SC[c - 'a'], shift = true))
    }
}

private fun MutableMap<Char, LayoutKeystroke>.putQwertzLetters() {
    "abcdefghijklmnopqrstuvwx".forEach { c ->
        put(c, LayoutKeystroke(LOWER_LETTER_SC[c - 'a']))
        put(c.uppercaseChar(), LayoutKeystroke(LOWER_LETTER_SC[c - 'a'], shift = true))
    }
    put('y', LayoutKeystroke(0x2C)); put('Y', LayoutKeystroke(0x2C, shift = true))
    put('z', LayoutKeystroke(0x15)); put('Z', LayoutKeystroke(0x15, shift = true))
}

// Spanish — Spain (kbdsp.dll / xkb `es`, #606). Dead keys ` ^ (0x1A) and ´ ¨ (0x28)
// are omitted so they never poison the next keystroke.
private val SPANISH_BASE: Map<Char, LayoutKeystroke> = buildMap {
    putQwertyLetters()
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "!\"·$%&/()=".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    put('º', LayoutKeystroke(0x29))
    put('ª', LayoutKeystroke(0x29, shift = true))
    put('\\', LayoutKeystroke(0x29, altGr = true))
    put('|', LayoutKeystroke(0x02, altGr = true))
    put('@', LayoutKeystroke(0x03, altGr = true))
    put('#', LayoutKeystroke(0x04, altGr = true))
    put('~', LayoutKeystroke(0x05, altGr = true))
    put('¬', LayoutKeystroke(0x07, altGr = true))
    put('€', LayoutKeystroke(0x12, altGr = true))
    put('\'', LayoutKeystroke(0x0C)); put('?', LayoutKeystroke(0x0C, shift = true))
    put('¡', LayoutKeystroke(0x0D)); put('¿', LayoutKeystroke(0x0D, shift = true))
    put('[', LayoutKeystroke(0x1A, altGr = true))
    put('+', LayoutKeystroke(0x1B)); put('*', LayoutKeystroke(0x1B, shift = true))
    put(']', LayoutKeystroke(0x1B, altGr = true))
    put('ñ', LayoutKeystroke(0x27)); put('Ñ', LayoutKeystroke(0x27, shift = true))
    put('{', LayoutKeystroke(0x28, altGr = true))
    put('ç', LayoutKeystroke(0x2B)); put('Ç', LayoutKeystroke(0x2B, shift = true))
    put('}', LayoutKeystroke(0x2B, altGr = true))
    put('<', LayoutKeystroke(SC_ISO_102)); put('>', LayoutKeystroke(SC_ISO_102, shift = true))
    put(',', LayoutKeystroke(0x33)); put(';', LayoutKeystroke(0x33, shift = true))
    put('.', LayoutKeystroke(0x34)); put(':', LayoutKeystroke(0x34, shift = true))
    put('-', LayoutKeystroke(0x35)); put('_', LayoutKeystroke(0x35, shift = true))
}

// Spanish — Latin America (kbdla.dll / xkb `latam`, #606). Dead ´ ¨ sit on 0x1A;
// { [ and } ] sit directly on 0x28 and 0x2B without AltGr, and @ sits on AltGr+Q.
private val LATIN_AMERICAN_SPANISH_BASE: Map<Char, LayoutKeystroke> = buildMap {
    putQwertyLetters()
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "!\"#$%&/()=".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    put('|', LayoutKeystroke(0x29))
    put('°', LayoutKeystroke(0x29, shift = true))
    put('¬', LayoutKeystroke(0x29, altGr = true))
    put('@', LayoutKeystroke(0x10, altGr = true))
    put('€', LayoutKeystroke(0x12, altGr = true))
    put('\'', LayoutKeystroke(0x0C)); put('?', LayoutKeystroke(0x0C, shift = true))
    put('\\', LayoutKeystroke(0x0C, altGr = true))
    put('¿', LayoutKeystroke(0x0D)); put('¡', LayoutKeystroke(0x0D, shift = true))
    put('+', LayoutKeystroke(0x1B)); put('*', LayoutKeystroke(0x1B, shift = true))
    put('~', LayoutKeystroke(0x1B, altGr = true))
    put('ñ', LayoutKeystroke(0x27)); put('Ñ', LayoutKeystroke(0x27, shift = true))
    put('{', LayoutKeystroke(0x28)); put('[', LayoutKeystroke(0x28, shift = true))
    put('}', LayoutKeystroke(0x2B)); put(']', LayoutKeystroke(0x2B, shift = true))
    put('<', LayoutKeystroke(SC_ISO_102)); put('>', LayoutKeystroke(SC_ISO_102, shift = true))
    put(',', LayoutKeystroke(0x33)); put(';', LayoutKeystroke(0x33, shift = true))
    put('.', LayoutKeystroke(0x34)); put(':', LayoutKeystroke(0x34, shift = true))
    put('-', LayoutKeystroke(0x35)); put('_', LayoutKeystroke(0x35, shift = true))
}

// Italian (kbdit.dll / xkb `it`, #606). Standard Italian has no base dead keys.
private val ITALIAN_BASE: Map<Char, LayoutKeystroke> = buildMap {
    putQwertyLetters()
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "!\"£$%&/()=".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    put('\\', LayoutKeystroke(0x29)); put('|', LayoutKeystroke(0x29, shift = true))
    put('\'', LayoutKeystroke(0x0C)); put('?', LayoutKeystroke(0x0C, shift = true))
    put('ì', LayoutKeystroke(0x0D)); put('^', LayoutKeystroke(0x0D, shift = true))
    put('€', LayoutKeystroke(0x12, altGr = true))
    put('è', LayoutKeystroke(0x1A)); put('é', LayoutKeystroke(0x1A, shift = true))
    put('[', LayoutKeystroke(0x1A, altGr = true))
    put('{', LayoutKeystroke(0x1A, shift = true, altGr = true))
    put('+', LayoutKeystroke(0x1B)); put('*', LayoutKeystroke(0x1B, shift = true))
    put(']', LayoutKeystroke(0x1B, altGr = true))
    put('}', LayoutKeystroke(0x1B, shift = true, altGr = true))
    put('ò', LayoutKeystroke(0x27)); put('ç', LayoutKeystroke(0x27, shift = true))
    put('@', LayoutKeystroke(0x27, altGr = true))
    put('à', LayoutKeystroke(0x28)); put('°', LayoutKeystroke(0x28, shift = true))
    put('#', LayoutKeystroke(0x28, altGr = true))
    put('ù', LayoutKeystroke(0x2B)); put('§', LayoutKeystroke(0x2B, shift = true))
    put('<', LayoutKeystroke(SC_ISO_102)); put('>', LayoutKeystroke(SC_ISO_102, shift = true))
    put(',', LayoutKeystroke(0x33)); put(';', LayoutKeystroke(0x33, shift = true))
    put('.', LayoutKeystroke(0x34)); put(':', LayoutKeystroke(0x34, shift = true))
    put('-', LayoutKeystroke(0x35)); put('_', LayoutKeystroke(0x35, shift = true))
}

// Swiss German / Swiss French QWERTZ (kbdsg.dll / kbdsf.dll / xkb `ch`, #606).
// The two layouts are identical except whether umlauts or French accents sit unshifted
// on 0x1A / 0x27 / 0x28. Dead keys ^ ` ~ (0x0D) and ¨ (unshifted 0x1B) are omitted.
private fun buildSwissQwertz(frenchPrimary: Boolean): Map<Char, LayoutKeystroke> = buildMap {
    putQwertzLetters()
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "+\"*ç%&/()=".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    put('§', LayoutKeystroke(0x29)); put('°', LayoutKeystroke(0x29, shift = true))
    put('|', LayoutKeystroke(0x02, altGr = true))
    put('@', LayoutKeystroke(0x03, altGr = true))
    put('#', LayoutKeystroke(0x04, altGr = true))
    put('¬', LayoutKeystroke(0x07, altGr = true))
    put('¢', LayoutKeystroke(0x09, altGr = true))
    put('€', LayoutKeystroke(0x12, altGr = true))
    put('\'', LayoutKeystroke(0x0C)); put('?', LayoutKeystroke(0x0C, shift = true))
    if (frenchPrimary) {
        put('è', LayoutKeystroke(0x1A)); put('ü', LayoutKeystroke(0x1A, shift = true))
        put('é', LayoutKeystroke(0x27)); put('ö', LayoutKeystroke(0x27, shift = true))
        put('à', LayoutKeystroke(0x28)); put('ä', LayoutKeystroke(0x28, shift = true))
    } else {
        put('ü', LayoutKeystroke(0x1A)); put('è', LayoutKeystroke(0x1A, shift = true))
        put('ö', LayoutKeystroke(0x27)); put('é', LayoutKeystroke(0x27, shift = true))
        put('ä', LayoutKeystroke(0x28)); put('à', LayoutKeystroke(0x28, shift = true))
    }
    put('[', LayoutKeystroke(0x1A, altGr = true))
    put('!', LayoutKeystroke(0x1B, shift = true))
    put(']', LayoutKeystroke(0x1B, altGr = true))
    put('{', LayoutKeystroke(0x28, altGr = true))
    put('$', LayoutKeystroke(0x2B)); put('£', LayoutKeystroke(0x2B, shift = true))
    put('}', LayoutKeystroke(0x2B, altGr = true))
    put('<', LayoutKeystroke(SC_ISO_102)); put('>', LayoutKeystroke(SC_ISO_102, shift = true))
    put('\\', LayoutKeystroke(SC_ISO_102, altGr = true))
    put(',', LayoutKeystroke(0x33)); put(';', LayoutKeystroke(0x33, shift = true))
    put('.', LayoutKeystroke(0x34)); put(':', LayoutKeystroke(0x34, shift = true))
    put('-', LayoutKeystroke(0x35)); put('_', LayoutKeystroke(0x35, shift = true))
}

private val SWISS_GERMAN_BASE: Map<Char, LayoutKeystroke> = buildSwissQwertz(frenchPrimary = false)
private val SWISS_FRENCH_BASE: Map<Char, LayoutKeystroke> = buildSwissQwertz(frenchPrimary = true)

// Belgian AZERTY (kbdbe.dll / xkb `be`, #606). Differs from French AZERTY on the
// number row (§/! instead of -/_ on 6/8), -/_ on 0x0D, =/+ on 0x35, and AltGr brackets.
private val BELGIAN_AZERTY_BASE: Map<Char, LayoutKeystroke> = buildMap {
    val letterPos = mapOf(
        'a' to 0x10, 'z' to 0x11, 'e' to 0x12, 'r' to 0x13, 't' to 0x14,
        'y' to 0x15, 'u' to 0x16, 'i' to 0x17, 'o' to 0x18, 'p' to 0x19,
        'q' to 0x1E, 's' to 0x1F, 'd' to 0x20, 'f' to 0x21, 'g' to 0x22,
        'h' to 0x23, 'j' to 0x24, 'k' to 0x25, 'l' to 0x26, 'm' to 0x27,
        'w' to 0x2C, 'x' to 0x2D, 'c' to 0x2E, 'v' to 0x2F, 'b' to 0x30,
        'n' to 0x31,
    )
    letterPos.forEach { (c, sc) ->
        put(c, LayoutKeystroke(sc))
        put(c.uppercaseChar(), LayoutKeystroke(sc, shift = true))
    }
    put('²', LayoutKeystroke(0x29)); put('³', LayoutKeystroke(0x29, shift = true))
    "&é\"'(§è!çà".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    put('|', LayoutKeystroke(0x02, altGr = true))
    put('@', LayoutKeystroke(0x03, altGr = true))
    put('#', LayoutKeystroke(0x04, altGr = true))
    put('^', LayoutKeystroke(0x07, altGr = true)) // AltGr+6 non-dead caret
    put('{', LayoutKeystroke(0x0A, altGr = true)) // AltGr+9
    put('}', LayoutKeystroke(0x0B, altGr = true)) // AltGr+0
    put('€', LayoutKeystroke(0x12, altGr = true))
    put(')', LayoutKeystroke(0x0C)); put('°', LayoutKeystroke(0x0C, shift = true))
    put('-', LayoutKeystroke(0x0D)); put('_', LayoutKeystroke(0x0D, shift = true))
    put('[', LayoutKeystroke(0x1A, altGr = true))
    put('$', LayoutKeystroke(0x1B)); put('*', LayoutKeystroke(0x1B, shift = true))
    put(']', LayoutKeystroke(0x1B, altGr = true))
    put('ù', LayoutKeystroke(0x28)); put('%', LayoutKeystroke(0x28, shift = true))
    put('µ', LayoutKeystroke(0x2B)); put('£', LayoutKeystroke(0x2B, shift = true))
    put('<', LayoutKeystroke(SC_ISO_102)); put('>', LayoutKeystroke(SC_ISO_102, shift = true))
    put('\\', LayoutKeystroke(SC_ISO_102, altGr = true))
    put(',', LayoutKeystroke(0x32)); put('?', LayoutKeystroke(0x32, shift = true))
    put(';', LayoutKeystroke(0x33)); put('.', LayoutKeystroke(0x33, shift = true))
    put(':', LayoutKeystroke(0x34)); put('/', LayoutKeystroke(0x34, shift = true))
    put('=', LayoutKeystroke(0x35)); put('+', LayoutKeystroke(0x35, shift = true))
}

// Portuguese — Portugal (kbdpo.dll / xkb `pt`, #606). Dead ´ ` (0x1B) and ~ ^ (0x2B) omitted.
private val PORTUGUESE_BASE: Map<Char, LayoutKeystroke> = buildMap {
    putQwertyLetters()
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "!\"#$%&/()=".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    put('\\', LayoutKeystroke(0x29)); put('|', LayoutKeystroke(0x29, shift = true))
    put('@', LayoutKeystroke(0x03, altGr = true))
    put('£', LayoutKeystroke(0x04, altGr = true))
    put('§', LayoutKeystroke(0x05, altGr = true))
    put('{', LayoutKeystroke(0x08, altGr = true))
    put('[', LayoutKeystroke(0x09, altGr = true))
    put(']', LayoutKeystroke(0x0A, altGr = true))
    put('}', LayoutKeystroke(0x0B, altGr = true))
    put('€', LayoutKeystroke(0x12, altGr = true))
    put('\'', LayoutKeystroke(0x0C)); put('?', LayoutKeystroke(0x0C, shift = true))
    put('«', LayoutKeystroke(0x0D)); put('»', LayoutKeystroke(0x0D, shift = true))
    put('+', LayoutKeystroke(0x1A)); put('*', LayoutKeystroke(0x1A, shift = true))
    put('ç', LayoutKeystroke(0x27)); put('Ç', LayoutKeystroke(0x27, shift = true))
    put('º', LayoutKeystroke(0x28)); put('ª', LayoutKeystroke(0x28, shift = true))
    put('<', LayoutKeystroke(SC_ISO_102)); put('>', LayoutKeystroke(SC_ISO_102, shift = true))
    put(',', LayoutKeystroke(0x33)); put(';', LayoutKeystroke(0x33, shift = true))
    put('.', LayoutKeystroke(0x34)); put(':', LayoutKeystroke(0x34, shift = true))
    put('-', LayoutKeystroke(0x35)); put('_', LayoutKeystroke(0x35, shift = true))
}

// Portuguese — Brazil ABNT2 (kbdbr.dll / xkb `br`, #606). Shift+6 (¨), 0x1A (´/`),
// and 0x28 (~/^) are dead keys; `/` and `?` use AltGr+Q / AltGr+W so they work even
// when a hypervisor emulates a 105-key ISO PS/2 board without physical key 0x73.
private val BRAZILIAN_ABNT2_BASE: Map<Char, LayoutKeystroke> = buildMap {
    putQwertyLetters()
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "!@#$%".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    "&*()".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x08 + i, shift = true)) }
    put('\'', LayoutKeystroke(0x29)); put('"', LayoutKeystroke(0x29, shift = true))
    put('-', LayoutKeystroke(0x0C)); put('_', LayoutKeystroke(0x0C, shift = true))
    put('=', LayoutKeystroke(0x0D)); put('+', LayoutKeystroke(0x0D, shift = true))
    put('§', LayoutKeystroke(0x0D, altGr = true))
    put('/', LayoutKeystroke(0x10, altGr = true))  // AltGr+Q on kbdbr.dll & xkb `br`
    put('?', LayoutKeystroke(0x11, altGr = true))  // AltGr+W on kbdbr.dll & xkb `br`
    put('°', LayoutKeystroke(0x12, altGr = true))  // AltGr+E on kbdbr.dll & xkb `br`
    put('[', LayoutKeystroke(0x1B)); put('{', LayoutKeystroke(0x1B, shift = true))
    put('ª', LayoutKeystroke(0x1B, altGr = true))
    put('ç', LayoutKeystroke(0x27)); put('Ç', LayoutKeystroke(0x27, shift = true))
    put(']', LayoutKeystroke(0x2B)); put('}', LayoutKeystroke(0x2B, shift = true))
    put('º', LayoutKeystroke(0x2B, altGr = true))
    put('\\', LayoutKeystroke(SC_ISO_102)); put('|', LayoutKeystroke(SC_ISO_102, shift = true))
    put(',', LayoutKeystroke(0x33)); put('<', LayoutKeystroke(0x33, shift = true))
    put('.', LayoutKeystroke(0x34)); put('>', LayoutKeystroke(0x34, shift = true))
    put(';', LayoutKeystroke(0x35)); put(':', LayoutKeystroke(0x35, shift = true))
}

// Swedish / Finnish (kbdsw.dll / kbdfi.dll / xkb `se`, #606). Dead ´ ` (0x0D) and
// ¨ ^ ~ (0x1B) are omitted.
private val SWEDISH_BASE: Map<Char, LayoutKeystroke> = buildMap {
    putQwertyLetters()
    "1234567890".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i)) }
    "!\"#¤%&/()=".forEachIndexed { i, c -> put(c, LayoutKeystroke(0x02 + i, shift = true)) }
    put('§', LayoutKeystroke(0x29)); put('½', LayoutKeystroke(0x29, shift = true))
    put('@', LayoutKeystroke(0x03, altGr = true))
    put('£', LayoutKeystroke(0x04, altGr = true))
    put('$', LayoutKeystroke(0x05, altGr = true))
    put('€', LayoutKeystroke(0x06, altGr = true))
    put('{', LayoutKeystroke(0x08, altGr = true))
    put('[', LayoutKeystroke(0x09, altGr = true))
    put(']', LayoutKeystroke(0x0A, altGr = true))
    put('}', LayoutKeystroke(0x0B, altGr = true))
    put('+', LayoutKeystroke(0x0C)); put('?', LayoutKeystroke(0x0C, shift = true))
    put('\\', LayoutKeystroke(0x0C, altGr = true))
    put('å', LayoutKeystroke(0x1A)); put('Å', LayoutKeystroke(0x1A, shift = true))
    put('ö', LayoutKeystroke(0x27)); put('Ö', LayoutKeystroke(0x27, shift = true))
    put('ä', LayoutKeystroke(0x28)); put('Ä', LayoutKeystroke(0x28, shift = true))
    put('\'', LayoutKeystroke(0x2B)); put('*', LayoutKeystroke(0x2B, shift = true))
    put('<', LayoutKeystroke(SC_ISO_102)); put('>', LayoutKeystroke(SC_ISO_102, shift = true))
    put('|', LayoutKeystroke(SC_ISO_102, altGr = true))
    put(',', LayoutKeystroke(0x33)); put(';', LayoutKeystroke(0x33, shift = true))
    put('.', LayoutKeystroke(0x34)); put(':', LayoutKeystroke(0x34, shift = true))
    put('-', LayoutKeystroke(0x35)); put('_', LayoutKeystroke(0x35, shift = true))
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
