package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-manager launch wrapper (#294): tmux/zellij/etc that fail to
 * start must drop the user into a login shell instead of killing the tab.
 */
class SessionManagerWrapperTest {

    private val tmuxCmd =
        "tmux new-session -A -s haven-local \\; set -gq allow-passthrough on \\; set -gq mouse on"

    @Test
    fun `missing binary falls back to a login shell`() {
        val w = sessionManagerWrapper("tmux", tmuxCmd)
        assertTrue("guards on command -v", w.contains("command -v tmux"))
        assertTrue("else-branch execs a login shell", w.contains("else $GUEST_LOGIN_SHELL; fi"))
    }

    @Test
    fun `installed-but-failing session manager degrades to a shell instead of exiting`() {
        val w = sessionManagerWrapper("tmux", tmuxCmd)
        // The command is run (not exec'd) so a non-zero exit can trip the ||.
        assertTrue("does not exec the session manager", !w.contains("then exec $tmuxCmd"))
        assertTrue("runs the command", w.contains("then $tmuxCmd ||"))
        assertTrue("|| falls back to a login shell", w.contains("$GUEST_LOGIN_SHELL; }"))
        assertTrue("surfaces the failure", w.contains("exited unexpectedly"))
    }

    @Test
    fun `tmux command separators are preserved verbatim`() {
        val w = sessionManagerWrapper("tmux", tmuxCmd)
        // The escaped ';' must reach tmux as literal args, not shell separators.
        assertTrue(w.contains("new-session -A -s haven-local \\; set -gq allow-passthrough on"))
    }

    /**
     * The wrapper is shell source we assemble by concatenation, so the only
     * assertion that really covers it is whether a shell can parse it. Added
     * when the login shell became a substituted constant (#501) — splicing a
     * multi-line `if … fi` into the `|| { … }` branch produces something that
     * reads fine and is not valid shell, and no `contains` check would notice.
     */
    @Test
    fun `every wrapper we emit is syntactically valid shell`() {
        listOf(
            sessionManagerWrapper("tmux", tmuxCmd),
            sessionManagerWrapper("zellij", "zellij attach haven-local --create"),
            sessionManagerWrapper("screen", "screen -xRR haven-local"),
            GUEST_LOGIN_SHELL,
        ).forEach { script ->
            val exit = ProcessBuilder("sh", "-n", "-c", script)
                .redirectErrorStream(true).start().waitFor()
            assertEquals("sh -n rejected: $script", 0, exit)
        }
    }

    @Test
    fun `the guest login shell prefers bash and falls back to sh`() {
        assertTrue("asks the guest, not the app", GUEST_LOGIN_SHELL.contains("command -v bash"))
        assertTrue("falls back to the previous behaviour", GUEST_LOGIN_SHELL.contains("/bin/sh"))
        assertTrue("replaces the shell rather than nesting one", GUEST_LOGIN_SHELL.startsWith("exec "))
        assertTrue("a login shell, so profile files still run", GUEST_LOGIN_SHELL.endsWith(" -l"))
    }

    @Test
    fun `zellij is wrapped the same way`() {
        val w = sessionManagerWrapper("zellij", "zellij attach haven-local --create")
        assertTrue(w.contains("command -v zellij"))
        assertTrue(w.contains("zellij attach haven-local --create ||"))
    }

    @Test
    fun `buildAndroidShellInvocation defaults to unprivileged shell in defaultHomeDir (#894)`() {
        val (cmd, args) = buildAndroidShellInvocation(
            defaultHomeDir = "/data/user/0/sh.haven.app/files",
            startAsRoot = false,
            workingDir = null,
        )
        assertEquals("/system/bin/sh", cmd)
        assertEquals(3, args.size)
        assertEquals("/system/bin/sh", args[0])
        assertEquals("-c", args[1])
        assertEquals("cd '/data/user/0/sh.haven.app/files' 2>/dev/null; exec /system/bin/sh -l", args[2])
        val exit = ProcessBuilder("sh", "-n", "-c", args[2]).redirectErrorStream(true).start().waitFor()
        assertEquals(0, exit)
    }

    @Test
    fun `buildAndroidShellInvocation escapes quotes and launches su inside configured starting directory (#894)`() {
        val (_, customArgs) = buildAndroidShellInvocation(
            defaultHomeDir = "/data/user/0/sh.haven.app/files",
            startAsRoot = false,
            workingDir = "  /sdcard/work'dir  ",
        )
        assertEquals("cd '/sdcard/work'\\''dir' 2>/dev/null; exec /system/bin/sh -l", customArgs[2])
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", customArgs[2]).redirectErrorStream(true).start().waitFor())

        val (_, rootArgs) = buildAndroidShellInvocation(
            defaultHomeDir = "/data/user/0/sh.haven.app/files",
            startAsRoot = true,
            workingDir = "/",
        )
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", rootArgs[2]).redirectErrorStream(true).start().waitFor())
        assertTrue("must invoke su -c: ${rootArgs[2]}", rootArgs[2].contains("exec su -c "))

        // Execute against a stub `su` binary in a real temp directory to verify the exact command su receives.
        val tmp = java.nio.file.Files.createTempDirectory("haven-su-894").toFile()
        try {
            val targetDir = java.io.File(tmp, "sub'dir").apply { mkdirs() }
            val fakeBin = java.io.File(tmp, "bin").apply { mkdirs() }
            val fakeSu = java.io.File(fakeBin, "su").apply {
                writeText("#!/bin/sh\nif [ \"\$1\" = \"-c\" ]; then shift; exec /bin/sh -c \"\$1\"; fi\nexit 2\n")
                setExecutable(true)
            }
            assertTrue(fakeSu.canExecute())
            val (_, realRootArgs) = buildAndroidShellInvocation(
                defaultHomeDir = tmp.absolutePath,
                startAsRoot = true,
                workingDir = targetDir.absolutePath,
                shellBinary = "/bin/sh",
            )
            val pb = ProcessBuilder("/bin/sh", "-c", realRootArgs[2])
            pb.environment()["PATH"] = "${fakeBin.absolutePath}:${System.getenv("PATH").orEmpty()}"
            val proc = pb.redirectErrorStream(true).start()
            proc.outputStream.write("pwd; exit 0\n".toByteArray())
            proc.outputStream.flush()
            proc.outputStream.close()
            val out = proc.inputStream.bufferedReader().readText().trim()
            assertEquals(0, proc.waitFor())
            assertTrue("expected pwd '${targetDir.canonicalPath}' in output '$out'", out.lines().any { it.trim() == targetDir.canonicalPath })
        } finally {
            forceDeleteRecursively(tmp)
        }
    }
}
