package sh.haven.core.usb.massstorage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives [NbdServer] over real loopback sockets with a scripted backend:
 * handshake framing, export info, READ data, read-only rejection, bounds
 * rejection, FLUSH and DISC.
 */
class NbdServerTest {

    /** 4 MiB backing store; records writes and flushes. */
    private class FakeBackend(override val readOnly: Boolean = false) : NbdBackend {
        val backing = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val writes = mutableListOf<Pair<Long, Int>>()
        var flushes = 0

        override val sizeBytes: Long get() = backing.size.toLong()
        override fun readAt(offset: Long, length: Int): ByteArray =
            backing.copyOfRange(offset.toInt(), offset.toInt() + length)
        override fun writeAt(offset: Long, data: ByteArray) {
            writes.add(offset to data.size)
            data.copyInto(backing, offset.toInt())
        }
        override fun flush() { flushes++ }
    }

    /** A minimal NBD client over a socket, enough to exercise the server. */
    private class Client(sock: Socket) {
        val input = DataInputStream(sock.getInputStream().buffered())
        val output = DataOutputStream(sock.getOutputStream())

        fun handshake(): Int {
            assertEquals(NbdServer.NBDMAGIC, input.readLong())
            assertEquals(NbdServer.IHAVEOPT, input.readLong())
            val hsFlags = input.readShort().toInt() and 0xFFFF
            assertTrue(hsFlags and NbdServer.HS_FIXED_NEWSTYLE != 0)
            output.writeInt(NbdServer.HS_C_FIXED_NEWSTYLE or NbdServer.HS_C_NO_ZEROES)
            output.flush()
            return hsFlags
        }

        fun opt(option: Int, payload: ByteArray = ByteArray(0)) {
            output.writeLong(NbdServer.IHAVEOPT)
            output.writeInt(option)
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }

        /** Read one option reply: (replyType, data). */
        fun reply(option: Int): Pair<Int, ByteArray> {
            assertEquals(NbdServer.NBD_REP_MAGIC, input.readLong())
            assertEquals(option, input.readInt())
            val type = input.readInt()
            val len = input.readInt()
            return type to ByteArray(len).also { input.readFully(it) }
        }

        fun go(name: String = "haven-sd") {
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            // name (len-prefixed) + info-count 0
            val payload = ByteArray(4 + nameBytes.size + 4)
            writeU32Be(payload, 0, nameBytes.size.toLong())
            nameBytes.copyInto(payload, 4)
            opt(NbdServer.NBD_OPT_GO, payload)
        }

        /** Read the export info pair + ACK, returning (size, flags). */
        fun exportInfo(option: Int = NbdServer.NBD_OPT_GO): Pair<Long, Int> {
            var size = -1L
            var flags = -1
            while (true) {
                val (type, data) = reply(option)
                when (type) {
                    NbdServer.NBD_REP_INFO -> {
                        when (readU16Be(data, 0)) {
                            NbdServer.NBD_INFO_EXPORT -> {
                                size = readU64Be(data, 2)
                                flags = readU16Be(data, 10)
                            }
                        }
                    }
                    NbdServer.NBD_REP_ACK -> return size to flags
                }
            }
        }

        fun cmd(type: Int, cookie: Long, offset: Long, length: Int, data: ByteArray? = null): Pair<Int, ByteArray> {
            output.writeInt(NbdServer.NBD_REQUEST_MAGIC)
            output.writeShort(0)
            output.writeShort(type)
            output.writeLong(cookie)
            output.writeLong(offset)
            output.writeInt(length)
            if (data != null) output.write(data)
            output.flush()
            val err = readReplyHeader(cookie)
            return err to (if (type == NbdServer.NBD_CMD_READ && err == NbdServer.NBD_SUCCESS) {
                ByteArray(length).also { input.readFully(it) }
            } else ByteArray(0))
        }

        /** Send READ and read only the reply header (error path: no data follows). */
        fun readErr(cookie: Long, offset: Long, length: Int): Int {
            output.writeInt(NbdServer.NBD_REQUEST_MAGIC)
            output.writeShort(0)
            output.writeShort(NbdServer.NBD_CMD_READ)
            output.writeLong(cookie)
            output.writeLong(offset)
            output.writeInt(length)
            output.flush()
            return readReplyHeader(cookie)
        }

        private fun readReplyHeader(cookie: Long): Int {
            assertEquals(NbdServer.NBD_SIMPLE_REPLY_MAGIC, input.readInt())
            val err = input.readInt()
            assertEquals(cookie, input.readLong())
            return err
        }

        fun replyErr(cookie: Long): Int {
            assertEquals(NbdServer.NBD_SIMPLE_REPLY_MAGIC, input.readInt())
            val err = input.readInt()
            assertEquals(cookie, input.readLong())
            return err
        }
    }

    @Test
    fun `handshake and GO return export size and read-only flag`() {
        val backend = FakeBackend(readOnly = true)
        val server = start(backend)
        try {
            Socket("127.0.0.1", server.port).use { sock ->
                val c = Client(sock)
                c.handshake()
                c.go()
                val (size, flags) = c.exportInfo()
                assertEquals(backend.sizeBytes, size)
                assertTrue(flags and NbdServer.NBD_FLAG_HAS_FLAGS != 0)
                assertTrue(flags and NbdServer.NBD_FLAG_READ_ONLY != 0)
                assertTrue(flags and NbdServer.NBD_FLAG_SEND_FLUSH != 0)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `read returns backend bytes at the right offset`() {
        val backend = FakeBackend()
        val server = start(backend)
        try {
            Socket("127.0.0.1", server.port).use { sock ->
                val c = Client(sock)
                c.handshake(); c.go(); c.exportInfo()
                val (err, data) = c.cmd(NbdServer.NBD_CMD_READ, 42, 512L * 3, 1536)
                assertEquals(NbdServer.NBD_SUCCESS, err)
                assertArrayEquals(backend.backing.copyOfRange(1536, 1536 + 1536), data)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `write on a read-only export is rejected with EPERM and never applied`() {
        val backend = FakeBackend(readOnly = true)
        val server = start(backend)
        try {
            Socket("127.0.0.1", server.port).use { sock ->
                val c = Client(sock)
                c.handshake(); c.go(); c.exportInfo()
                val payload = ByteArray(512) { 0x5A }
                val err = writeAndReply(c, 7, 0L, payload)
                assertEquals(NbdServer.NBD_EPERM, err)
                assertFalse(backend.backing.copyOfRange(0, 512).all { it == 0x5A.toByte() })
                assertTrue(backend.writes.isEmpty())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `write on a writable export lands in the backend`() {
        val backend = FakeBackend()
        val server = start(backend)
        try {
            Socket("127.0.0.1", server.port).use { sock ->
                val c = Client(sock)
                c.handshake(); c.go(); c.exportInfo()
                val payload = ByteArray(1024) { (it xor 0x33).toByte() }
                val err = writeAndReply(c, 9, 512L * 100, payload)
                assertEquals(NbdServer.NBD_SUCCESS, err)
                assertEquals(listOf(512L * 100 to 1024), backend.writes)
                assertArrayEquals(payload, backend.backing.copyOfRange(512 * 100, 512 * 100 + 1024))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `out of range read is rejected and connection stays usable`() {
        val backend = FakeBackend()
        val server = start(backend)
        try {
            Socket("127.0.0.1", server.port).use { sock ->
                val c = Client(sock)
                c.handshake(); c.go(); c.exportInfo()
                assertEquals(NbdServer.NBD_EINVAL, c.readErr(11, backend.sizeBytes - 100, 512))
                // Stream must still be aligned for a follow-up command.
                val (err, _) = c.cmd(NbdServer.NBD_CMD_READ, 12, 0, 512)
                assertEquals(NbdServer.NBD_SUCCESS, err)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `oversized request is rejected with EINVAL`() {
        val backend = FakeBackend()
        val server = start(backend)
        try {
            Socket("127.0.0.1", server.port).use { sock ->
                val c = Client(sock)
                c.handshake(); c.go(); c.exportInfo()
                assertEquals(NbdServer.NBD_EINVAL, writeAndReply(c, 13, 0, ByteArray(NbdServer.MAX_TRANSFER_BYTES + 512)))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `flush increments the backend counter and DISC ends the session`() {
        val backend = FakeBackend()
        val ended = CountDownLatch(1)
        val server = NbdServer(backend) { ended.countDown() }
        server.start()
        try {
            Socket("127.0.0.1", server.port).use { sock ->
                val c = Client(sock)
                c.handshake(); c.go(); c.exportInfo()
                c.cmd(NbdServer.NBD_CMD_FLUSH, 21, 0, 0)
                assertEquals(1, backend.flushes)
                // DISC: no reply expected.
                c.output.writeInt(NbdServer.NBD_REQUEST_MAGIC)
                c.output.writeShort(0)
                c.output.writeShort(NbdServer.NBD_CMD_DISC)
                c.output.writeLong(22)
                c.output.writeLong(0)
                c.output.writeInt(0)
                c.output.flush()
            }
            assertTrue("onSessionEnd not called after DISC", ended.await(5, TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `structured reply is answered UNSUP and GO still works`() {
        val backend = FakeBackend()
        val server = start(backend)
        try {
            Socket("127.0.0.1", server.port).use { sock ->
                val c = Client(sock)
                c.handshake()
                c.opt(NbdServer.NBD_OPT_STRUCTURED_REPLY)
                val (type, _) = c.reply(NbdServer.NBD_OPT_STRUCTURED_REPLY)
                assertEquals(NbdServer.NBD_REP_ERR_UNSUP, type)
                c.go()
                val (size, _) = c.exportInfo()
                assertEquals(backend.sizeBytes, size)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `list returns the export name`() {
        val backend = FakeBackend()
        val server = start(backend)
        try {
            Socket("127.0.0.1", server.port).use { sock ->
                val c = Client(sock)
                c.handshake()
                c.opt(NbdServer.NBD_OPT_LIST)
                val (type, data) = c.reply(NbdServer.NBD_OPT_LIST)
                assertEquals(NbdServer.NBD_REP_SERVER, type)
                val nameLen = readU32Be(data, 0).toInt()
                assertEquals("haven-sd", String(data, 4, nameLen, Charsets.US_ASCII))
                assertEquals(NbdServer.NBD_REP_ACK, c.reply(NbdServer.NBD_OPT_LIST).first)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop stops the listener`() {
        val server = start(FakeBackend())
        val port = server.port
        assertTrue(port > 0)
        server.stop()
        // The port must be free again: bind it in the test process.
        val probe = ServerSocket()
        probe.reuseAddress = true
        probe.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        probe.close()
        assertFalse(server.isRunning)
    }

    private fun writeAndReply(c: Client, cookie: Long, offset: Long, payload: ByteArray): Int {
        c.output.writeInt(NbdServer.NBD_REQUEST_MAGIC)
        c.output.writeShort(0)
        c.output.writeShort(NbdServer.NBD_CMD_WRITE)
        c.output.writeLong(cookie)
        c.output.writeLong(offset)
        c.output.writeInt(payload.size)
        c.output.write(payload)
        c.output.flush()
        return c.replyErr(cookie)
    }

    private fun start(backend: NbdBackend): NbdServer =
        NbdServer(backend).also { it.start() }
}