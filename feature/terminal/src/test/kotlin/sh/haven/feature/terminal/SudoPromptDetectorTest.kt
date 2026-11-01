package sh.haven.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SudoPromptDetectorTest {

    private fun SudoPromptDetector.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        process(bytes, 0, bytes.size)
    }

    @Test
    fun `detects standard sudo prompt`() {
        val detector = SudoPromptDetector()
        detector.feed("[sudo] password for ian: ")
        assertEquals("[sudo] password for ian:", detector.activePrompt.value)
    }

    @Test
    fun `detects sudo prompt with ANSI color sequences`() {
        val detector = SudoPromptDetector()
        detector.feed("\u001b[1;33m[sudo]\u001b[0m password for root: ")
        assertEquals("[sudo] password for root:", detector.activePrompt.value)
    }

    @Test
    fun `detects sudo rs authenticate prompt`() {
        val detector = SudoPromptDetector()
        detector.feed("[sudo: authenticate] Password: ")
        assertEquals("[sudo: authenticate] Password:", detector.activePrompt.value)
    }

    @Test
    fun `detects doas password prompt`() {
        val detector = SudoPromptDetector()
        detector.feed("doas (ian@ router) password: ")
        assertEquals("doas (ian@ router) password:", detector.activePrompt.value)
    }

    @Test
    fun `detects prompt split across byte chunks`() {
        val detector = SudoPromptDetector()
        val text = "\u001b[32m[sudo]\u001b[0m password for ian: "
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (i in bytes.indices) {
            detector.process(bytes, i, 1)
        }
        assertEquals("[sudo] password for ian:", detector.activePrompt.value)
    }

    @Test
    fun `clears active prompt when newline arrives`() {
        val detector = SudoPromptDetector()
        detector.feed("[sudo] password for ian: ")
        assertEquals("[sudo] password for ian:", detector.activePrompt.value)

        detector.feed("\r\n")
        assertNull(detector.activePrompt.value)
    }

    @Test
    fun `re-detects prompt after Sorry try again`() {
        val detector = SudoPromptDetector()
        detector.feed("[sudo] password for ian: \r\nSorry, try again.\r\n[sudo] password for ian: ")
        assertEquals("[sudo] password for ian:", detector.activePrompt.value)
    }

    @Test
    fun `detects bare Password prompt immediately after sudo retry`() {
        val detector = SudoPromptDetector()
        detector.feed("[sudo] password for ian: \r\nSorry, try again.\r\nPassword: ")
        assertEquals("Password:", detector.activePrompt.value)
    }

    @Test
    fun `does not trigger on normal shell prompts or sudo syslog lines`() {
        val detector = SudoPromptDetector()
        detector.feed("ian@haven:~$ ")
        assertNull(detector.activePrompt.value)

        detector.feed("sudo: ian : TTY=pts/0 ; PWD=/home/ian ; USER=root ; COMMAND=/bin/ls:")
        assertNull(detector.activePrompt.value)
    }

    @Test
    fun `clear resets active prompt`() {
        val detector = SudoPromptDetector()
        detector.feed("[sudo] password for ian: ")
        assertEquals("[sudo] password for ian:", detector.activePrompt.value)

        detector.clear()
        assertNull(detector.activePrompt.value)
    }

    @Test
    fun `detects prompt across tmux CSI cursor positioning and status bar redraw`() {
        val detector = SudoPromptDetector()
        detector.feed(
            "ian@haven:~$ sudo ls\r\n\u001b[12;1H[sudo] password for ian: \u001b[43;1H[haven] 0:bash* 12:12\u001b[12;26H",
        )
        assertEquals("[sudo] password for ian:", detector.activePrompt.value)
    }
}
