package sh.haven.core.rclone

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.data.repository.ConnectionLogRepository

/**
 * #839 — Cancel must mark the session ERROR even when no browser OAuth
 * flow is in flight. A stored-token refresh inside connectSession's
 * listDirectory call blocks the same way with no oauthFutures entry;
 * the old early-return left the card CONNECTING and the spinner up,
 * which the reporter saw as "Cancel OAuth ignores me".
 */
class RcloneSessionManagerCancelTest {

    private fun manager(): RcloneSessionManager = RcloneSessionManager(
        client = mockk(relaxed = true),
        context = mockk<Context>(relaxed = true),
        connectionLogRepository = mockk<ConnectionLogRepository>(relaxed = true),
    )

    @Test
    fun `cancel without an OAuth future marks the session ERROR`() {
        val mgr = manager()
        val sessionId = mgr.registerSession("p1", "Drive")
        assertEquals(
            RcloneSessionManager.SessionState.Status.CONNECTING,
            mgr.sessions.value[sessionId]!!.status,
        )

        mgr.cancelPendingOAuth(sessionId)

        val session = mgr.sessions.value[sessionId]!!
        assertEquals(RcloneSessionManager.SessionState.Status.ERROR, session.status)
        assertEquals("Connection cancelled.", session.errorMessage)
    }

    @Test
    fun `cancel of an unknown session is a no-op`() {
        val mgr = manager()
        mgr.cancelPendingOAuth("no-such-session")
        assertEquals(0, mgr.sessions.value.size)
    }
}
