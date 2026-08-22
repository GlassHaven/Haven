package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #366 — send_terminal_input only tried SSH + local, so input to a mosh/ET/
 * Reticulum session failed "No local session" while snapshot reads resolved
 * the same id. Pins the registry dispatcher: input reaches whichever
 * transport owns the session, and the no-owner error names all transports.
 *
 * Uses fake [TransportSessionManager]s rather than the real managers. Since
 * #510 the registry knows only that interface — which is what lets `:core:ssh`
 * stop depending on every transport module — so the dispatcher is testable
 * without them. That the real transports are actually bound is a separate
 * question, pinned by `TransportBindingsTest` in `:app`.
 */
class SessionManagerRegistryInputTest {

    /** A transport that never owns the id, answering like a real manager does. */
    private class Disowning(
        override val transport: Transport,
        override val inputName: String,
    ) : TransportSessionManager {
        override fun removeAllSessionsForProfile(profileId: String) = Unit
    }

    /** A transport that accepts the id and records what it was given. */
    private class Owning(
        override val transport: Transport,
        override val inputName: String,
    ) : TransportSessionManager {
        var received: Pair<String, String>? = null
        override fun removeAllSessionsForProfile(profileId: String) = Unit
        override fun sendInput(sessionId: String, text: String) {
            received = sessionId to text
        }
    }

    /** A transport that owns the id but cannot serve it — a real diagnosis. */
    private class Diagnosing(
        override val transport: Transport,
        override val inputName: String,
        private val message: String,
    ) : TransportSessionManager {
        override fun removeAllSessionsForProfile(profileId: String) = Unit
        override fun sendInput(sessionId: String, text: String): Nothing =
            throw IllegalStateException(message)
    }

    private val names = mapOf(
        Transport.SSH to "SSH",
        Transport.LOCAL to "local",
        Transport.MOSH to "mosh",
        Transport.ET to "ET",
        Transport.RETICULUM to "Reticulum",
        Transport.BTSERIAL to "Bluetooth-serial",
        Transport.BLESERIAL to "BLE-serial",
        Transport.USBSERIAL to "USB-serial",
    )

    private fun registry(vararg overrides: TransportSessionManager): SessionManagerRegistry {
        val overridden = overrides.map { it.transport }.toSet()
        val rest = names.filterKeys { it !in overridden }.map { (t, n) -> Disowning(t, n) }
        return SessionManagerRegistry((rest + overrides).toSet(), keepAlives = emptySet())
    }

    @Test
    fun `input reaches a mosh-owned session`() {
        val mosh = Owning(Transport.MOSH, "mosh")

        registry(mosh).sendTerminalInput("s1", "ls\r")

        assertEquals("s1" to "ls\r", mosh.received)
    }

    @Test
    fun `input reaches an ET-owned session`() {
        val et = Owning(Transport.ET, "ET")

        registry(et).sendTerminalInput("s1", "x")

        assertEquals("s1" to "x", et.received)
    }

    @Test
    fun `no owner names all transports`() {
        try {
            registry().sendTerminalInput("s1", "x")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            val msg = e.message ?: ""
            for (t in names.values) {
                assertTrue("error should name $t: \"$msg\"", msg.contains(t))
            }
        }
    }

    @Test
    fun `owner's diagnosis wins over not-mine errors`() {
        val ssh = Diagnosing(
            Transport.SSH, "SSH",
            "Session s1 has no active terminal — open a terminal tab first",
        )

        try {
            registry(ssh).sendTerminalInput("s1", "x")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "expected the SSH diagnosis, got \"${e.message}\"",
                e.message!!.contains("has no active terminal"),
            )
        }
    }

    /**
     * #555: `send_terminal_input` acked `delivered: true` into a local shell
     * that never saw the bytes, and the ack said nothing about where they
     * went. The registry knows — it returns the accepting transport's name so
     * the ack can carry it, and a reproduction distinguishes "the local
     * manager took them and lost them" from "another transport claimed the id".
     */
    @Test
    fun `names the transport that accepted the write`() {
        val ssh = Disowning(Transport.SSH, "SSH")
        val local = Owning(Transport.LOCAL, "local")

        val accepted = registry(ssh, local).sendTerminalInput("s1", "ls\n")

        assertEquals("local", accepted)
        assertEquals("s1" to "ls\n", local.received)
    }

    /** The first accepting transport wins, and is the one reported. */
    @Test
    fun `reports the first acceptor, not the last`() {
        val ssh = Owning(Transport.SSH, "SSH")
        val local = Owning(Transport.LOCAL, "local")

        val accepted = registry(local, ssh).sendTerminalInput("s1", "x")

        assertEquals("SSH", accepted)
        assertEquals(null, local.received)
    }

    /**
     * A transport with no [TransportSessionManager.inputName] is never offered
     * the id at all — RDP and SMB have no PTY to write to, and asking them
     * would put a bogus "No null session" in the error the user sees.
     */
    @Test
    fun `transports without terminal input are not offered the id`() {
        var asked = false
        val smb = object : TransportSessionManager {
            override val transport = Transport.SMB
            override fun removeAllSessionsForProfile(profileId: String) = Unit
            override fun sendInput(sessionId: String, text: String) {
                asked = true
            }
        }

        try {
            registry(smb).sendTerminalInput("s1", "x")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue("SMB should not appear in \"${e.message}\"", !e.message!!.contains("SMB"))
        }
        assertTrue("a transport with no inputName must not be asked", !asked)
    }
}
