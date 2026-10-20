package sh.haven.core.local.uml

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.local.DesktopManager

/**
 * #686: a start that fails after the guest booted left the guest running.
 * Its launcher holds the advisory lock on the shared rootfs image, so the
 * next Start panics in the guest kernel ("F_SETLK failed, file already
 * locked by pid …" → VFS cannot mount root). The cause was two-fold:
 * [UmlDesktopManager.fail] never tore the guest down, and `sessionIds`
 * was never written, so even [UmlDesktopManager.stopDesktop]'s close path
 * had nothing to close. A failed start must release the rootfs lock.
 */
class UmlDesktopFailureTeardownTest {

    private val sessionId = "sess-1"

    /** Guest up, console up, then the session reports dead → start fails with a sessionId in hand. */
    private fun failingGuest(): UmlGuestManager {
        val guest = mockk<UmlGuestManager>(relaxed = true)
        every { guest.isAvailable() } returns true
        // A real dir: writeRecipeScript() runs File.resolve/writeText against it.
        every { guest.guestShareDir } returns
            java.nio.file.Files.createTempDirectory("uml-teardown").toFile()
        every { guest.registerSession(any(), any(), any(), any()) } returns sessionId
        every { guest.getActiveSession(sessionId) } returns mockk(relaxed = true)
        // sessions map without the id == dead (sessionDead's `?: true`), so
        // awaitConsolePrompt throws "guest exited during boot" immediately
        // instead of burning its 45s budget.
        every { guest.sessions } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        return guest
    }

    @Test
    fun failedStartClosesTheGuest() = runBlocking {
        val guest = failingGuest()
        val manager = UmlDesktopManager(guest, mockk<DesktopManager>(relaxed = true))

        manager.startDesktop(UmlDesktopManager.Kind.X11)

        withTimeout(5_000) {
            while (manager.state.value[UmlDesktopManager.Kind.X11]?.status !=
                UmlDesktopManager.DesktopState.Status.ERROR
            ) {
                delay(25)
            }
        }
        assertEquals(UmlDesktopManager.DesktopState.Status.ERROR,
            manager.state.value[UmlDesktopManager.Kind.X11]?.status)
        // The lock holder must be gone before the state is reported, or the
        // next Start races the dying launcher.
        verify(timeout = 2_000) { guest.closeGuest(sessionId) }
    }

    @Test
    fun cancelledStartClosesTheGuest() = runBlocking {
        val guest = failingGuest()
        // Keep the start in flight: console alive, prompt never settles.
        every { guest.sessions } returns kotlinx.coroutines.flow.MutableStateFlow(
            mapOf(sessionId to mockk<UmlGuestManager.SessionState>(relaxed = true)),
        )
        val manager = UmlDesktopManager(guest, mockk<DesktopManager>(relaxed = true))

        manager.startDesktop(UmlDesktopManager.Kind.X11)
        // STARTING is set synchronously by beginBusy, before the driver
        // coroutine runs — waiting on it alone lets stopDesktop cancel the job
        // before the guest boots (nothing to close), which is the CI flake.
        // Wait for the boot itself so the cancel lands mid-flight.
        withTimeout(5_000) { verify(timeout = 5_000) { guest.connectSession(sessionId) } }
        manager.stopDesktop(UmlDesktopManager.Kind.X11)
        verify(timeout = 3_000) { guest.closeGuest(sessionId) }
    }
}
