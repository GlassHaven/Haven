package sh.haven.app.agent

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.guestnotify.GuestNotifyBridge
import sh.haven.core.mcp.McpError

/**
 * JVM-testable surface of [NotifyToolProvider] (#851): the tool set, consent
 * metadata, the master opt-in gate, and JSON shaping. The real socket bind and
 * Android notification post run only on a device (no Robolectric LocalSocket
 * shadow) — those paths are NOT covered here and are marked unverified.
 */
class NotifyToolProviderTest {

    private val prefs = mockk<UserPreferencesRepository>()
    private val proot = mockk<ProotManager>()
    private val lsm = mockk<LocalSessionManager> {
        every { prootManager } returns proot
    }
    private val bridge = mockk<GuestNotifyBridge>()

    private fun provider() = NotifyToolProvider(prefs, lsm, bridge)
    private fun handler(name: String) = provider().tools()[name]!!

    @Test
    fun `exposes attach and detach only`() {
        assertEquals(
            setOf("attach_notify_to_guest", "detach_notify_from_guest"),
            provider().tools().keys,
        )
    }

    @Test
    fun `attach is once-per-session and detach is never`() {
        assertEquals(ConsentLevel.ONCE_PER_SESSION, handler("attach_notify_to_guest").consentLevel)
        assertEquals(ConsentLevel.NEVER, handler("detach_notify_from_guest").consentLevel)
    }

    @Test
    fun `attach rejected when master pref is off`() = runTest {
        every { prefs.notifyGuestExposureEnabled } returns flowOf(false)
        try {
            handler("attach_notify_to_guest").handle(JSONObject())
            fail("expected McpError when the master opt-in is off")
        } catch (e: McpError) {
            assertTrue(e.message!!.contains("disabled"))
        }
        verify(exactly = 0) { bridge.start() }
    }

    @Test
    fun `attach starts bridge and stages helper when enabled`() = runTest {
        every { prefs.notifyGuestExposureEnabled } returns flowOf(true)
        every { bridge.start() } returns JSONObject().put("attached", true)
        every { proot.stageHavenNotifyArtifacts() } returns "/usr/local/bin/haven-notify"
        val res = handler("attach_notify_to_guest").handle(JSONObject()).structured
        assertTrue(res.getBoolean("attached"))
        assertEquals("/usr/local/bin/haven-notify", res.getString("helperPath"))
        assertEquals("haven-notify \"Title\" \"Body\"", res.getString("helperCommand"))
        verify { bridge.start() }
        verify { proot.stageHavenNotifyArtifacts() }
    }

    @Test
    fun `attach reports null helper path when no rootfs`() = runTest {
        every { prefs.notifyGuestExposureEnabled } returns flowOf(true)
        every { bridge.start() } returns JSONObject().put("attached", true)
        every { proot.stageHavenNotifyArtifacts() } returns null
        val res = handler("attach_notify_to_guest").handle(JSONObject()).structured
        assertTrue(res.isNull("helperPath"))
    }

    @Test
    fun `detach stops the bridge`() = runTest {
        every { bridge.stop() } returns JSONObject().put("detached", true)
        val res = handler("detach_notify_from_guest").handle(JSONObject()).structured
        assertTrue(res.getBoolean("detached"))
        verify { bridge.stop() }
    }
}
