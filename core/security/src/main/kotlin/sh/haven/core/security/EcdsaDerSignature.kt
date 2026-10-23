package sh.haven.core.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DER → SSH ECDSA 签名 wire 转换（纯函数，JVM 可测）。
 *
 * 职责边界: 只做字节格式转换 — ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }`
 * → SSH `mpint r || mpint s`；不关心 Keystore / 签名算法 / 曲线。AndroidKeyStore
 * 与 CTAP2 一样吐 DER，而 `ecdsa-sha2-*` 的签名字段要 mpint 串联，消费方是
 * core/ssh 的 KeystoreSshIdentity（T5）。
 *
 * 注: core/fido 的 FidoIdentity（FIDO sk-ecdsa 场景）与 core/ssh 的
 * KeystoreSshIdentity（T5）都消费本实现 —— 它是 DER→SSH mpint 转换的
 * 唯一事实来源（跨模块去重后的规范落点）。
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
