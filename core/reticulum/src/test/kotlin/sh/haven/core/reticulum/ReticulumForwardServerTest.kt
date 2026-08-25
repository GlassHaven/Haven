package sh.haven.core.reticulum

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Byte-level tests for the SOCKS5 (no-auth, CONNECT) handshake used by
 * [ReticulumForwardServer]'s dynamic forward. No network.
 */
class ReticulumForwardServerTest {

    private fun server() = ReticulumForwardServer(StubTransport())

    private fun handshake(request: ByteArray): Pair<Pair<String, Int>?, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = server().parseSocks5Connect(
            DataInputStream(ByteArrayInputStream(request)),
            DataOutputStream(out),
        )
        return result to out.toByteArray()
    }

    @Test
    fun `domain CONNECT parses host and port`() {
        // greeting: VER=5 NMETHODS=1 METHOD=0
        // request: VER=5 CMD=1 RSV=0 ATYP=3 LEN=11 "example.com" PORT=80
        val req = byteArrayOf(0x05, 0x01, 0x00) +
            byteArrayOf(0x05, 0x01, 0x00, 0x03, 0x0B) + "example.com".toByteArray() +
            byteArrayOf(0x00, 0x50)
        val (dest, out) = handshake(req)
        assertEquals("example.com" to 80, dest)
        // method selection then success reply.
        assertEquals(0x05.toByte(), out[0]); assertEquals(0x00.toByte(), out[1])
        assertEquals(0x05.toByte(), out[2]); assertEquals(0x00.toByte(), out[3])
    }

    @Test
    fun `ipv4 CONNECT parses host and port`() {
        val req = byteArrayOf(0x05, 0x01, 0x00) +
            byteArrayOf(0x05, 0x01, 0x00, 0x01, 0x7F, 0x00, 0x00, 0x01) +
            byteArrayOf(0x1F, 0x90.toByte()) // 8080
        val (dest, _) = handshake(req)
        assertEquals("127.0.0.1" to 8080, dest)
    }

    @Test
    fun `non-CONNECT command is rejected`() {
        // CMD=2 (BIND) → null + reply code 0x07 (command not supported)
        val req = byteArrayOf(0x05, 0x01, 0x00) +
            byteArrayOf(0x05, 0x02, 0x00, 0x01, 0x7F, 0x00, 0x00, 0x01, 0x00, 0x50)
        val (dest, out) = handshake(req)
        assertNull(dest)
        // reply is [05 07 00 01 ...]
        assertEquals(0x05.toByte(), out[2]); assertEquals(0x07.toByte(), out[3])
    }

    @Test
    fun `no acceptable methods is rejected`() {
        // only METHOD=2 (user/pass) offered → 05 FF, null
        val req = byteArrayOf(0x05, 0x01, 0x02)
        val (dest, out) = handshake(req)
        assertNull(dest)
        assertArrayEquals(byteArrayOf(0x05, 0xFF.toByte()), out)
    }

    /** Minimal transport stub — the handshake never touches it. */
    private class StubTransport : ReticulumTransport {
        override suspend fun init(configDir: String, host: String, port: Int, ifacNetname: String?, ifacNetkey: String?, socketDialer: ((String, Int, Int) -> java.net.Socket)?): String = ""
        override val isInitialised: Boolean = false
        override suspend fun openSession(destinationHash: String, rows: Int, cols: Int): RnshShellSession = throw NotImplementedError()
        override suspend fun execCommand(destinationHash: String, command: List<String>): ReticulumExecSession = throw NotImplementedError()
        override val discoveredDestinations: StateFlow<List<DiscoveredDestination>> = MutableStateFlow(emptyList())
        override suspend fun requestPath(destinationHashHex: String): Boolean = false
        override suspend fun probeSideband(configDir: String): Boolean = false
        override suspend fun clientIdentityHash(configDir: String): String? =
            throw NotImplementedError("identity storage is not part of this test")

        override suspend fun importClientIdentity(
            configDir: String,
            source: java.io.File,
        ): ReticulumIdentityImport =
            throw NotImplementedError("identity storage is not part of this test")

        override suspend fun closeAll() {}
    }
}
