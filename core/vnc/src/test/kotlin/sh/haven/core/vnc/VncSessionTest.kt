package sh.haven.core.vnc

import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class VncSessionTest {

    private lateinit var session: VncSession

    @Before
    fun setUp() {
        session = VncSession(
            VncConfig(),
            mockk<InputStream>(relaxed = true),
            mockk<OutputStream>(relaxed = true),
        )
    }

    @Test
    fun `waitForFramebufferUpdate with timeout returns false when no signal`() {
        val got = session.waitForFramebufferUpdate(50)
        assertFalse(got)
    }

    @Test
    fun `waitForFramebufferUpdate with timeout returns true when signalled`() {
        // Signal from another thread
        Thread {
            Thread.sleep(10)
            session.framebufferUpdated()
        }.start()

        val got = session.waitForFramebufferUpdate(1000)
        assertTrue(got)
    }

    @Test
    fun `waitForFramebufferUpdate without timeout blocks until signalled`() {
        Thread {
            Thread.sleep(10)
            session.framebufferUpdated()
        }.start()

        // This would hang forever without the signal
        session.waitForFramebufferUpdate()
    }

    @Test
    fun `lastUpdateHadRectangles defaults to false`() {
        assertFalse(session.lastUpdateHadRectangles)
    }

    @Test
    fun `lastUpdateHadRectangles can be set and read`() {
        session.lastUpdateHadRectangles = true
        assertTrue(session.lastUpdateHadRectangles)

        session.lastUpdateHadRectangles = false
        assertFalse(session.lastUpdateHadRectangles)
    }

    @Test
    fun `waitForFramebufferUpdate resets fbUpdated flag`() {
        session.framebufferUpdated()

        // First call returns true and resets
        val first = session.waitForFramebufferUpdate(50)
        assertTrue(first)

        // Second call returns false (flag was reset)
        val second = session.waitForFramebufferUpdate(50)
        assertFalse(second)
    }
}
