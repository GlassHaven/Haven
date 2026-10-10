package sh.haven.feature.vnc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bug #5 (desktop VNC keyboard): typing `abcABC123` into the VNC viewer
 * produced duplicated and dropped characters. A raw-RFB capture rig proved
 * the guest Xvnc keymap is clean (a protocol-correct client typed `KT-OK`
 * exactly), so the garble is produced client-side, in the hidden text
 * field's commit diff.
 *
 * The old handler diffed each new value against the reset `" "` sentinel
 * (`newText.substring(oldText.length)`), never against the text the IME
 * actually holds. The rig recorded the emitted strings for `abcABC123` as
 * `a, c, A, AB, C, C1, C12` — 'b' never emitted, 'A' twice, 'C' three
 * times — the signature of slicing a growing IME buffer at a fixed offset.
 *
 * [diffImeCommit] trims the common prefix and common suffix between the
 * last value the IME reported and the new one, so the delta is exactly
 * what changed whether the IME appended, replaced a word (autocorrect), or
 * edited mid-string. The handler must call it with the IME's last reported
 * text as the baseline, not the reset sentinel.
 */
class VncImeDiffTest {

    @Test
    fun `appending one character to the empty field emits just that character`() {
        assertEquals("a" to 0, diffImeCommit("", "a"))
    }

    @Test
    fun `a leading space is typed, not swallowed`() {
        // The old sentinel was " ", so a real typed space collided with it;
        // the field now starts empty and a space is just another character.
        assertEquals(" " to 0, diffImeCommit("", " "))
        assertEquals("h" to 0, diffImeCommit(" ", " h"))
    }

    @Test
    fun `unchanged value emits nothing`() {
        assertEquals(null to 0, diffImeCommit("abc", "abc"))
    }

    @Test
    fun `a growing buffer never re-emits earlier characters`() {
        // The regression: with a stale (sentinel) baseline each commit
        // re-slices the buffer and re-emits its leading characters. With
        // the IME's last text as the baseline, each commit yields only the
        // newly added tail.
        var last = ""
        val emitted = mutableListOf<String>()
        for (newText in listOf("a", "ab", "abc", "abcA", "abcAB", "abcABC", "abcABC1", "abcABC12", "abcABC123")) {
            val (added, deleted) = diffImeCommit(last, newText)
            assertEquals(0, deleted)
            if (added != null) emitted.add(added)
            last = newText
        }
        assertEquals(listOf("a", "b", "c", "A", "B", "C", "1", "2", "3"), emitted)
    }

    @Test
    fun `the stale-sentinel baseline reproduces the captured re-emission`() {
        // Documents the bug: diffing every commit against the 1-char
        // sentinel (the old `newText.substring(1)`) re-emits the buffer's
        // leading characters — the `C, C1, C12` signature from the rig.
        val buffer = listOf("C", "C1", "C12")
        val stale = buffer.map { it.substring(1) }
        assertEquals(listOf("", "1", "12"), stale)
        // With the correct baseline the same commits emit only the tail.
        var last = ""
        val correct = buffer.map { newText ->
            val added = diffImeCommit(last, newText).first
            last = newText
            added
        }
        assertEquals(listOf("C", "1", "2"), correct)
    }

    @Test
    fun `backspace deletes exactly the removed characters`() {
        assertEquals(null to 2, diffImeCommit("hello", "hel"))
    }

    @Test
    fun `a full clear is backspaces not an insertion`() {
        assertEquals(null to 3, diffImeCommit("abc", ""))
    }

    @Test
    fun `autocorrect word replacement emits the minimal edit`() {
        // Gboard can swap a committed word in a single commit. Prefix/suffix
        // trimming sees the changed interior: "teh" -> "the" keeps the
        // common "t", so the remote needs 2 backspaces then "he".
        assertEquals("he" to 2, diffImeCommit("teh", "the"))
    }

    @Test
    fun `mid-string insertion emits only the inserted run`() {
        // "horld" -> "hlowrld": common prefix "h", common suffix "rld",
        // so the changed interior is "o" replaced by "low".
        assertEquals("low" to 1, diffImeCommit("horld", "hlowrld"))
    }
}