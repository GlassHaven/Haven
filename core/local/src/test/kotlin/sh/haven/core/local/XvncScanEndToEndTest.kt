package sh.haven.core.local

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #501 & #893 end-to-end: the pure-Kotlin `/proc` scanner has to find a real
 * process in `/proc` without forking `sh` or `tr` subprocesses, and handle
 * synthetic `/proc` fixtures for cmdline, environ, and nested-Wayland matching.
 */
class XvncScanEndToEndTest {

    private val display = 77

    private fun scan(): List<ProcessCmdline> = scanProcCmdlines()

    @Test
    fun `the scan finds a live process whose argv is an Xvnc argv`() {
        assumeTrue("needs /proc", File("/proc/self/cmdline").exists())
        val argv0 = "/usr/bin/Xvnc :$display -geometry 1280x720 -depth 24 -rfbport 5977"
        val fake = ProcessBuilder("bash", "-c", "exec -a \"$argv0\" sleep 30").start()
        try {
            // Give the exec a moment to land before reading /proc.
            Thread.sleep(300)
            val rows = scan()
            assumeTrue("bash exec -a unavailable on this host", rows.any { it.cmdline.contains("Xvnc :$display") })

            val hits = rows.filter { xvncMatches(it.cmdline, display) }
            assertTrue("the production scan must find the Xvnc on :$display", hits.isNotEmpty())
            assertTrue(
                "found rows must really be that Xvnc: ${hits.map { it.cmdline }}",
                hits.all { it.cmdline.contains("Xvnc :$display") },
            )

            // A different display must not sweep it up.
            assertTrue(
                "display ${display + 1} must not match the Xvnc on :$display",
                rows.none { xvncMatches(it.cmdline, display + 1) && it.cmdline.contains("Xvnc :$display") },
            )
        } finally {
            fake.destroyForcibly()
            fake.waitFor()
        }
    }

    @Test
    fun `a shell whose command line merely mentions Xvnc is not matched`() {
        assumeTrue("needs /proc", File("/proc/self/cmdline").exists())
        // This hazard is one the move to /proc *introduced*, not one it
        // inherited: `ps -A` printed names only, so a shell that merely
        // mentions Xvnc never appeared as a candidate there. Reading full
        // command lines makes it one — a script, a log tailer, this test's own
        // shell. Anchoring on argv[0] is what stops it, and matching the whole
        // line instead fails this test against a real process.
        val talker = ProcessBuilder("sh", "-c", "echo 'starting Xvnc :$display'; sleep 30").start()
        try {
            Thread.sleep(300)
            val rows = scan()
            val mentions = rows.filter { it.cmdline.contains("Xvnc") && !it.cmdline.startsWith("/usr/bin/Xvnc") }
            assumeTrue("no mentioning process observed", mentions.isNotEmpty())
            mentions.forEach {
                assertFalse("must not be a kill candidate: ${it.cmdline.take(120)}", xvncMatches(it.cmdline, display))
                assertFalse("must not be a kill candidate: ${it.cmdline.take(120)}", xvncMatches(it.cmdline, null))
            }
        } finally {
            talker.destroyForcibly()
            talker.waitFor()
        }
    }

    @Test
    fun `pure Kotlin proc scanners parse NUL-delimited cmdline environ and nested-wayland basenames (#893)`() {
        val fakeProc = Files.createTempDirectory("fake-proc-893").toFile()
        try {
            File(fakeProc, "self").mkdirs()
            File(fakeProc, "stat").writeText("cpu 0 0 0")

            File(fakeProc, "101").apply {
                mkdirs()
                File(this, "cmdline").writeBytes("/usr/bin/Xvnc\u0000:1\u0000-geometry\u00001280x720\u0000".toByteArray())
                File(this, "environ").writeBytes("HOME=/root\u0000XDG_RUNTIME_DIR=/tmp/xdg-runtime-1\u0000".toByteArray())
            }
            File(fakeProc, "102").apply {
                mkdirs()
                File(this, "cmdline").writeBytes("/usr/sbin/wayvnc\u00000.0.0.0\u00005901\u0000".toByteArray())
                File(this, "environ").writeBytes("HOME=/root\u0000XDG_RUNTIME_DIR=/tmp/xdg-runtime-11\u0000".toByteArray())
            }
            File(fakeProc, "103").apply {
                mkdirs()
                File(this, "cmdline").writeBytes("/usr/libexec/imv-wayland\u0000/root/pic.png\u0000".toByteArray())
            }
            File(fakeProc, "104").apply {
                mkdirs()
                // Mentioning wayvnc in argv[2], not argv[0] — must NOT match nested-wayland targets.
                File(this, "cmdline").writeBytes("/bin/sh\u0000-c\u0000echo wayvnc\u0000".toByteArray())
            }
            File(fakeProc, "105").apply {
                mkdirs()
                // Kernel thread: empty cmdline
                File(this, "cmdline").writeBytes(ByteArray(0))
            }

            val cmdlines = scanProcCmdlines(fakeProc).associate { it.pid to it.cmdline }
            assertEquals("/usr/bin/Xvnc :1 -geometry 1280x720", cmdlines["101"])
            assertEquals("/usr/sbin/wayvnc 0.0.0.0 5901", cmdlines["102"])
            assertFalse("kernel thread with empty cmdline must be skipped", cmdlines.containsKey("105"))

            assertEquals(listOf("101"), findAppWindowSessionPids("/tmp/xdg-runtime-1", fakeProc))
            assertEquals(listOf("102"), findAppWindowSessionPids("/tmp/xdg-runtime-11", fakeProc))

            val waylandHits = findNestedWaylandPids(listOf("sway", "wayvnc", "imv"), fakeProc).sorted()
            assertEquals(listOf("102", "103"), waylandHits)
        } finally {
            forceDeleteRecursively(fakeProc)
        }
    }
}
