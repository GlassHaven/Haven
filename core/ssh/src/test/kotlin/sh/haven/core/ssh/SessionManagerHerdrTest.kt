package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerHerdrTest {

    @Test
    fun `attach checks for Herdr before starting a named session`() {
        val command = SessionManager.HERDR.command!!("work")

        assertTrue(command.contains("command -v herdr"))
        assertTrue(command.contains("exec herdr --session work"))
    }

    @Test
    fun `session list uses Herdr JSON output`() {
        assertTrue(SessionManager.HERDR.listCommand!!.contains("herdr session list --json"))
    }

    @Test
    fun `remove stops and deletes Herdr state`() {
        val command = SessionManager.HERDR.killCommand!!("work")

        assertTrue(command.contains("herdr session stop work --json"))
        assertTrue(command.contains("herdr session delete work --json"))
        assertFalse(command.contains("session rename"))
    }

    @Test
    fun `parses names from Herdr session JSON`() {
        val output = """
            {"sessions":[
              {"name":"default","default":true,"running":false},
              {"name":"api.work-2","default":false,"running":true}
            ]}
        """.trimIndent()

        assertEquals(
            listOf("default", "api.work-2"),
            SessionManager.parseSessionList(SessionManager.HERDR, output),
        )
    }

    @Test
    fun `malformed Herdr output is ignored`() {
        assertEquals(
            emptyList<String>(),
            SessionManager.parseSessionList(SessionManager.HERDR, "not json"),
        )
    }

    @Test
    fun `parses the real SessionInfo shape with socket and dir fields`() {
        // Shape from herdrdev/herdr src/session.rs SessionInfo + cli.rs session_list.
        val output = """
            {"sessions":[
              {"name":"default","default":true,"running":false,"socket_path":"/run/user/1000/herdr/api.sock","session_dir":"/home/u/.local/share/herdr"},
              {"name":"work","default":false,"running":true,"socket_path":"/run/user/1000/herdr/work/api.sock","session_dir":"/home/u/.local/share/herdr/sessions/work"}
            ]}
        """.trimIndent()

        assertEquals(
            listOf("default", "work"),
            SessionManager.parseSessionList(SessionManager.HERDR, output),
        )
    }

    @Test
    fun `nested name fields do not leak into the session list`() {
        // If the shape ever nests workspaces/tabs/panes (each has a name),
        // only the top-level session names may appear.
        val output = """
            {"sessions":[
              {"name":"work","workspaces":[{"name":"ws1","tabs":[{"name":"shell-1"}]}]}
            ]}
        """.trimIndent()

        assertEquals(
            listOf("work"),
            SessionManager.parseSessionList(SessionManager.HERDR, output),
        )
    }

    @Test
    fun `invalid session names are skipped`() {
        // Herdr validate_name: ASCII alnum . _ - only, max 64 bytes.
        val tooLong = "a".repeat(65)
        val output = """
            {"sessions":[
              {"name":"ok.name-1_x"},
              {"name":"has space"},
              {"name":""},
              {"name":"$tooLong"}
            ]}
        """.trimIndent()

        assertEquals(
            listOf("ok.name-1_x"),
            SessionManager.parseSessionList(SessionManager.HERDR, output),
        )
    }
}
