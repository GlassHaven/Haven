package sh.haven.core.mosh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.mosh.proto.HostInstruction
import sh.haven.core.mosh.proto.HostMessage
import sh.haven.core.mosh.proto.TransportInstruction
import sh.haven.core.mosh.proto.UserInstruction
import sh.haven.core.mosh.proto.UserMessage

class WireFormatTest {

    @Test
    fun `TransportInstruction roundtrip`() {
        val original = TransportInstruction(
            protocolVersion = 2,
            oldNum = 100,
            newNum = 200,
            ackNum = 50,
            throwawayNum = 25,
            diff = byteArrayOf(1, 2, 3, 4),
        )

        val encoded = original.encode()
        val decoded = TransportInstruction.decode(encoded)

        assertEquals(original.protocolVersion, decoded.protocolVersion)
        assertEquals(original.oldNum, decoded.oldNum)
        assertEquals(original.newNum, decoded.newNum)
        assertEquals(original.ackNum, decoded.ackNum)
        assertEquals(original.throwawayNum, decoded.throwawayNum)
        assertArrayEquals(original.diff, decoded.diff)
    }

    @Test
    fun `TransportInstruction with no diff`() {
        val original = TransportInstruction(
            protocolVersion = 2,
            oldNum = 0,
            newNum = 0,
            ackNum = 0,
        )

        val encoded = original.encode()
        val decoded = TransportInstruction.decode(encoded)

        assertEquals(2, decoded.protocolVersion)
        assertEquals(0L, decoded.oldNum)
        assertNull(decoded.diff)
    }

    @Test
    fun `UserMessage with keystroke`() {
        val msg = UserMessage(
            listOf(UserInstruction.Keystroke("hello".toByteArray()))
        )
        val encoded = msg.encode()
        assertTrue(encoded.isNotEmpty())

        // Verify it's valid protobuf by checking it starts with the right tag
        // Tag for field 1, wire type 2 (LEN) = (1 << 3) | 2 = 0x0A
        assertEquals(0x0A.toByte(), encoded[0])
    }

    @Test
    fun `UserMessage with resize`() {
        val msg = UserMessage(
            listOf(UserInstruction.Resize(80, 24))
        )
        val encoded = msg.encode()
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun `UserMessage with multiple instructions`() {
        val msg = UserMessage(listOf(
            UserInstruction.Keystroke("a".toByteArray()),
            UserInstruction.Keystroke("b".toByteArray()),
            UserInstruction.Resize(132, 43),
            UserInstruction.Keystroke("c".toByteArray()),
        ))
        val encoded = msg.encode()
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun `HostMessage decode with HostBytes`() {
        // Manually encode a HostMessage with a single HostBytes instruction
        // HostMessage { instruction[1] { hostbytes[2] { hoststring[4] = "hello" } } }
        val hoststring = "hello".toByteArray()

        // Inner: HostBytes { hoststring = 4: "hello" }
        // Tag(4, LEN) = (4 << 3) | 2 = 0x22
        val hbInner = byteArrayOf(0x22, hoststring.size.toByte()) + hoststring

        // Mid: Instruction { hostbytes = 2: hbInner }
        // Tag(2, LEN) = (2 << 3) | 2 = 0x12
        val instInner = byteArrayOf(0x12, hbInner.size.toByte()) + hbInner

        // Outer: HostMessage { instruction = 1: instInner }
        // Tag(1, LEN) = (1 << 3) | 2 = 0x0A
        val msgBytes = byteArrayOf(0x0A, instInner.size.toByte()) + instInner

        val decoded = HostMessage.decode(msgBytes)
        assertEquals(1, decoded.instructions.size)
        val inst = decoded.instructions[0]
        assertTrue(inst is HostInstruction.HostBytes)
        assertArrayEquals(hoststring, (inst as HostInstruction.HostBytes).data)
    }

    @Test
    fun `HostMessage decode with EchoAck`() {
        // EchoAck { echo_ack_num = 8: 42 }
        // Tag(8, VARINT) = (8 << 3) | 0 = 0x40
        val eaInner = byteArrayOf(0x40, 42)

        // Instruction { echoack = 7: eaInner }
        // Tag(7, LEN) = (7 << 3) | 2 = 0x3A
        val instInner = byteArrayOf(0x3A, eaInner.size.toByte()) + eaInner

        // HostMessage { instruction = 1: instInner }
        val msgBytes = byteArrayOf(0x0A, instInner.size.toByte()) + instInner

        val decoded = HostMessage.decode(msgBytes)
        assertEquals(1, decoded.instructions.size)
        val inst = decoded.instructions[0]
        assertTrue(inst is HostInstruction.EchoAck)
        assertEquals(42L, (inst as HostInstruction.EchoAck).echoAckNum)
    }

    @Test
    fun `HostMessage decode empty message`() {
        val decoded = HostMessage.decode(ByteArray(0))
        assertTrue(decoded.instructions.isEmpty())
    }

    @Test
    fun `TransportInstruction large values`() {
        val original = TransportInstruction(
            protocolVersion = 2,
            oldNum = Long.MAX_VALUE,
            newNum = Long.MAX_VALUE - 1,
            ackNum = 0xFFFF_FFFFL,
        )

        val decoded = TransportInstruction.decode(original.encode())
        assertEquals(original.oldNum, decoded.oldNum)
        assertEquals(original.newNum, decoded.newNum)
        assertEquals(original.ackNum, decoded.ackNum)
    }
}
