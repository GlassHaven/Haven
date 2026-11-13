package sh.haven.core.knock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnockSequenceTest {

    @Test
    fun `blank input parses to null - knocking disabled`() {
        assertNull(KnockSequence.parse(null).getOrThrow())
        assertNull(KnockSequence.parse("").getOrThrow())
        assertNull(KnockSequence.parse("   ").getOrThrow())
        assertNull(KnockSequence.parse("\t\n").getOrThrow())
    }

    @Test
    fun `space-separated ports default to tcp`() {
        val seq = KnockSequence.parse("7000 8000 9000").getOrThrow()
        assertNotNull(seq)
        assertEquals(
            listOf(
                KnockStep(7000, KnockStep.Protocol.TCP),
                KnockStep(8000, KnockStep.Protocol.TCP),
                KnockStep(9000, KnockStep.Protocol.TCP),
            ),
            seq!!.steps,
        )
    }

    @Test
    fun `comma-separated ports work`() {
        val seq = KnockSequence.parse("7000,8000,9000").getOrThrow()!!
        assertEquals(3, seq.steps.size)
        assertTrue(seq.steps.all { it.protocol == KnockStep.Protocol.TCP })
    }

    @Test
    fun `mixed whitespace and commas tolerated`() {
        val seq = KnockSequence.parse("7000, 8000,9000  ,  10000").getOrThrow()!!
        assertEquals(listOf(7000, 8000, 9000, 10000), seq.steps.map { it.port })
    }

    @Test
    fun `protocol suffix is honoured`() {
        val seq = KnockSequence.parse("7000/tcp 8000/udp 9000/TCP").getOrThrow()!!
        assertEquals(KnockStep.Protocol.TCP, seq.steps[0].protocol)
        assertEquals(KnockStep.Protocol.UDP, seq.steps[1].protocol)
        assertEquals(KnockStep.Protocol.TCP, seq.steps[2].protocol)
    }

    @Test
    fun `colon separator also accepted (knockd style)`() {
        val seq = KnockSequence.parse("7000:tcp,8000:udp").getOrThrow()!!
        assertEquals(KnockStep.Protocol.TCP, seq.steps[0].protocol)
        assertEquals(KnockStep.Protocol.UDP, seq.steps[1].protocol)
    }

    @Test
    fun `icmp knockonports syntax and payload modes parse cleanly`() {
        val seq = KnockSequence.parse(
            "495x1:ICMP, 792x1:ICMP, 42x1:ICMP, 53x1:ICMP",
            delayMs = 500,
        ).getOrThrow()!!
        assertEquals(500, seq.interKnockDelayMs)
        assertEquals(
            listOf(
                KnockStep(495, KnockStep.Protocol.ICMP, KnockStep.IcmpSizeMode.PAYLOAD_ONLY),
                KnockStep(792, KnockStep.Protocol.ICMP, KnockStep.IcmpSizeMode.PAYLOAD_ONLY),
                KnockStep(42, KnockStep.Protocol.ICMP, KnockStep.IcmpSizeMode.PAYLOAD_ONLY),
                KnockStep(53, KnockStep.Protocol.ICMP, KnockStep.IcmpSizeMode.PAYLOAD_ONLY),
            ),
            seq.steps,
        )
        assertEquals(495, seq.steps[0].icmpPayloadBytes(ipv6 = false))
        assertEquals(495, seq.steps[0].icmpPayloadBytes(ipv6 = true))
    }

    @Test
    fun `icmp header-inclusive modes and repeat counts expand and roundtrip`() {
        val seq = KnockSequence.parse("0/icmp 503x2:icmp+h 523/icmp+ip").getOrThrow()!!
        assertEquals(
            listOf(
                KnockStep(0, KnockStep.Protocol.ICMP, KnockStep.IcmpSizeMode.PAYLOAD_ONLY),
                KnockStep(503, KnockStep.Protocol.ICMP, KnockStep.IcmpSizeMode.WITH_ICMP_HEADER),
                KnockStep(503, KnockStep.Protocol.ICMP, KnockStep.IcmpSizeMode.WITH_ICMP_HEADER),
                KnockStep(523, KnockStep.Protocol.ICMP, KnockStep.IcmpSizeMode.WITH_IP_AND_ICMP_HEADERS),
            ),
            seq.steps,
        )
        assertEquals(0, seq.steps[0].icmpPayloadBytes(ipv6 = false))
        assertEquals(495, seq.steps[1].icmpPayloadBytes(ipv6 = false))
        assertEquals(495, seq.steps[3].icmpPayloadBytes(ipv6 = false))
        assertEquals(475, seq.steps[3].icmpPayloadBytes(ipv6 = true))

        val reparsed = KnockSequence.parse(seq.format()).getOrThrow()!!
        assertEquals(seq.steps, reparsed.steps)
    }

    @Test
    fun `icmp invalid sizes and repeat counts fail`() {
        assertTrue(KnockSequence.parse("-1/icmp").isFailure)
        assertTrue(KnockSequence.parse("65508/icmp").isFailure)
        assertTrue(KnockSequence.parse("7/icmp+h").isFailure)
        assertTrue(KnockSequence.parse("27/icmp+ip").isFailure)
        assertTrue(KnockSequence.parse("495x0:icmp").isFailure)
        assertTrue(KnockSequence.parse("495x33:icmp").isFailure)
        assertTrue(KnockSequence.parse("495xabc:icmp").isFailure)
    }

    @Test
    fun `delay is carried through`() {
        val seq = KnockSequence.parse("7000", delayMs = 250).getOrThrow()!!
        assertEquals(250, seq.interKnockDelayMs)
    }

    @Test
    fun `non-numeric port fails`() {
        assertTrue(KnockSequence.parse("abc").isFailure)
        assertTrue(KnockSequence.parse("7000 abc 9000").isFailure)
    }

    @Test
    fun `port out of range fails`() {
        assertTrue(KnockSequence.parse("0").isFailure)
        assertTrue(KnockSequence.parse("70000").isFailure)
        assertTrue(KnockSequence.parse("-1").isFailure)
    }

    @Test
    fun `unknown protocol fails`() {
        val r = KnockSequence.parse("22/sctp")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()?.message?.contains("sctp", ignoreCase = true) == true)
    }

    @Test
    fun `format roundtrips through parse`() {
        val original = KnockSequence.parse("7000/tcp 8000/udp 495/icmp 9000/tcp").getOrThrow()!!
        val reparsed = KnockSequence.parse(original.format()).getOrThrow()!!
        assertEquals(original.steps, reparsed.steps)
    }

    @Test
    fun `negative delay fails construction`() {
        val r = runCatching {
            KnockSequence(listOf(KnockStep(7000, KnockStep.Protocol.TCP)), -1)
        }
        assertTrue(r.isFailure)
    }
}
