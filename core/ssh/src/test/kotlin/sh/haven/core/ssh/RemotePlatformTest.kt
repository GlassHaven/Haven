package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe outputs are as captured from real hosts: Windows Server OpenSSH 9.5
 * (cmd default shell, and `powershell -c`), Ubuntu, and macOS.
 */
class RemotePlatformTest {

    @Test
    fun `cmd leaves the PowerShell variable and the semicolon literal`() {
        assertEquals(
            RemotePlatform.WINDOWS_CMD,
            RemotePlatform.parseProbe("Windows_NT\$PSVersionTable; uname -s\r\n"),
        )
    }

    @Test
    fun `PowerShell expands PSVersionTable to a hashtable even when uname fails`() {
        val out = "Windows_NTSystem.Collections.Hashtable\r\n"
        assertEquals(RemotePlatform.WINDOWS_POWERSHELL, RemotePlatform.parseProbe(out))
    }

    @Test
    fun `POSIX shells print the literal OS token then uname`() {
        assertEquals(RemotePlatform.LINUX, RemotePlatform.parseProbe("%OS%\nLinux\n"))
        assertEquals(RemotePlatform.MACOS, RemotePlatform.parseProbe("%OS%\nDarwin\n"))
        assertEquals(RemotePlatform.OTHER_POSIX, RemotePlatform.parseProbe("%OS%\nFreeBSD\n"))
        assertEquals(RemotePlatform.OTHER_POSIX, RemotePlatform.parseProbe("%OS%\nMINGW64_NT-10.0-26100\n"))
    }

    @Test
    fun `empty or garbled output is unknown`() {
        assertEquals(RemotePlatform.UNKNOWN, RemotePlatform.parseProbe(""))
        assertEquals(RemotePlatform.UNKNOWN, RemotePlatform.parseProbe("%OS%\n"))
    }

    @Test
    fun `Herdr on a cmd host starts without the POSIX sh wrapper`() {
        val cmd = SessionManager.HERDR.startCommand("sg-win", RemotePlatform.WINDOWS_CMD)!!
        assertFalse(cmd.contains("sh -c"))
        assertTrue(cmd.startsWith("where /q herdr"))
        assertTrue(cmd.endsWith("else herdr --session sg-win"))
        // A parenthesis inside the echo would close cmd's `if (…)` block early.
        val echo = cmd.substringAfter("(echo ").substringBefore(") else")
        assertFalse(echo.contains('(') || echo.contains(')'))
    }

    @Test
    fun `Herdr on a PowerShell host uses Get-Command`() {
        val cmd = SessionManager.HERDR.startCommand("sg-win", RemotePlatform.WINDOWS_POWERSHELL)!!
        assertFalse(cmd.contains("sh -c"))
        assertTrue(cmd.contains("Get-Command herdr"))
        assertTrue(cmd.contains("herdr --session sg-win"))
    }

    @Test
    fun `Windows list and kill commands drop the POSIX wrapper`() {
        assertEquals("herdr session list --json 2>nul", SessionManager.HERDR.listCommandFor(RemotePlatform.WINDOWS_CMD))
        assertEquals("herdr session list --json 2>\$null", SessionManager.HERDR.listCommandFor(RemotePlatform.WINDOWS_POWERSHELL))
        val kill = SessionManager.HERDR.killCommandFor("w", RemotePlatform.WINDOWS_CMD)!!
        assertTrue(kill.contains("herdr session stop w --json") && kill.contains("herdr session delete w --json"))
        assertFalse(kill.contains("sh -c"))
        assertFalse(SessionManager.PSMUX.listCommandFor(RemotePlatform.WINDOWS_CMD)!!.contains("sh -c"))
    }

    @Test
    fun `POSIX and unknown hosts keep the original templates`() {
        for (platform in listOf(RemotePlatform.UNKNOWN, RemotePlatform.LINUX, RemotePlatform.MACOS)) {
            for (m in SessionManager.entries) {
                assertEquals(m.command?.invoke("n"), m.startCommand("n", platform))
                assertEquals(m.listCommand, m.listCommandFor(platform))
                assertEquals(m.killCommand?.invoke("n"), m.killCommandFor("n", platform))
            }
        }
    }

    @Test
    fun `POSIX-only managers are unchanged on Windows`() {
        assertEquals(
            SessionManager.TMUX.command!!("n"),
            SessionManager.TMUX.startCommand("n", RemotePlatform.WINDOWS_CMD),
        )
    }
}
