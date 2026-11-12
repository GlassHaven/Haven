package sh.haven.core.glkvm

/**
 * PC set-1 scancode → KeyboardEvent.code translation for the shared
 * desktop keyboard lane. VNC/RDP/SPICE tabs all receive PC set-1 codes
 * from the viewer's key pipeline; kvmd instead wants KeyboardEvent.code
 * position names (its HID then emits the matching USB usage ID and the
 * TARGET OS applies its own layout).
 *
 * Extended key events arrive flagged — Haven's RDP lane encodes them as
 * 16-bit values with the high byte 0xE0 (see [EXT]), e.g. arrows and the
 * right-hand modifiers. Without the E0 flag those base values are the
 * classic numpad keys; with it they mean the navigation cluster. Both
 * worlds map to distinct KeyboardEvent.code names, so the distinction is
 * preserved end to end.
 */
object GlkvmScancodes {

    /** Extended-flag high byte used by the desktop key lane (0xE0 shifted up). */
    const val EXT = 0xE000

    private const val EXT_FLAG = 0xE0

    private val BASE: Map<Int, String> = buildMap {
        put(0x01, "Escape")
        // Digit row 1..0
        for (i in 0..9) put(0x02 + i, "Digit${(i + 1) % 10}")
        put(0x0C, "Minus")
        put(0x0D, "Equal")
        put(0x0E, "Backspace")
        put(0x0F, "Tab")
        // QWERTY top + right
        put(0x10, "KeyQ"); put(0x11, "KeyW"); put(0x12, "KeyE"); put(0x13, "KeyR")
        put(0x14, "KeyT"); put(0x15, "KeyY"); put(0x16, "KeyU"); put(0x17, "KeyI")
        put(0x18, "KeyO"); put(0x19, "KeyP")
        put(0x1A, "BracketLeft"); put(0x1B, "BracketRight")
        put(0x1C, "Enter"); put(0x1D, "ControlLeft")
        // Home row
        put(0x1E, "KeyA"); put(0x1F, "KeyS"); put(0x20, "KeyD"); put(0x21, "KeyF")
        put(0x22, "KeyG"); put(0x23, "KeyH"); put(0x24, "KeyJ"); put(0x25, "KeyK")
        put(0x26, "KeyL")
        put(0x27, "Semicolon"); put(0x28, "Quote"); put(0x29, "Backquote")
        put(0x2A, "ShiftLeft"); put(0x2B, "Backslash")
        // Bottom row
        put(0x2C, "KeyZ"); put(0x2D, "KeyX"); put(0x2E, "KeyC"); put(0x2F, "KeyV")
        put(0x30, "KeyB"); put(0x31, "KeyN"); put(0x32, "KeyM")
        put(0x33, "Comma"); put(0x34, "Period"); put(0x35, "Slash")
        put(0x36, "ShiftRight")
        put(0x37, "NumpadMultiply")
        put(0x38, "AltLeft"); put(0x39, "Space"); put(0x3A, "CapsLock")
        // Function row + lock keys
        for (i in 0..9) put(0x3B + i, "F${i + 1}")
        put(0x45, "NumLock"); put(0x46, "ScrollLock")
        put(0x57, "F11"); put(0x58, "F12")
        // ISO 102nd key
        put(0x56, "IntlBackslash")
        // Numpad (set-1 base codes; the E0 twins are the nav cluster)
        put(0x47, "Numpad7"); put(0x48, "Numpad8"); put(0x49, "Numpad9")
        put(0x4A, "NumpadSubtract"); put(0x4B, "Numpad4"); put(0x4C, "Numpad5")
        put(0x4D, "Numpad6"); put(0x4E, "NumpadAdd")
        put(0x4F, "Numpad1"); put(0x50, "Numpad2"); put(0x51, "Numpad3")
        put(0x52, "Numpad0"); put(0x53, "NumpadDecimal")
    }

    private val EXTENDED: Map<Int, String> = mapOf(
        0x1D to "ControlRight",
        0x35 to "NumpadDivide",
        0x38 to "AltRight",
        0x47 to "Home",
        0x48 to "ArrowUp",
        0x49 to "PageUp",
        0x4B to "ArrowLeft",
        0x4D to "ArrowRight",
        0x4F to "End",
        0x50 to "ArrowDown",
        0x51 to "PageDown",
        0x52 to "Insert",
        0x53 to "Delete",
        0x5B to "MetaLeft",
        0x5C to "MetaRight",
        0x5D to "ContextMenu",
        0x1C to "NumpadEnter",
    )

    /**
     * One KeyboardEvent.code per scancode — the two domains are 1:1.
     * Unknown scancodes return null (caller drops the event).
     */
    fun codeFor(scancode: Int): String? {
        val ext = (scancode shr 8) and 0xFF == EXT_FLAG
        val base = scancode and 0xFF
        return if (ext) EXTENDED[base] else BASE[base]
    }
}