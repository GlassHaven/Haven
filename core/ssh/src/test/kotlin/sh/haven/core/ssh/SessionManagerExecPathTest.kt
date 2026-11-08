package sh.haven.core.ssh

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list / kill / rename commands run over an SSH exec channel, where sshd
 * gives a non-login `sh` a bare PATH (macOS: /usr/bin:/bin:/usr/sbin:/sbin).
 * A Homebrew-installed manager is then "not found" (exit 127) even though the
 * interactive login shell started it fine, so the attach probe raised a
 * false "tmux not running" banner and the session picker listed nothing.
 * Every exec-side command must fall back to the Homebrew bin directories.
 */
class SessionManagerExecPathTest {

    private val homebrewDirs = listOf("/opt/homebrew/bin", "/usr/local/bin")

    private fun assertSearchesHomebrew(what: String, command: String) {
        homebrewDirs.forEach { dir ->
            assertTrue("$what should search $dir: $command", command.contains(dir))
        }
        // Appended, not prepended: a binary already on the sshd PATH keeps winning.
        assertTrue("$what should append to the existing PATH: $command", command.contains("PATH=\"\$PATH:"))
    }

    @Test
    fun `list commands search Homebrew bin directories`() {
        SessionManager.entries.forEach { m ->
            m.listCommand?.let { assertSearchesHomebrew("${m.name} list", it) }
        }
    }

    @Test
    fun `kill and rename commands search Homebrew bin directories`() {
        SessionManager.entries.forEach { m ->
            m.killCommand?.let { assertSearchesHomebrew("${m.name} kill", it("work")) }
            m.renameCommand?.let { assertSearchesHomebrew("${m.name} rename", it("old", "new")) }
        }
    }

    @Test
    fun `tmux list command still lists bare session names`() {
        val cmd = SessionManager.TMUX.listCommand!!
        assertTrue(cmd.startsWith("sh -c '"))
        assertTrue(cmd.contains("tmux ls -F \"#{session_name}\" 2>/dev/null"))
    }
}
