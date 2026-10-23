package sh.haven.core.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DER → SSH ECDSA signature wire conversion (pure function, JVM-testable).
 *
 * Boundary: byte-format conversion only — ASN.1 DER `SEQUENCE { INTEGER r,
 * INTEGER s }` → SSH `mpint r || mpint s`. It knows nothing about Keystore,
 * signing algorithm or curve; AndroidKeyStore emits DER exactly like CTAP2
 * does, while the `ecdsa-sha2-*` signature blob wants the two mpints
 * concatenated.
 *
 * Consumed by both core/fido's FidoIdentity (FIDO sk-ecdsa) and core/ssh's
 * KeystoreSshIdentity — the single source of truth for DER→SSH mpint
 * conversion after the cross-module dedup.
 */
fun derEcdsaToSshBlob(der: ByteArray): ByteArray {
    var pos = 0
    fun readByte(): Int {
        require(pos < der.size) { "truncated DER signature" }
        return der[pos++].toInt() and 0xFF
    }
    fun readLength(): Int {
        val first = readByte()
        if (first < 0x80) return first
        val numBytes = first and 0x7F
        require(numBytes in 1..2) { "unsupported DER length form" }
        var len = 0
        repeat(numBytes) { len = (len shl 8) or readByte() }
        return len
    }
    fun readInteger(): BigInteger {
        require(readByte() == 0x02) { "expected DER INTEGER" }
        val len = readLength()
        require(len > 0 && pos + len <= der.size) { "bad DER INTEGER length" }
        val bytes = der.copyOfRange(pos, pos + len)
        pos += len
        return BigInteger(bytes)
    }

    require(readByte() == 0x30) { "expected DER SEQUENCE" }
    val seqLen = readLength()
    require(pos + seqLen == der.size) { "DER SEQUENCE length mismatch" }
    val r = readInteger()
    val s = readInteger()
    require(pos == der.size) { "trailing bytes after DER signature" }
    require(r.signum() > 0 && s.signum() > 0) { "non-positive ECDSA parameter" }

    val out = ByteArrayOutputStream()
    for (v in listOf(r, s)) {
        // BigInteger.toByteArray() is minimal two's-complement with a leading
        // 0x00 when the top bit is set — exactly the mpint content encoding.
        val b = v.toByteArray()
        val lenBuf = ByteBuffer.allocate(4)
        lenBuf.order(ByteOrder.BIG_ENDIAN)
        lenBuf.putInt(b.size)
        out.write(lenBuf.array())
        out.write(b)
    }
    return out.toByteArray()
}
