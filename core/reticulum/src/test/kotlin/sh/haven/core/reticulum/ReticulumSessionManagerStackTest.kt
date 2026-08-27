package sh.haven.core.reticulum

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Reticulum stack is a process singleton whose mode — shared instance or
 * gateway — is fixed when it starts, and the two are exclusive (#588). Nothing
 * released it, so a shared-instance connection made a gateway connection
 * impossible for the life of the process: "the banner keeps appearing even
 * after disconnecting the session and just closing Haven makes possible to
 * choose again" (reported against v5.87.59).
 *
 * These tests pin the release: the last session going away shuts the stack
 * down, and a session that is still there keeps it up.
 */
class ReticulumSessionManagerStackTest {

    private fun manager(transport: ReticulumTransport) =
        ReticulumSessionManager(transport, ReticulumForwardServer(transport))

    @Test
    fun `removing the last session shuts the stack down`() {
        val transport = LatchTransport()
        val manager = manager(transport)
        val id = manager.registerSession("profile-a", "a", "aa".repeat(16))

        manager.removeSession(id)

        assertTrue(
            "stack was not released after the last session went away",
            transport.closed.await(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `removing one of two sessions leaves the stack up`() {
        val transport = LatchTransport()
        val manager = manager(transport)
        val first = manager.registerSession("profile-a", "a", "aa".repeat(16))
        manager.registerSession("profile-b", "b", "bb".repeat(16))

        manager.removeSession(first)

        assertFalse(
            "stack was released while a session was still open",
            transport.closed.await(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `disconnectAll shuts the stack down`() {
        val transport = LatchTransport()
        val manager = manager(transport)
        manager.registerSession("profile-a", "a", "aa".repeat(16))
        manager.registerSession("profile-b", "b", "bb".repeat(16))

        manager.disconnectAll()

        assertTrue(
            "stack was not released after disconnectAll",
            transport.closed.await(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `removing a profile's last session shuts the stack down`() {
        val transport = LatchTransport()
        val manager = manager(transport)
        manager.registerSession("profile-a", "a", "aa".repeat(16))
        manager.registerSession("profile-a", "a2", "aa".repeat(16))

        manager.removeAllSessionsForProfile("profile-a")

        assertTrue(
            "stack was not released after the profile's sessions went away",
            transport.closed.await(5, TimeUnit.SECONDS),
        )
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `a failed connect leaves the session in ERROR, not a CONNECTING ghost`() {
        val transport = LatchTransport()
        val manager = manager(transport)
        val id = manager.registerSession("profile-a", "a", "aa".repeat(16))

        val thrown = runCatching {
            runBlocking { manager.connectSession(id, "", "bad.host", 1) }
        }.exceptionOrNull()

        // The exact type is not the contract; any throw must leave ERROR behind.
        // In a JVM unit test the `android.util.Log.w` at the top of the try
        // throws "not mocked" before transport.init is reached, which exercises
        // the same catch-and-mark path a real transport failure would.
        assertTrue("connect should have thrown", thrown != null)
        assertEquals(
            "a failed connect must surface as ERROR so the tab shows dead and " +
                "activeSessions stops counting it (#601)",
            ReticulumSessionManager.SessionState.Status.ERROR,
            manager.sessions.value[id]?.status,
        )
    }

    /** Records [closeAll] so the test can wait for work done on the IO dispatcher. */
    private class LatchTransport : ReticulumTransport {
        val closed = CountDownLatch(1)

        @Volatile
        var closeCount: Int = 0
            private set

        override suspend fun closeAll() {
            closeCount++
            closed.countDown()
        }

        // Throws: connectSession must translate this into an ERROR status
        // rather than leaving the entry CONNECTING (#601).
        override suspend fun init(configDir: String, host: String, port: Int, ifacNetname: String?, ifacNetkey: String?, socketDialer: ((String, Int, Int) -> java.net.Socket)?): String =
            throw IllegalStateException("no stack in this test")
        override val isInitialised: Boolean = false
        override suspend fun openSession(destinationHash: String, rows: Int, cols: Int): RnshShellSession = throw NotImplementedError()
        override suspend fun execCommand(destinationHash: String, command: List<String>): ReticulumExecSession = throw NotImplementedError()
        override val discoveredDestinations: StateFlow<List<DiscoveredDestination>> = MutableStateFlow(emptyList())
        override suspend fun requestPath(destinationHashHex: String): Boolean = false
        override suspend fun probeSideband(configDir: String): Boolean = false
        override suspend fun clientIdentityHash(configDir: String): String? =
            throw NotImplementedError("identity storage is not part of this test")

        override suspend fun importClientIdentity(
            configDir: String,
            source: java.io.File,
        ): ReticulumIdentityImport =
            throw NotImplementedError("identity storage is not part of this test")
    }
}
