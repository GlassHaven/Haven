package sh.haven.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerRegistryTest {

    private data class FakeSession(
        override val sessionId: String,
        override val profileId: String,
        override val label: String,
        override val status: SessionStatus,
        override val transport: Transport,
        override val sessionName: String? = null,
        override val sessionManagerLabel: String? = null,
    ) : Session

    @Test
    fun `allSessions orders by Transport enum ordinal and disconnectProfile broadcasts`() {
        val disconnectedProfiles = mutableListOf<Pair<Transport, String>>()
        val usb = object : TransportSessionManager {
            override val transport = Transport.USBSERIAL
            override val activeSessionCount = 1
            override val sessions = listOf(
                FakeSession("u1", "p-usb", "USB", SessionStatus.CONNECTED, Transport.USBSERIAL),
            )
            override fun removeAllSessionsForProfile(profileId: String) {
                disconnectedProfiles += Transport.USBSERIAL to profileId
            }
        }
        val ssh = object : TransportSessionManager {
            override val transport = Transport.SSH
            override val activeSessionCount = 1
            override val sessions = listOf(
                FakeSession("s1", "p-ssh", "SSH", SessionStatus.CONNECTED, Transport.SSH),
            )
            override fun removeAllSessionsForProfile(profileId: String) {
                disconnectedProfiles += Transport.SSH to profileId
            }
        }
        val registry = SessionManagerRegistry(setOf(usb, ssh), emptySet())
        assertEquals(listOf("s1", "u1"), registry.allSessions.map { it.sessionId })
        assertTrue(registry.hasActiveSessions())

        registry.disconnectProfile("p-ssh")
        assertEquals(2, disconnectedProfiles.size)
    }

    @Test
    fun `hasActiveSessions reflects ForegroundKeepAlive when transports are idle`() {
        var active = false
        val keepAlive = object : ForegroundKeepAlive {
            override val isActive: Boolean get() = active
        }
        val registry = SessionManagerRegistry(emptySet(), setOf(keepAlive))
        assertFalse(registry.hasActiveSessions())
        active = true
        assertTrue(registry.hasActiveSessions())
    }
}
