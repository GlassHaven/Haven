package sh.haven.core.local

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.repository.ProotInstallLogRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ShizukuBridgeProtocolTest {

    @Test
    fun `handshake round-trip preserves pty dimensions term and argv`() {
        val out = ByteArrayOutputStream()
        val expected = ShizukuBridgeProtocol.Handshake(
            usePty = true,
            rows = 42,
            cols = 132,
            term = "xterm-kitty",
            args = listOf("-c", "pm list packages | head -n 5"),
        )
        ShizukuBridgeProtocol.writeHandshake(out, expected)

        val decoded = ShizukuBridgeProtocol.readHandshake(ByteArrayInputStream(out.toByteArray()))
        assertEquals(expected, decoded)
    }

    @Test
    fun `handshake clamps zero dimensions and blank term to defaults`() {
        val out = ByteArrayOutputStream()
        ShizukuBridgeProtocol.writeHandshake(
            out,
            ShizukuBridgeProtocol.Handshake(
                usePty = false,
                rows = 0,
                cols = 999,
                term = "",
                args = emptyList(),
            ),
        )

        val decoded = ShizukuBridgeProtocol.readHandshake(ByteArrayInputStream(out.toByteArray()))
        assertEquals(false, decoded.usePty)
        assertEquals(24, decoded.rows)
        assertEquals(80, decoded.cols)
        assertEquals("xterm-256color", decoded.term)
        assertTrue(decoded.args.isEmpty())
    }

    @Test(expected = IOException::class)
    fun `handshake rejects invalid magic`() {
        val bad = byteArrayOf('B'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte(), '!'.code.toByte())
        ShizukuBridgeProtocol.readHandshake(ByteArrayInputStream(bad))
    }

    @Test(expected = IOException::class)
    fun `handshake rejects oversized argument length`() {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).apply {
            write(ShizukuBridgeProtocol.MAGIC)
            writeByte(0)
            writeShort(24)
            writeShort(80)
            writeShort(0)
            writeShort(1) // argc = 1
            writeInt(ShizukuBridgeProtocol.MAX_ARG_BYTES + 1)
            flush()
        }
        ShizukuBridgeProtocol.readHandshake(ByteArrayInputStream(raw.toByteArray()))
    }

    @Test
    fun `frame round-trip covers stdout stderr error exit resize and eof`() {
        val out = ByteArrayOutputStream()
        val sample = "uid=2000(shell) gid=2000(shell)\n".toByteArray(Charsets.UTF_8)
        ShizukuBridgeProtocol.writeFrame(out, ShizukuBridgeProtocol.MSG_STDOUT, sample)
        ShizukuBridgeProtocol.writeErrorFrame(out, "Shizuku is not running.")
        ShizukuBridgeProtocol.writeFrame(
            out,
            ShizukuBridgeProtocol.MSG_RESIZE,
            byteArrayOf(0x00, 0x30, 0x00, 0x78), // 48 x 120
        )
        ShizukuBridgeProtocol.writeExitFrame(out, 127)

        val stream = ByteArrayInputStream(out.toByteArray())
        val f1 = ShizukuBridgeProtocol.readFrame(stream)!!
        assertEquals(ShizukuBridgeProtocol.MSG_STDOUT, f1.type)
        assertArrayEquals(sample, f1.payload)

        val f2 = ShizukuBridgeProtocol.readFrame(stream)!!
        assertEquals(ShizukuBridgeProtocol.MSG_ERROR, f2.type)
        assertEquals("Shizuku is not running.", String(f2.payload, Charsets.UTF_8))

        val f3 = ShizukuBridgeProtocol.readFrame(stream)!!
        assertEquals(ShizukuBridgeProtocol.MSG_RESIZE, f3.type)
        assertEquals(48 to 120, ShizukuBridgeProtocol.parseResizePayload(f3.payload))

        val f4 = ShizukuBridgeProtocol.readFrame(stream)!!
        assertEquals(ShizukuBridgeProtocol.MSG_EXIT, f4.type)
        assertEquals(127, ByteBuffer.wrap(f4.payload).int)

        assertNull(ShizukuBridgeProtocol.readFrame(stream))
    }

    @Test(expected = IOException::class)
    fun `readFrame rejects payload larger than MAX_FRAME_LEN`() {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).apply {
            writeByte(ShizukuBridgeProtocol.MSG_STDIN)
            writeInt(ShizukuBridgeProtocol.MAX_FRAME_LEN + 1)
            flush()
        }
        ShizukuBridgeProtocol.readFrame(ByteArrayInputStream(raw.toByteArray()))
    }

    @Test
    fun `ShizukuPtyClient builds interactive and command argv plus RISH_APPLICATION_ID`() {
        val ctx = mockk<Context>(relaxed = true) {
            every { packageName } returns "sh.haven.app.debug"
        }
        val client = ShizukuPtyClient(ctx)

        val (interactiveCmd, interactiveArgs) = client.buildShellCommand()
        assertEquals("/system/bin/sh", interactiveCmd)
        assertEquals(3, interactiveArgs.size)
        assertEquals("-c", interactiveArgs[1])
        assertTrue(interactiveArgs[2].contains("exec /system/bin/sh -l"))

        val (oneShotCmd, oneShotArgs) = client.buildShellCommand(listOf("-c", "id"))
        assertEquals("/system/bin/sh", oneShotCmd)
        assertArrayEquals(arrayOf("/system/bin/sh", "-c", "id"), oneShotArgs)

        val env = client.buildShellEnv(locale = "C.UTF-8", term = "xterm-256color").toList()
        assertTrue(env.contains("RISH_APPLICATION_ID=sh.haven.app.debug"))
        assertTrue(env.contains("HOME=/data/local/tmp"))
        assertTrue(env.contains("TERM=xterm-256color"))
    }

    @Test
    fun `ProotManager shizukuGuestBindArgs stages shizuku and rish placeholders and starts bridge`() {
        val tmpBase = Files.createTempDirectory("proot-shizuku-bind").toFile()
        try {
            val filesDir = File(tmpBase, "files").apply { mkdirs() }
            val cacheDir = File(tmpBase, "cache").apply { mkdirs() }
            val nativeLibDir = File(tmpBase, "nativelib").apply { mkdirs() }
            val rootfsDir = File(tmpBase, "rootfs").apply { mkdirs() }
            val appInfo = ApplicationInfo().apply {
                nativeLibraryDir = nativeLibDir.absolutePath
            }
            val ctx = mockk<Context>(relaxed = true) {
                every { this@mockk.filesDir } returns filesDir
                every { this@mockk.cacheDir } returns cacheDir
                every { applicationInfo } returns appInfo
            }
            val bridge = mockk<ShizukuBridgeServer>(relaxed = true)
            val mgr = ProotManager(
                context = ctx,
                installLogRepository = mockk<ProotInstallLogRepository>(relaxed = true),
                shizukuBridgeServer = bridge,
            )

            // Absent binary -> empty bind list
            assertEquals(0, mgr.shizukuGuestBindArgs(rootfsDir, longForm = false).size)

            // Executable libhaven-shizuku.so -> binds both /usr/local/bin/shizuku and /usr/local/bin/rish
            val helper = File(nativeLibDir, "libhaven-shizuku.so").apply {
                writeText("#!/bin/sh\n")
                setExecutable(true)
            }
            val shortArgs = mgr.shizukuGuestBindArgs(rootfsDir, longForm = false).toList()
            assertEquals(
                listOf(
                    "-b", "${helper.absolutePath}:/usr/local/bin/shizuku",
                    "-b", "${helper.absolutePath}:/usr/local/bin/rish",
                ),
                shortArgs,
            )
            val longArgs = mgr.shizukuGuestBindArgs(rootfsDir, longForm = true).toList()
            assertEquals(
                listOf(
                    "--bind=${helper.absolutePath}:/usr/local/bin/shizuku",
                    "--bind=${helper.absolutePath}:/usr/local/bin/rish",
                ),
                longArgs,
            )
            assertTrue(File(rootfsDir, "usr/local/bin/shizuku").exists())
            assertTrue(File(rootfsDir, "usr/local/bin/rish").exists())
            verify(atLeast = 1) { bridge.ensureStarted() }
        } finally {
            forceDeleteRecursively(tmpBase)
        }
    }

    @Test
    fun `LocalSession customBackend failure surfaces diagnostic banner and exit code 1`() {
        val output = ByteArrayOutputStream()
        val exitLatch = CountDownLatch(1)
        val recordedExit = AtomicInteger(-1)

        val failingBackend = object : LocalSession.CustomPtyBackend {
            override fun open(rows: Int, cols: Int): ParcelFileDescriptor {
                throw ShizukuUnavailableException("Shizuku service is not running.")
            }
            override fun resize(rows: Int, cols: Int) = Unit
            override fun waitExit(): Int = 1
            override fun close() = Unit
        }

        val session = LocalSession(
            sessionId = "test-shizuku-fail",
            profileId = "p1",
            label = "Shizuku Shell",
            command = "/system/bin/sh",
            args = arrayOf("/system/bin/sh"),
            env = emptyArray(),
            onDataReceived = { buf, off, len -> synchronized(output) { output.write(buf, off, len) } },
            onExited = { code ->
                recordedExit.set(code)
                exitLatch.countDown()
            },
            customBackend = failingBackend,
        )

        session.start(rows = 30, cols = 100)
        session.sendInput("id\n".toByteArray())
        assertTrue("Expected onExited within 3s", exitLatch.await(3, TimeUnit.SECONDS))
        assertEquals(1, recordedExit.get())
        val rendered = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
        assertTrue(
            "Expected diagnostic banner in output, got: $rendered",
            rendered.contains("[Shizuku] Shizuku service is not running."),
        )
        session.close()
    }
}
