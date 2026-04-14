package sh.haven.core.mosh

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Regression test for [#73](https://github.com/GlassOnTin/Haven/issues/73).
 *
 * Pins the fix's load-bearing invariant: `MoshSession.start()` must push
 * a DECCKM-on byte sequence (`ESC [ ? 1 h`) into the client-side emulator
 * via [onDataReceived] BEFORE any transport bytes arrive. Without that
 * init, libvterm stays in normal cursor key mode and arrow keys come
 * out as `ESC [ A` instead of `ESC O A`, which breaks Mutt/Emacs/less
 * inside mosh sessions. See the companion doc on
 * [MoshSession.DECCKM_ON] for the full causal chain.
 *
 * The test deliberately points the transport at `127.0.0.1:0` so the
 * UDP bind may or may not succeed in the test environment — we only
 * care that the DECCKM init has been delivered **synchronously** before
 * the transport-start coroutine runs. The session is closed immediately
 * after the assertion so no network traffic or lingering coroutines
 * survive.
 */
class MoshSessionDecckmTest {

    // 16 zero bytes, base64-encoded. Mosh's OCB key length.
    private val zeroKey = "AAAAAAAAAAAAAAAAAAAAAA=="

    private data class Capture(val bytes: ByteArray, val offset: Int, val length: Int) {
        fun snapshot(): ByteArray = bytes.copyOfRange(offset, offset + length)
    }

    private val received = CopyOnWriteArrayList<Capture>()
    private lateinit var session: MoshSession

    @After
    fun tearDown() {
        if (this::session.isInitialized) {
            session.close()
        }
    }

    private fun newSession(): MoshSession = MoshSession(
        sessionId = "test-session",
        profileId = "test-profile",
        label = "test",
        serverIp = "127.0.0.1",
        moshPort = 0, // ephemeral; UDP connect won't route but we don't need it to
        moshKey = zeroKey,
        onDataReceived = { data, offset, length ->
            received.add(Capture(data.copyOf(), offset, length))
        },
    )

    @Test
    fun `start pushes DECCKM-on to the emulator synchronously before starting the transport`() {
        session = newSession()
        session.start()

        // The DECCKM init is delivered by the synchronous portion of
        // start() before t.start(scope) launches any coroutines, so it
        // must already be present the moment start() returns.
        assertTrue("no onDataReceived call made by start", received.isNotEmpty())
        val first = received.first()
        assertArrayEquals(
            "first bytes delivered to the emulator must be ESC [ ? 1 h " +
                "(DECCKM on) — see MoshSession.DECCKM_ON for the rationale",
            MoshSession.DECCKM_ON,
            first.snapshot(),
        )
    }

    @Test
    fun `DECCKM_ON is exactly 5 bytes forming the ESC bracket ? 1 h CSI sequence`() {
        // Guard against typos in the constant. The literal is small enough
        // that spelling it out in the assertion is clearer than any indirection.
        assertEquals(5, MoshSession.DECCKM_ON.size)
        assertEquals(0x1B.toByte(), MoshSession.DECCKM_ON[0])
        assertEquals('['.code.toByte(), MoshSession.DECCKM_ON[1])
        assertEquals('?'.code.toByte(), MoshSession.DECCKM_ON[2])
        assertEquals('1'.code.toByte(), MoshSession.DECCKM_ON[3])
        assertEquals('h'.code.toByte(), MoshSession.DECCKM_ON[4])
    }

    @Test
    fun `start is a no-op after close — must not emit DECCKM twice`() {
        session = newSession()
        session.start()
        val firstCount = received.size
        session.close()
        session.start()
        // Second start() on a closed session should do nothing, not
        // push a stale DECCKM init into a torn-down emulator.
        assertEquals(
            "start after close must not push additional bytes",
            firstCount,
            received.size,
        )
    }
}
