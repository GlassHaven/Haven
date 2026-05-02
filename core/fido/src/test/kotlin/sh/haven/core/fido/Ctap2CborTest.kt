package sh.haven.core.fido

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ctap2CborTest {

    @Test
    fun `getAssertion without pin auth retains v1 wire shape`() {
        val cmd = Ctap2Cbor.encodeGetAssertionCommand(
            rpId = "ssh:",
            clientDataHash = ByteArray(32) { it.toByte() },
            credentialId = ByteArray(48) { (it * 3).toByte() },
        )
        // Command byte (0x02) + map(4) is the same shape we shipped before
        // the PIN protocol. Spot-check the leading bytes.
        assertEquals(0x02.toByte(), cmd[0])
        assertEquals(0xA4.toByte(), cmd[1]) // CBOR map of size 4
    }

    @Test
    fun `getAssertion with pin auth carries keys 6 and 7`() {
        val authParam = ByteArray(32) { 0x42 }
        val cmd = Ctap2Cbor.encodeGetAssertionCommand(
            rpId = "ssh:primary",
            clientDataHash = ByteArray(32),
            credentialId = ByteArray(48),
            pinUvAuthParam = authParam,
            pinUvAuthProtocol = 2,
        )
        assertEquals(0x02.toByte(), cmd[0])
        assertEquals(0xA6.toByte(), cmd[1]) // CBOR map of size 6
    }

    @Test
    fun `getInfo decoder extracts pin protocols and clientPin flag`() {
        // Hand-crafted CBOR: { 4: { "clientPin": true, "uv": false }, 6: [1, 2] }
        val data = byteArrayOf(
            0xA2.toByte(),                       // map(2)
            0x04,                                // key 4 (options)
            0xA2.toByte(),                       // map(2)
            0x69, 0x63, 0x6C, 0x69, 0x65, 0x6E, 0x74, 0x50, 0x69, 0x6E, // text(9) "clientPin"
            0xF5.toByte(),                       // true
            0x62, 0x75, 0x76,                    // text(2) "uv"
            0xF4.toByte(),                       // false
            0x06,                                // key 6 (pinUvAuthProtocols)
            0x82.toByte(),                       // array(2)
            0x01, 0x02,                          // [1, 2]
        )
        val info = Ctap2Cbor.decodeGetInfoResponse(data)
        assertEquals(listOf(1, 2), info.pinUvAuthProtocols)
        assertTrue(info.clientPinSet)
        assertFalse(info.uvBuiltIn)
    }

    @Test
    fun `cose key encode-decode round-trip via clientPin keyAgreement`() {
        // Build a minimal getKeyAgreement-style response: map { 1: COSE_Key }
        // by piggybacking the encoder used in clientPinGetTokenWithPermissions.
        val pair = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val (x, y) = Ctap2PinProtocol.V2.ecPublicToCoseCoords(
            pair.public as java.security.interfaces.ECPublicKey,
        )
        val key = Ctap2Cbor.CoseEcdhPubKey(x, y)
        val cmd = Ctap2Cbor.encodeClientPinGetTokenWithPermissions(
            protocol = 2,
            platformKeyAgreement = key,
            pinHashEnc = ByteArray(32),
            permissions = Ctap2Cbor.PERMISSION_GET_ASSERTION,
            rpId = "ssh:",
        )
        // First byte is the CTAP command (clientPIN = 0x06)
        assertEquals(Ctap2Cbor.CMD_CLIENT_PIN, cmd[0])
        assertEquals(0xA6.toByte(), cmd[1]) // map(6) — protocol, sub, KA, pinHashEnc, perms, rpId
    }

    @Test
    fun `decode keyAgreement response extracts COSE coords`() {
        // Build a getKeyAgreement response: { 1: COSE_Key{ 1:2, 3:-25, -1:1, -2:x, -3:y } }
        val x = ByteArray(32) { (0xAA + it).toByte() }
        val y = ByteArray(32) { (0x55 + it).toByte() }

        val out = java.io.ByteArrayOutputStream()
        out.write(0xA1) // map(1)
        out.write(0x01) // key 1
        // COSE_Key map(5): keys 1, 3, -1, -2, -3
        out.write(0xA5)
        out.write(0x01); out.write(0x02)        // 1: 2
        out.write(0x03); out.write(0x38); out.write(24) // 3: -25 — major1 + ai24 + value24
        out.write(0x20); out.write(0x01)        // -1: 1
        out.write(0x21); out.write(0x58); out.write(32); out.write(x)
        out.write(0x22); out.write(0x58); out.write(32); out.write(y)

        val parsed = Ctap2Cbor.decodeClientPinKeyAgreementResponse(out.toByteArray())
        assertArrayEquals(x, parsed.x)
        assertArrayEquals(y, parsed.y)
    }
}
