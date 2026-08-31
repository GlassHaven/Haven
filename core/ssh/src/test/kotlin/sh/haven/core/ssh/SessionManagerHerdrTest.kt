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
}
