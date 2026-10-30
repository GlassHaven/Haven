package sh.haven.feature.rdp

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #422: arrow keys were sent as bare Set-1 scancodes. Those values are the
 * *numpad* twins of the navigation cluster — the real keys are E0-prefixed —
 * so Haven pressed the wrong key on every server.
 *
 * (The marker was NOT what made VirtualBox drop connections — VRDP rejects
 * any lone fast-path scancode PDU because it never advertises fast-path
 * input; the native layer handles that separately with a slow-path input
 * fallback. The marker is still required to press the right key.)
 *
 * The native layer detects extended keys with `code and 0xE000 == 0xE000`
 * (ironrdp `Scancode::from_u16`) and sets KBDFLAGS_EXTENDED from it, so the
 * marker is the whole contract between these constants and the wire.
 */
class RdpScancodeTest {

    private fun isExtended(code: Int) = (code and 0xE000) == 0xE000

    /** Every key that is E0-prefixed on a real keyboard must carry the marker. */
    @Test
    fun `navigation cluster and Windows key are extended`() {
        val extended = mapOf(
            "Up" to SC_UP, "Down" to SC_DOWN, "Left" to SC_LEFT, "Right" to SC_RIGHT,
            "Home" to SC_HOME, "End" to SC_END, "PgUp" to SC_PGUP, "PgDn" to SC_PGDN,
            "Insert" to SC_INSERT, "Delete" to SC_DELETE, "LeftWin" to SC_WIN_L,
        )
        extended.forEach { (name, code) ->
            assertTrue("$name (0x${code.toString(16)}) must set the 0xE000 extended marker", isExtended(code))
        }
    }

    /**
     * The constants above are only half the story: the bug was in the
     * *mapping*, which sent both Alt keys to the same constant. This is the
     * test that fails on the shipped code.
     */
    @Test
    fun `each right-hand modifier maps to its own scancode`() {
        assertEquals(SC_ALT_R, androidKeyToScancode(Key.AltRight))
        assertEquals(SC_CTRL_R, androidKeyToScancode(Key.CtrlRight))
        assertEquals(SC_SHIFT_R, androidKeyToScancode(Key.ShiftRight))
        assertEquals(SC_WIN_R, androidKeyToScancode(Key.MetaRight))

        assertEquals(SC_ALT_L, androidKeyToScancode(Key.AltLeft))
        assertEquals(SC_CTRL_L, androidKeyToScancode(Key.CtrlLeft))
        assertEquals(SC_SHIFT_L, androidKeyToScancode(Key.ShiftLeft))
        assertEquals(SC_WIN_L, androidKeyToScancode(Key.MetaLeft))
    }

    /**
     * #504: right-hand modifiers are separate keys, not aliases for the left
     * ones.
     *
     * A reporter ran `showkey` on his guest's console and reported AltGr
     * arriving as scancode 56 — that is 0x38, *left* Alt. Both Alt keys were
     * mapped to the same constant. On a Polish layout AltGr+o is ó, and the
     * guest was being told he had pressed a modifier that composes nothing, so
     * the character simply never appeared.
     *
     * The measurement is what made this findable: "56, six times" is a fact
     * about the wire, not a description of a symptom.
     */
    @Test
    fun `right-hand modifiers differ from their left-hand twins`() {
        assertNotEquals("AltGr must not be sent as left Alt", SC_ALT_L, SC_ALT_R)
        assertNotEquals("right Ctrl must not be sent as left Ctrl", SC_CTRL_L, SC_CTRL_R)
        assertNotEquals("right Shift must not be sent as left Shift", SC_SHIFT_L, SC_SHIFT_R)
        assertNotEquals("right Win must not be sent as left Win", SC_WIN_L, SC_WIN_R)
    }

    /**
     * Right Ctrl, Alt and Win are E0-prefixed. Right **Shift** is not — it is
     * its own base code, 0x36, and marking it extended would press something
     * else. That asymmetry is the easy thing to get wrong here.
     */
    @Test
    fun `right Ctrl Alt and Win are extended, right Shift is not`() {
        assertTrue("AltGr (right Alt) is E0-prefixed", isExtended(SC_ALT_R))
        assertTrue("right Ctrl is E0-prefixed", isExtended(SC_CTRL_R))
        assertTrue("right Win is E0-prefixed", isExtended(SC_WIN_R))
        assertFalse("right Shift is a base scancode, not an extended one", isExtended(SC_SHIFT_R))
        assertEquals("right Shift is 0x36", 0x36, SC_SHIFT_R)
    }

    /**
     * The low byte is what reaches the guest — `Scancode::from_u16` truncates
     * to `scancode as u8` and carries the extended bit separately. So AltGr
     * must be 0x38 *with* the marker, which is a different key from 0x38
     * without it.
     */
    @Test
    fun `the extended modifiers keep the right base code`() {
        assertEquals("AltGr base code", 0x38, SC_ALT_R and 0xFF)
        assertEquals("right Ctrl base code", 0x1D, SC_CTRL_R and 0xFF)
        assertEquals("right Win base code", 0x5C, SC_WIN_R and 0xFF)
    }

    /**
     * The other half of the contract: marking a key extended that is not
     * would press a different key just as wrongly, so the ordinary keys must
     * stay bare.
     */
    @Test
    fun `character control and function keys are not extended`() {
        val plain = mapOf(
            "Escape" to SC_ESCAPE, "Backspace" to SC_BACKSPACE, "Tab" to SC_TAB,
            "Return" to SC_RETURN, "LeftCtrl" to SC_CTRL_L, "LeftShift" to SC_SHIFT_L,
            "LeftAlt" to SC_ALT_L, "F1" to SC_F1, "F12" to SC_F12,
        )
        plain.forEach { (name, code) ->
            assertTrue("$name (0x${code.toString(16)}) must NOT be marked extended", !isExtended(code))
        }
    }

    /**
     * The low byte is what actually reaches the wire as the scancode; the
     * marker must not have disturbed it. These are the Set-1 values for the
     * navigation cluster.
     */
    @Test
    fun `the extended marker leaves the underlying scancode intact`() {
        assertEquals(0x48, SC_UP and 0xFF)
        assertEquals(0x50, SC_DOWN and 0xFF)
        assertEquals(0x4B, SC_LEFT and 0xFF)
        assertEquals(0x4D, SC_RIGHT and 0xFF)
        assertEquals(0x47, SC_HOME and 0xFF)
        assertEquals(0x4F, SC_END and 0xFF)
        assertEquals(0x49, SC_PGUP and 0xFF)
        assertEquals(0x51, SC_PGDN and 0xFF)
        assertEquals(0x52, SC_INSERT and 0xFF)
        assertEquals(0x53, SC_DELETE and 0xFF)
        assertEquals(0x5B, SC_WIN_L and 0xFF)
    }

    /**
     * #507 — no numpad key was mapped at all, so every one of them fell through
     * to `else -> null` and reached the guest as nothing. Silent on a phone;
     * visible the moment someone attaches a real keyboard, which is the same
     * situation that produced the report.
     */
    @Test
    fun `every numpad key maps to a scancode`() {
        val numpad = mapOf(
            "NumPad0" to Key.NumPad0, "NumPad1" to Key.NumPad1, "NumPad2" to Key.NumPad2,
            "NumPad3" to Key.NumPad3, "NumPad4" to Key.NumPad4, "NumPad5" to Key.NumPad5,
            "NumPad6" to Key.NumPad6, "NumPad7" to Key.NumPad7, "NumPad8" to Key.NumPad8,
            "NumPad9" to Key.NumPad9, "NumPadEnter" to Key.NumPadEnter,
            "NumPadDivide" to Key.NumPadDivide, "NumPadMultiply" to Key.NumPadMultiply,
            "NumPadSubtract" to Key.NumPadSubtract, "NumPadAdd" to Key.NumPadAdd,
            "NumPadDot" to Key.NumPadDot, "NumLock" to Key.NumLock,
        )
        val unmapped = numpad.filterValues { androidKeyToScancode(it) == null }.keys
        assertTrue("these numpad keys reach the guest as nothing: $unmapped", unmapped.isEmpty())
    }

    /**
     * The subtle half. Numpad Enter and Divide ARE E0-prefixed; the rest of the
     * numpad is not. Getting this backwards is the #422 bug in mirror image —
     * there, navigation keys were sent bare and pressed their numpad twins.
     */
    @Test
    fun `only numpad Enter and Divide are extended`() {
        assertTrue("numpad Enter is E0-prefixed", isExtended(SC_NUMPAD_ENTER))
        assertTrue("numpad Divide is E0-prefixed", isExtended(SC_NUMPAD_DIVIDE))

        val bare = mapOf(
            "0" to SC_NUMPAD_0, "1" to SC_NUMPAD_1, "2" to SC_NUMPAD_2, "3" to SC_NUMPAD_3,
            "4" to SC_NUMPAD_4, "5" to SC_NUMPAD_5, "6" to SC_NUMPAD_6, "7" to SC_NUMPAD_7,
            "8" to SC_NUMPAD_8, "9" to SC_NUMPAD_9, "." to SC_NUMPAD_DOT,
            "+" to SC_NUMPAD_ADD, "-" to SC_NUMPAD_SUBTRACT, "*" to SC_NUMPAD_MULTIPLY,
            "NumLock" to SC_NUMLOCK,
        )
        bare.forEach { (name, code) ->
            assertFalse(
                "numpad $name must NOT be extended — the extended twin is the navigation key",
                isExtended(code),
            )
        }
    }

    /**
     * Numpad Enter must not collapse onto Return, and the numpad digits must not
     * collapse onto the navigation cluster. Both would be silent: the key would
     * appear to work while pressing something else.
     */
    @Test
    fun `numpad keys are distinct from the keys they share bare codes with`() {
        assertNotEquals("numpad Enter is not Return", SC_RETURN, SC_NUMPAD_ENTER)
        assertNotEquals("numpad 8 is not Up", SC_UP, SC_NUMPAD_8)
        assertNotEquals("numpad 2 is not Down", SC_DOWN, SC_NUMPAD_2)
        assertNotEquals("numpad 4 is not Left", SC_LEFT, SC_NUMPAD_4)
        assertNotEquals("numpad 6 is not Right", SC_RIGHT, SC_NUMPAD_6)
        assertNotEquals("numpad 7 is not Home", SC_HOME, SC_NUMPAD_7)
        assertNotEquals("numpad 1 is not End", SC_END, SC_NUMPAD_1)
        assertNotEquals("numpad 0 is not Insert", SC_INSERT, SC_NUMPAD_0)
        assertNotEquals("numpad . is not Delete", SC_DELETE, SC_NUMPAD_DOT)
    }

    /**
     * #504 regression on v5.87.11+ — the focus fix moved hardware-key focus
     * off the hidden text field, whose IME InputConnection had been the only
     * path converting printable keypresses. From then on, any key absent from
     * androidKeyToScancode reached the guest as nothing: pawlosck's report is
     * the mapping table read back verbatim — numpad, F-keys, modifiers,
     * Enter/Esc/Backspace all work (mapped), letters and digits are dead
     * (unmapped). This test fails on the shipped code.
     */
    @Test
    fun `every letter digit and punctuation key maps to a scancode`() {
        val printable = mapOf(
            "A" to Key.A, "M" to Key.M, "Z" to Key.Z,
            "One" to Key.One, "Zero" to Key.Zero,
            "Space" to Key.Spacebar, "Grave" to Key.Grave, "Minus" to Key.Minus,
            "Equals" to Key.Equals, "LeftBracket" to Key.LeftBracket,
            "RightBracket" to Key.RightBracket, "Backslash" to Key.Backslash,
            "Semicolon" to Key.Semicolon, "Apostrophe" to Key.Apostrophe,
            "Comma" to Key.Comma, "Period" to Key.Period, "Slash" to Key.Slash,
        )
        val unmapped = printable.filterValues { androidKeyToScancode(it) == null }.keys
        assertTrue("these keys reach the guest as nothing: $unmapped", unmapped.isEmpty())
    }

    /**
     * The hardware-key table and the soft-keyboard char table describe the
     * same physical keyboard, so they must agree scancode-for-scancode. A
     * typo in one is invisible at the type level; this is what catches it.
     */
    @Test
    fun `hardware key table agrees with the soft-keyboard char table`() {
        val letterKeys = listOf(
            Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I,
            Key.J, Key.K, Key.L, Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R,
            Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
        )
        ('a'..'z').forEachIndexed { i, ch ->
            assertEquals(
                "letter '$ch': hardware and char tables disagree",
                asciiCharToRdpScancode(ch)!!.first,
                androidKeyToScancode(letterKeys[i]),
            )
        }
        val digitKeys = mapOf(
            '0' to Key.Zero, '1' to Key.One, '2' to Key.Two, '3' to Key.Three,
            '4' to Key.Four, '5' to Key.Five, '6' to Key.Six, '7' to Key.Seven,
            '8' to Key.Eight, '9' to Key.Nine,
        )
        digitKeys.forEach { (ch, key) ->
            assertEquals(
                "digit '$ch': hardware and char tables disagree",
                asciiCharToRdpScancode(ch)!!.first,
                androidKeyToScancode(key),
            )
        }
        val punctuation = mapOf(
            ' ' to Key.Spacebar, '`' to Key.Grave, '-' to Key.Minus,
            '=' to Key.Equals, '[' to Key.LeftBracket, ']' to Key.RightBracket,
            '\\' to Key.Backslash, ';' to Key.Semicolon, '\'' to Key.Apostrophe,
            ',' to Key.Comma, '.' to Key.Period, '/' to Key.Slash,
        )
        punctuation.forEach { (ch, key) ->
            assertEquals(
                "'$ch': hardware and char tables disagree",
                asciiCharToRdpScancode(ch)!!.first,
                androidKeyToScancode(key),
            )
        }
    }

    /**
     * Base scancodes on purpose: physical Shift/AltGr arrive as their own
     * modifier scancodes, so the guest composes shifted characters itself.
     * An extended marker here would press a different key entirely.
     */
    @Test
    fun `printable keys are bare base scancodes`() {
        listOf(Key.A, Key.Z, Key.One, Key.Zero, Key.Spacebar, Key.Slash).forEach { key ->
            val code = androidKeyToScancode(key)!!
            assertFalse("0x${code.toString(16)} must not be extended", isExtended(code))
        }
    }

    /** Every numpad scancode is its own key — a duplicate means two keys collide. */
    @Test
    fun `numpad scancodes are unique`() {
        val all = listOf(
            SC_NUMPAD_0, SC_NUMPAD_1, SC_NUMPAD_2, SC_NUMPAD_3, SC_NUMPAD_4,
            SC_NUMPAD_5, SC_NUMPAD_6, SC_NUMPAD_7, SC_NUMPAD_8, SC_NUMPAD_9,
            SC_NUMPAD_DOT, SC_NUMPAD_ADD, SC_NUMPAD_SUBTRACT, SC_NUMPAD_MULTIPLY,
            SC_NUMPAD_DIVIDE, SC_NUMPAD_ENTER, SC_NUMLOCK,
        )
        assertEquals("two numpad keys share a scancode", all.size, all.toSet().size)
    }

    @Test
    fun `vtermKeyToScancode maps nav and function keys to Set-1 scancodes`() {
        assertEquals(SC_UP, vtermKeyToScancode(5))
        assertEquals(SC_DOWN, vtermKeyToScancode(6))
        assertEquals(SC_LEFT, vtermKeyToScancode(7))
        assertEquals(SC_RIGHT, vtermKeyToScancode(8))
        assertEquals(SC_INSERT, vtermKeyToScancode(9))
        assertEquals(SC_DELETE, vtermKeyToScancode(10))
        assertEquals(SC_HOME, vtermKeyToScancode(11))
        assertEquals(SC_END, vtermKeyToScancode(12))
        assertEquals(SC_PGUP, vtermKeyToScancode(13))
        assertEquals(SC_PGDN, vtermKeyToScancode(14))
        assertEquals(SC_F1, vtermKeyToScancode(257))
        assertEquals(SC_F12, vtermKeyToScancode(268))
    }

    @Test
    fun `handleRdpToolbarBytes translates Esc Tab Enter ShiftTab and snippets`() {
        val events = mutableListOf<String>()
        var released = 0
        fun send(bytes: ByteArray, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
            handleRdpToolbarBytes(
                bytes = bytes,
                ctrlActive = ctrl,
                altActive = alt,
                shiftActive = shift,
                onKeyDown = { events.add("down:$it") },
                onKeyUp = { events.add("up:$it") },
                onTypeChar = { events.add("char:$it") },
                onTypeText = { events.add("text:$it") },
                releaseModifiers = { released++ },
            )
        }
        send(byteArrayOf(0x1b))
        assertEquals(listOf("down:$SC_ESCAPE", "up:$SC_ESCAPE"), events)
        events.clear()

        send("\u001b[Z".toByteArray())
        assertEquals(listOf("down:$SC_SHIFT_L", "down:$SC_TAB", "up:$SC_TAB", "up:$SC_SHIFT_L"), events)
        events.clear()

        send("mcp".toByteArray())
        assertEquals(listOf("text:mcp"), events)
        assertEquals(3, released)
        events.clear()

        // #772: single-character toolbar key while Win is armed routes via
        // onTypeChar (scancode tap so held SC_WIN_L composes Win+key) and
        // releases active modifiers.
        handleRdpToolbarBytes(
            bytes = "r".toByteArray(),
            ctrlActive = false,
            altActive = false,
            shiftActive = false,
            winActive = true,
            onKeyDown = { events.add("down:$it") },
            onKeyUp = { events.add("up:$it") },
            onTypeChar = { events.add("char:$it") },
            onTypeText = { events.add("text:$it") },
            releaseModifiers = { released++ },
        )
        assertEquals(listOf("char:r"), events)
        assertEquals(4, released)
    }
}
