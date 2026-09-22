package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerPsmuxTest {

    @Test
    fun `attach checks for psmux then tries attach before creating a named session`() {
        val command = SessionManager.PSMUX.command!!("work")

        assertTrue(command.contains("command -v psmux"))
        assertTrue(command.contains("psmux attach -t work"))
        assertTrue(command.contains("psmux new-session -s work"))
    }

    @Test
    fun `session list uses psmux ls`() {
        assertTrue(SessionManager.PSMUX.listCommand!!.contains("psmux ls"))
    }

    @Test
    fun `remove kills the psmux session by target`() {
        val command = SessionManager.PSMUX.killCommand!!("work")

        assertTrue(command.contains("psmux kill-session -t work"))
    }

    @Test
    fun `rename moves the psmux session to the new name`() {
        val command = SessionManager.PSMUX.renameCommand!!("old", "new")

        assertTrue(command.contains("psmux rename-session -t old new"))
    }

    @Test
    fun `parses names from plain psmux ls lines`() {
        val output = "main\nwork\n"

        assertEquals(
            listOf("main", "work"),
            SessionManager.parseSessionList(SessionManager.PSMUX, output),
        )
    }

    @Test
    fun `blank psmux ls output parses to empty`() {
        assertEquals(
            emptyList<String>(),
            SessionManager.parseSessionList(SessionManager.PSMUX, "\n"),
        )
    }
}
