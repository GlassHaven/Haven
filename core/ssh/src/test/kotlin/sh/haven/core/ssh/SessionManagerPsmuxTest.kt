package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `session list asks for bare names so the tmux-style default never reaches the parser`() {
        val listCommand = SessionManager.PSMUX.listCommand!!

        assertTrue(listCommand.contains("psmux ls -F"))
        assertTrue(listCommand.contains("#{session_name}"))
    }

    @Test
    fun `remove kills the psmux session by target`() {
        val command = SessionManager.PSMUX.killCommand!!("work")

        assertTrue(command.contains("psmux kill-session -t work"))
    }

    @Test
    fun `rename is not advertised because psmux has no target-based rename`() {
        assertNull(SessionManager.PSMUX.renameCommand)
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

    @Test
    fun `rmux attach checks PATH including local and cargo bin and enables passthrough and mouse`() {
        val command = SessionManager.RMUX.command!!("work")

        assertTrue(command.contains(".local/bin"))
        assertTrue(command.contains(".cargo/bin"))
        assertTrue(command.contains("command -v rmux"))
        assertTrue(command.contains("rmux new-session -A -s work"))
        assertTrue(command.contains("set -gq allow-passthrough on"))
        assertTrue(command.contains("set -gq mouse on"))
    }

    @Test
    fun `rmux list kill and rename commands and parser work`() {
        assertTrue(SessionManager.RMUX.listCommand!!.contains("rmux ls -F \"#{session_name}\""))
        assertTrue(SessionManager.RMUX.killCommand!!("work").contains("rmux kill-session -t work"))
        assertTrue(SessionManager.RMUX.renameCommand!!("old", "new").contains("rmux rename-session -t old new"))
        assertEquals(
            listOf("alpha", "beta"),
            SessionManager.parseSessionList(SessionManager.RMUX, "alpha\nbeta\n"),
        )
    }
}
