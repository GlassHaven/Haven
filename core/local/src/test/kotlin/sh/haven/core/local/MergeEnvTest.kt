package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergeEnv] underpins #285 (a local shell joining a running desktop): the base
 * proot env already sets XDG_RUNTIME_DIR=/tmp, and the desktop overlay must
 * REPLACE it (not emit a duplicate KEY= that execve resolves libc-defined).
 */
class MergeEnvTest {

    @Test
    fun `overlay replaces an existing key in place, no duplicate`() {
        val base = arrayOf("HOME=/root", "XDG_RUNTIME_DIR=/tmp", "PATH=/bin")
        val out = mergeEnv(base, mapOf("XDG_RUNTIME_DIR" to "/tmp/xdg-runtime-1"))
        val xdg = out.filter { it.startsWith("XDG_RUNTIME_DIR=") }
        assertEquals("exactly one XDG_RUNTIME_DIR entry", 1, xdg.size)
        assertEquals("XDG_RUNTIME_DIR=/tmp/xdg-runtime-1", xdg.single())
        // Untouched keys survive.
        assertTrue(out.contains("HOME=/root"))
        assertTrue(out.contains("PATH=/bin"))
    }

    @Test
    fun `new keys are appended`() {
        val base = arrayOf("HOME=/root")
        val out = mergeEnv(base, mapOf("DISPLAY" to ":1", "XAUTHORITY" to "/root/.Xauthority"))
        assertTrue(out.contains("DISPLAY=:1"))
        assertTrue(out.contains("XAUTHORITY=/root/.Xauthority"))
        assertTrue(out.contains("HOME=/root"))
    }

    @Test
    fun `value containing equals signs is preserved`() {
        val out = mergeEnv(arrayOf("A=1"), mapOf("FOO" to "a=b=c"))
        assertEquals("FOO=a=b=c", out.single { it.startsWith("FOO=") })
    }

    @Test
    fun `empty overlay returns the base entries unchanged`() {
        val base = arrayOf("HOME=/root", "PATH=/bin")
        val out = mergeEnv(base, emptyMap())
        assertEquals(base.toList(), out.toList())
    }

    @Test
    fun `buildAndroidShellPath preserves Android system PATH and appends root binary dirs`() {
        val inherited = "/product/bin:/apex/com.android.runtime/bin:/system_ext/bin:/system/bin:/odm/bin:/vendor/bin"
        val out = buildAndroidShellPath(inherited)
        assertEquals(
            listOf(
                "/product/bin",
                "/apex/com.android.runtime/bin",
                "/system_ext/bin",
                "/system/bin",
                "/odm/bin",
                "/vendor/bin",
                "/system/xbin",
                "/sbin",
                "/su/bin",
                "/debug_ramdisk",
            ),
            out.split(':'),
        )
    }

    @Test
    fun `buildAndroidShellPath falls back cleanly when inherited PATH is null or blank`() {
        assertEquals(
            "/system/bin:/system/xbin:/sbin:/su/bin:/debug_ramdisk:/vendor/bin",
            buildAndroidShellPath(null),
        )
        assertEquals(
            "/system/bin:/system/xbin:/sbin:/su/bin:/debug_ramdisk:/vendor/bin",
            buildAndroidShellPath("  :  "),
        )
    }
}
