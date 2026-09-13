package sh.haven.core.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #640: Disconnect All sets a static flag and tries to foreground the
 * activity so it can finish itself. When that auto-launch is blocked by
 * background-activity-launch restrictions, the flag survives to the next
 * launcher open, whose resume then finished the app — the reporter saw
 * Haven "crash back to the launcher" on the open after Disconnect All.
 * Only the service's own auto-launch (it carries the exit extra) may
 * finish; a plain launcher open clears the flag instead.
 */
class SshConnectionServiceExitPolicyTest {
    @Test
    fun `auto-launch resume with flag set finishes the app`() {
        assertTrue(SshConnectionService.shouldFinishOnResume(disconnectedAll = true, hasExitExtra = true))
    }

    @Test
    fun `launcher open with a stranded flag must not finish (#640)`() {
        // The auto-launch was BAL-blocked; the user opens Haven from the
        // launcher and must land in the app, not bounce out.
        assertFalse(SshConnectionService.shouldFinishOnResume(disconnectedAll = true, hasExitExtra = false))
    }

    @Test
    fun `no flag means never finish`() {
        assertFalse(SshConnectionService.shouldFinishOnResume(disconnectedAll = false, hasExitExtra = true))
        assertFalse(SshConnectionService.shouldFinishOnResume(disconnectedAll = false, hasExitExtra = false))
    }
}