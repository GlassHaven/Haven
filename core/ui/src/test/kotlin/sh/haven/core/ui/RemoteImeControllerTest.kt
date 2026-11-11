package sh.haven.core.ui

import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression suite for #606: soft-keyboard number bar (`0`–`9`), shifted
 * capitals, symbols, unpaired `KEYCODE_SHIFT_LEFT` events, Backspace/Enter
 * buffer sync, and AOSP same-turn `commitText` + `KeyEvent` echo deduplication.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteImeControllerTest {

    private class Harness {
        val controller = RemoteImeController()
        val events = mutableListOf<String>()
        var hasActiveModifier = false
        var modifierReleases = 0

        fun commit(newText: String): String = controller.onValueChanged(
            newText = newText,
            hasActiveModifier = hasActiveModifier,
            onBackspace = { events += "BS" },
            onTypeChar = { events += "char:$it" },
            onTypeText = { events += "text:$it" },
            releaseActiveModifiers = {
                modifierReleases++
                hasActiveModifier = false
            },
        )

        fun sendKey(
            action: Int,
            keyCode: Int,
            metaState: Int = 0,
            repeat: Int = 0,
            specialHandler: ((KeyEvent) -> Boolean)? = { ev ->
                events += "special:${ev.nativeKeyEvent.action}:${ev.nativeKeyEvent.keyCode}"
                true
            },
        ): Boolean = controller.onPreviewKeyEvent(
            event = KeyEvent(AndroidKeyEvent(0L, 0L, action, keyCode, repeat, metaState)),
            hasActiveModifier = hasActiveModifier,
            onBackspace = { events += "BS" },
            onTypeChar = { events += "char:$it" },
            onTypeText = { events += "text:$it" },
            releaseActiveModifiers = {
                modifierReleases++
                hasActiveModifier = false
            },
            onSpecialKey = specialHandler,
        )

        fun tapKey(keyCode: Int, metaState: Int = 0) {
            assertTrue(sendKey(AndroidKeyEvent.ACTION_DOWN, keyCode, metaState))
            assertTrue(sendKey(AndroidKeyEvent.ACTION_UP, keyCode, metaState))
        }
    }

    @Test
    fun numberBarSendKeyEventsEmitEveryDigit() {
        val h = Harness()
        for (code in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9) {
            h.tapKey(code)
        }
        assertEquals(
            (0..9).map { "text:$it" },
            h.events,
        )
        assertEquals("0123456789", h.controller.text)
    }

    @Test
    fun shiftedCapitalsAndSymbolsViaSendKeyEventEmitOnFirstPressWithoutLeakingShift() {
        val h = Harness()
        // Soft keyboard presses Shift, taps 'A', taps '!', taps '@', releases Shift.
        assertFalse(h.sendKey(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_SHIFT_LEFT))
        h.tapKey(AndroidKeyEvent.KEYCODE_A, AndroidKeyEvent.META_SHIFT_ON or AndroidKeyEvent.META_SHIFT_LEFT_ON)
        h.tapKey(AndroidKeyEvent.KEYCODE_1, AndroidKeyEvent.META_SHIFT_ON or AndroidKeyEvent.META_SHIFT_LEFT_ON)
        h.tapKey(AndroidKeyEvent.KEYCODE_AT)
        h.tapKey(AndroidKeyEvent.KEYCODE_POUND)
        h.tapKey(AndroidKeyEvent.KEYCODE_STAR)
        h.tapKey(AndroidKeyEvent.KEYCODE_MINUS, AndroidKeyEvent.META_SHIFT_ON)
        h.tapKey(AndroidKeyEvent.KEYCODE_SLASH, AndroidKeyEvent.META_SHIFT_ON)
        assertFalse(h.sendKey(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_SHIFT_LEFT))

        assertEquals(
            listOf("text:A", "text:!", "text:@", "text:#", "text:*", "text:_", "text:?"),
            h.events,
        )
        assertEquals("A!@#*_?", h.controller.text)
    }

    @Test
    fun unpairedSoftShiftDownDoesNotLeakShiftOrCorruptSubsequentCommitTextSymbols() {
        val h = Harness()
        // IME sends KEYCODE_SHIFT_LEFT ACTION_DOWN, then commits "A" via commitText
        // without sending KEYCODE_SHIFT_LEFT ACTION_UP, followed by unshifted ";" and "/".
        assertFalse(h.sendKey(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_SHIFT_LEFT))
        h.commit("A")
        h.commit("A;")
        h.commit("A;/")

        assertEquals(listOf("text:A", "text:;", "text:/"), h.events)
        assertTrue("bare soft Shift must never reach onSpecialKey", h.events.none { it.startsWith("special:") })
    }

    @Test
    fun duplicateKeyEventEchoAfterCommitTextIsSuppressedUntilTwoHopDrain() {
        val h = Harness()
        // AOSP Spanish (#606): commitText("q") followed by synthetic KEYCODE_Q down/repeats/up.
        h.commit("q")
        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_Q))
        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_Q, repeat = 1))
        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_Q, repeat = 2))
        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_Q))

        assertEquals("commit + synthetic echo must emit 'q' once", listOf("text:q"), h.events)
        assertEquals("q", h.controller.text)

        // Drain the two-hop main-looper post; a new sendKeyEvent(KEYCODE_Q) must now emit.
        shadowOf(Looper.getMainLooper()).idle()
        h.tapKey(AndroidKeyEvent.KEYCODE_Q)
        assertEquals(listOf("text:q", "text:q"), h.events)
        assertEquals("qq", h.controller.text)
    }

    @Test
    fun nonMatchingSendKeyEventRightAfterCommitTextIsNotSwallowed() {
        val h = Harness()
        h.commit("a")
        // Immediately tap '5' on the number row before the two-hop drain fires.
        h.tapKey(AndroidKeyEvent.KEYCODE_5)

        assertEquals(listOf("text:a", "text:5"), h.events)
        assertEquals("a5", h.controller.text)
    }

    @Test
    fun backspaceShrinksBufferIncludingSurrogatePairsSoNextCommitDiffsCleanly() {
        val h = Harness()
        h.commit("a\uD83D\uDE00") // "a😀"
        h.events.clear()

        // Soft keyboard sends KEYCODE_DEL via sendKeyEvent.
        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DEL))
        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DEL))
        assertEquals(listOf("BS"), h.events)
        assertEquals("a", h.controller.text)

        // Next commit appends 'b' onto "a" -> must emit only 'b' with zero extra backspaces.
        h.events.clear()
        h.commit("ab")
        assertEquals(listOf("text:b"), h.events)

        // Backspace on an already-empty buffer still forwards BS to the remote host.
        h.commit("")
        h.events.clear()
        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DEL))
        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DEL))
        assertEquals(listOf("BS"), h.events)
        assertEquals("", h.controller.text)
    }

    @Test
    fun enterClearsBufferAndDelegatesToSpecialKeyHandler() {
        val h = Harness()
        h.commit("admin")
        h.events.clear()
        h.hasActiveModifier = true

        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER))
        assertEquals("", h.controller.text)
        assertTrue(h.sendKey(AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_ENTER))

        assertEquals(
            listOf(
                "special:${AndroidKeyEvent.ACTION_DOWN}:${AndroidKeyEvent.KEYCODE_ENTER}",
                "special:${AndroidKeyEvent.ACTION_UP}:${AndroidKeyEvent.KEYCODE_ENTER}",
            ),
            h.events,
        )
        assertFalse(h.hasActiveModifier)
    }

    @Test
    fun activeToolbarModifierRoutesSingleCharacterThroughOnTypeCharAndReleases() {
        val h = Harness()
        h.hasActiveModifier = true
        h.tapKey(AndroidKeyEvent.KEYCODE_2)
        assertEquals(listOf("char:2"), h.events)
        assertFalse(h.hasActiveModifier)

        h.events.clear()
        h.hasActiveModifier = true
        h.commit("2c")
        assertEquals(listOf("char:c"), h.events)
        assertFalse(h.hasActiveModifier)
    }

    @Test
    fun ctrlChordDelegatesToSpecialKeyInsteadOfTypingLiteralCharacter() {
        val h = Harness()
        assertTrue(
            h.sendKey(
                AndroidKeyEvent.ACTION_DOWN,
                AndroidKeyEvent.KEYCODE_C,
                metaState = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_CTRL_LEFT_ON,
            ),
        )
        assertEquals(
            listOf("special:${AndroidKeyEvent.ACTION_DOWN}:${AndroidKeyEvent.KEYCODE_C}"),
            h.events,
        )
        assertEquals("", h.controller.text)
    }
}
