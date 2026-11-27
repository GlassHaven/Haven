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

    @Test
    fun `buildAndroidMkshrcContent binds Ctrl+L, formats balanced mksh color PS1, and honors customPs1 and user mkshrc (#896, #897)`() {
        val plainRc = buildAndroidMkshrcContent(colorPrompt = false, customPs1 = null)
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", plainRc).redirectErrorStream(true).start().waitFor())
        assertTrue(plainRc.contains("[ -r /system/etc/mkshrc ] && . /system/etc/mkshrc"))
        assertTrue(plainRc.contains("bind '^L=clear-screen'"))
        assertTrue(plainRc.contains("\$HOME/.mkshrc"))

        val colorRc = buildAndroidMkshrcContent(colorPrompt = true, customPs1 = null)
        assertEquals(0, ProcessBuilder("sh", "-n", "-c", colorRc).redirectErrorStream(true).start().waitFor())

        val tmp = java.nio.file.Files.createTempDirectory("haven-mkshrc-896").toFile()
        try {
            val rcFile = java.io.File(tmp, ".haven-mkshrc").apply { writeText(colorRc) }
            // Simulate mksh sourcing $ENV as non-root (USER_ID=10234) and root (USER_ID=0).
            val probeScript = """
                bind() { echo "BIND:${'$'}1"; }
                KSH_VERSION='@(#)MIRBSD KSH R59'
                HOME='${tmp.absolutePath}'
                HOSTNAME='pixel'
                USER='u0_a234'
                USER_ID=10234
                . '${rcFile.absolutePath}'
                printf 'USER_PS1=%s\n' "${'$'}PS1"
                USER='root'
                USER_ID=0
                . '${rcFile.absolutePath}'
                printf 'ROOT_PS1=%s\n' "${'$'}PS1"
            """.trimIndent()
            val proc = ProcessBuilder("/bin/sh", "-c", probeScript).redirectErrorStream(true).start()
            val out = proc.inputStream.readBytes().toString(Charsets.ISO_8859_1)
            assertEquals("probe output: $out", 0, proc.waitFor())
            assertTrue("must run bind '^L=clear-screen': $out", out.contains("BIND:^L=clear-screen"))

            val userPs1 = out.split('\n').first { it.startsWith("USER_PS1=") }.removePrefix("USER_PS1=")
            val rootPs1 = out.split('\n').first { it.startsWith("ROOT_PS1=") }.removePrefix("ROOT_PS1=")
            assertTrue("mksh PS1 must defer _haven_precmd expansion to prompt time", userPs1.contains("\$(_haven_precmd)"))
            assertTrue("mksh PS1 must start with SOH+CR delimiter header", userPs1.startsWith("\u0001\r"))
            assertTrue("user prompt must use bold green (32m) and end with '$ '", userPs1.contains("\u001b[1;32m") && userPs1.endsWith(" $ "))
            assertTrue("root prompt must use bold red (31m) and end with '# '", rootPs1.contains("\u001b[1;31m") && rootPs1.endsWith(" # "))
            // After the 2-byte `\001\r` header, every `\001` opens or closes a non-printing span -> count must be even.
            assertEquals(0, userPs1.drop(2).count { it == '\u0001' } % 2)
            assertEquals(0, rootPs1.drop(2).count { it == '\u0001' } % 2)

            // Custom PS1 + user ~/.mkshrc override order.
            val customRc = buildAndroidMkshrcContent(colorPrompt = true, customPs1 = "[my'ps1] > ")
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", customRc).redirectErrorStream(true).start().waitFor())
            val customRcFile = java.io.File(tmp, ".haven-custom-mkshrc").apply { writeText(customRc) }
            val customOut = ProcessBuilder(
                "/bin/sh",
                "-c",
                "HOME='${tmp.absolutePath}'; . '${customRcFile.absolutePath}'; printf '%s' \"\$PS1\"",
            ).redirectErrorStream(true).start().let { p ->
                val text = p.inputStream.bufferedReader().readText()
                assertEquals(0, p.waitFor())
                text
            }
            assertEquals("[my'ps1] > ", customOut)

            java.io.File(tmp, ".mkshrc").writeText("PS1='from-dot-mkshrc$ '\n")
            val dotOverrideOut = ProcessBuilder(
                "/bin/sh",
                "-c",
                "HOME='${tmp.absolutePath}'; . '${customRcFile.absolutePath}'; printf '%s' \"\$PS1\"",
            ).redirectErrorStream(true).start().let { p ->
                val text = p.inputStream.bufferedReader().readText()
                assertEquals(0, p.waitFor())
                text
            }
            assertEquals("from-dot-mkshrc$ ", dotOverrideOut)
        } finally {
            forceDeleteRecursively(tmp)
        }
    }

    @Test
    fun `buildAndroidShellInvocation propagates ENV across su, launches custom zsh without mksh ENV, and falls back when custom shell missing (#896, #897)`() {
        val tmp = java.nio.file.Files.createTempDirectory("haven-shell-897").toFile()
        try {
            val mkshrc = java.io.File(tmp, "cache/.haven-mkshrc").apply {
                parentFile?.mkdirs()
                writeText(buildAndroidMkshrcContent(colorPrompt = true))
            }
            val fakeBin = java.io.File(tmp, "bin").apply { mkdirs() }
            // `su` stub that deliberately strips ENV (like Magisk/KernelSU su) before running `-c`.
            java.io.File(fakeBin, "su").apply {
                writeText("#!/bin/sh\nunset ENV\nif [ \"\$1\" = \"-c\" ]; then shift; exec /bin/sh -c \"\$1\"; fi\nexit 2\n")
                setExecutable(true)
            }
            val fakeZsh = java.io.File(fakeBin, "zsh").apply {
                writeText("#!/bin/sh\nprintf 'FAKE_ZSH args=%s SHELL=%s ENV=%s PWD=%s\\n' \"\$*\" \"\${SHELL:-}\" \"\${ENV:-unset}\" \"\$(pwd)\"\n")
                setExecutable(true)
            }

            // 1. Default shell under `su -c` keeps ENV pointing at mkshrc even when su strips outer ENV.
            val (_, suDefaultArgs) = buildAndroidShellInvocation(
                defaultHomeDir = tmp.absolutePath,
                startAsRoot = true,
                workingDir = tmp.absolutePath,
                shellBinary = "/bin/sh",
                mkshrcPath = mkshrc.absolutePath,
            )
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", suDefaultArgs[2]).redirectErrorStream(true).start().waitFor())
            val pbSuDefault = ProcessBuilder("/bin/sh", "-c", suDefaultArgs[2])
            pbSuDefault.environment()["PATH"] = "${fakeBin.absolutePath}:${System.getenv("PATH").orEmpty()}"
            val procSuDefault = pbSuDefault.redirectErrorStream(true).start()
            procSuDefault.outputStream.write("printf 'SU_ENV=%s\\n' \"\${ENV:-}\"; exit 0\n".toByteArray())
            procSuDefault.outputStream.close()
            val outSuDefault = procSuDefault.inputStream.bufferedReader().readText()
            assertEquals(0, procSuDefault.waitFor())
            assertTrue("su shell must receive ENV=${mkshrc.absolutePath}: $outSuDefault", outSuDefault.contains("SU_ENV=${mkshrc.absolutePath}"))

            // 2. Custom zsh under `su -c` runs zsh -l with SHELL=.../zsh and ENV unset so zsh loads its own ~/.zshrc.
            val (_, suZshArgs) = buildAndroidShellInvocation(
                defaultHomeDir = tmp.absolutePath,
                startAsRoot = true,
                workingDir = tmp.absolutePath,
                shellBinary = "/bin/sh",
                mkshrcPath = mkshrc.absolutePath,
                customShell = fakeZsh.absolutePath,
            )
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", suZshArgs[2]).redirectErrorStream(true).start().waitFor())
            val pbSuZsh = ProcessBuilder("/bin/sh", "-c", suZshArgs[2])
            pbSuZsh.environment()["PATH"] = "${fakeBin.absolutePath}:${System.getenv("PATH").orEmpty()}"
            val procSuZsh = pbSuZsh.redirectErrorStream(true).start()
            val outSuZsh = procSuZsh.inputStream.bufferedReader().readText()
            assertEquals(0, procSuZsh.waitFor())
            assertTrue("expected FAKE_ZSH invocation, got: $outSuZsh", outSuZsh.contains("FAKE_ZSH args=-l SHELL=${fakeZsh.absolutePath} ENV=unset"))

            // 3. Missing custom shell prints a notice and falls back cleanly to shellBinary with ENV intact.
            val (_, missingArgs) = buildAndroidShellInvocation(
                defaultHomeDir = tmp.absolutePath,
                startAsRoot = false,
                workingDir = tmp.absolutePath,
                shellBinary = "/bin/sh",
                mkshrcPath = mkshrc.absolutePath,
                customShell = "/nonexistent/custom'zsh",
            )
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", missingArgs[2]).redirectErrorStream(true).start().waitFor())
            val procMissing = ProcessBuilder("/bin/sh", "-c", missingArgs[2]).redirectErrorStream(true).start()
            procMissing.outputStream.write("printf 'FALLBACK_ENV=%s\\n' \"\${ENV:-}\"; exit 0\n".toByteArray())
            procMissing.outputStream.close()
            val outMissing = procMissing.inputStream.bufferedReader().readText()
            assertEquals(0, procMissing.waitFor())
            assertTrue("expected missing-shell notice, got: $outMissing", outMissing.contains("Custom shell not found"))
            assertTrue("expected fallback ENV, got: $outMissing", outMissing.contains("FALLBACK_ENV=${mkshrc.absolutePath}"))

            // 4. Shizuku shell command is syntactically valid and stages bind '^L=clear-screen'.
            val shizukuScript = buildShizukuShellStartupScript()
            assertEquals(0, ProcessBuilder("sh", "-n", "-c", shizukuScript).redirectErrorStream(true).start().waitFor())
            val stagedShizukuRc = java.io.File(tmp, ".haven-shizuku-mkshrc")
            val stageOnly = shizukuScript
                .replace("/data/local/tmp/.haven-shizuku-mkshrc", stagedShizukuRc.absolutePath)
                .replace("/data/local/tmp", tmp.absolutePath)
                .replace("exec /system/bin/sh -l", "printf 'SHIZUKU_ENV=%s\\n' \"\${ENV:-}\"")
            val stageOut = ProcessBuilder("/bin/sh", "-c", stageOnly).redirectErrorStream(true).start().let { p ->
                val text = p.inputStream.bufferedReader().readText()
                assertEquals(0, p.waitFor())
                text
            }
            assertTrue(stageOut.contains("SHIZUKU_ENV=${stagedShizukuRc.absolutePath}"))
            assertTrue(stagedShizukuRc.readText().contains("bind '^L=clear-screen'"))
        } finally {
            forceDeleteRecursively(tmp)
        }
    }
}
