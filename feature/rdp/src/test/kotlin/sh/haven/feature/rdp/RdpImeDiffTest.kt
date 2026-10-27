package sh.haven.feature.rdp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The RDP hidden text field must diff each IME commit against the text the IME
 * held before it, never against a reset sentinel. The sentinel version sliced a
 * growing IME buffer at a fixed offset, so typing produced dropped characters
 * and long runs of re-emitted ones — the same defect VNC fixed in v5.89.24
 * (VncImeDiffTest, bug #5), still live in the RDP/SPICE/GLKVM screen.
 */
class RdpImeDiffTest {

    @Test
    fun `appending one character to the empty field emits just that character`() {
        assertEquals("a" to 0, diffImeCommit("", "a"))
    }

    @Test
    fun `unchanged value emits nothing`() {
        assertEquals(null to 0, diffImeCommit("abc", "abc"))
    }

    @Test
    fun `a growing buffer never re-emits earlier characters`() {
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
    fun `composing then committing a CJK character emits only the committed character`() {
        // Sogou reports the pinyin as composing text, then replaces the whole
        // composing run with the character. One commit, one character.
        assertEquals("ni" to 0, diffImeCommit("", "ni"))
        assertEquals("你" to 2, diffImeCommit("ni", "你"))
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
        assertEquals("he" to 2, diffImeCommit("teh", "the"))
    }

    @Test
    fun `mid-string insertion emits only the inserted run`() {
        assertEquals("low" to 1, diffImeCommit("horld", "hlowrld"))
    }
}
