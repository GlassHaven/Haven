package sh.haven.core.vnc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reactive pacing: the client loop waits on [VncSession.waitForInput] instead
 * of `Thread.sleep(interval)`. Sending any user input must wake it within the
 * interval so the next FramebufferUpdateRequest goes out immediately.
 */
class VncSessionInputWakeTest {

    private lateinit var session: VncSession

    @Before
    fun setUp() {
        session = VncSession(
            VncConfig(),
            InputStream.nullInputStream(),
            ByteArrayOutputStream(),
        )
    }

    @Test
    fun `waitForInput returns false when no input within timeout`() {
        val start = System.currentTimeMillis()
        val got = session.waitForInput(50)
        val elapsed = System.currentTimeMillis() - start
        assertFalse(got)
        assertTrue("Should wait close to timeout; elapsed=$elapsed", elapsed >= 45)
    }

    @Test
    fun `sendPointerEvent wakes waitForInput`() {
        Thread {
            Thread.sleep(10)
            session.sendPointerEvent(100, 200)
        }.start()

        val start = System.currentTimeMillis()
        val got = session.waitForInput(1000)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("Should wake on input", got)
        assertTrue("Should wake fast, not sleep through timeout; elapsed=$elapsed", elapsed < 500)
    }

    @Test
    fun `sendKeyEvent wakes waitForInput`() {
        Thread {
            Thread.sleep(10)
            session.sendKeyEvent(0xFF0D /* Return */, true)
        }.start()

        val got = session.waitForInput(1000)
        assertTrue(got)
    }

    @Test
    fun `updateMouseButton wakes waitForInput`() {
        Thread {
            Thread.sleep(10)
            session.updateMouseButton(1, true)
        }.start()

        val got = session.waitForInput(1000)
        assertTrue(got)
    }

    @Test
    fun `waitForInput consumes the signal`() {
        session.sendPointerEvent(1, 2)
        val first = session.waitForInput(50)
        val second = session.waitForInput(50)
        assertTrue(first)
        assertFalse("Signal must only fire once", second)
    }
}
