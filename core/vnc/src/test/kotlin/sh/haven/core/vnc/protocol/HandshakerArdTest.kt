package sh.haven.core.vnc.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import sh.haven.core.vnc.VncConfig
import sh.haven.core.vnc.VncSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Security-type selection against the list macOS Screen Sharing offers
 * (observed: 30, 33, 36, 2, 35 behind an "RFB 003.889" banner). With a
 * username Haven must log into the account (type 30); without one it keeps
 * using the VNC password (type 2) exactly as before.
 */
class HandshakerArdTest {

    private val macTypes = listOf(30, 33, 36, 2, 35)

    private fun serverScript(build: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).build() }.toByteArray()

    private fun DataOutputStream.banner(types: List<Int>) {
        write("RFB 003.889\n".toByteArray(Charsets.US_ASCII))
        writeByte(types.size)
        types.forEach { writeByte(it) }
    }

    private fun session(server: ByteArray, out: ByteArrayOutputStream, user: String?, pass: String) =
        VncSession(
            VncConfig().apply {
                usernameSupplier = user?.let { u -> { u } }
                passwordSupplier = { pass }
            },
            ByteArrayInputStream(server),
            out,
        )

    @Test
    fun `with a username the client logs into the macOS account via type 30`() {
        val ard = ArdAuthTest.FakeArdServer()
        val server = serverScript {
            banner(macTypes)
            write(ard.wireParams())
            writeInt(0) // SecurityResult OK
        }
        val out = ByteArrayOutputStream()

        Handshaker.handshake(session(server, out, "alice", "s3cret"), Socket(), "mac.local")

        val sent = out.toByteArray()
        assertEquals("RFB 003.008\n", String(sent, 0, 12, Charsets.US_ASCII))
        assertEquals(30, sent[12].toInt())
        val block = ard.decrypt(sent.copyOfRange(13, sent.size))
        assertEquals("alice", String(block, 0, 5, Charsets.UTF_8))
        assertEquals(0, block[5].toInt())
        assertEquals("s3cret", String(block, 64, 6, Charsets.UTF_8))
        assertEquals(0, block[70].toInt())
    }

    @Test
    fun `without a username the VNC password path is unchanged`() {
        val server = serverScript {
            banner(macTypes)
            write(ByteArray(16)) // VncAuth challenge
            writeInt(0)
        }
        val out = ByteArrayOutputStream()

        Handshaker.handshake(session(server, out, null, "vncpass"), Socket(), "mac.local")

        val sent = out.toByteArray()
        assertEquals(2, sent[12].toInt())
        assertEquals(12 + 1 + 16, sent.size)
    }

    @Test
    fun `a rejected account login surfaces the server's reason`() {
        val ard = ArdAuthTest.FakeArdServer()
        val reason = "Authentication failure"
        val server = serverScript {
            banner(macTypes)
            write(ard.wireParams())
            writeInt(1)
            writeInt(reason.length)
            write(reason.toByteArray(Charsets.US_ASCII))
        }

        val e = assertThrows(AuthenticationFailedException::class.java) {
            Handshaker.handshake(session(server, ByteArrayOutputStream(), "alice", "wrong"), Socket(), "mac.local")
        }
        assertEquals(reason, e.message)
    }
}
