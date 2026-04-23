package sh.haven.core.vnc.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Marker interface for messages that can be serialized. */
interface Encodable {
    fun encode(out: OutputStream)
}

data class ProtocolVersion(val major: Int, val minor: Int) : Encodable {
    fun atLeast(maj: Int, min: Int) = major > maj || (major == maj && minor >= min)

    override fun encode(out: OutputStream) {
        out.write("RFB %03d.%03d\n".format(major, minor).toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    companion object {
        private val PATTERN = Regex("RFB (\\d{3})\\.(\\d{3})")
        fun decode(input: InputStream): ProtocolVersion {
            val buf = ByteArray(12)
            DataInputStream(input).readFully(buf)
            val str = String(buf, Charsets.US_ASCII)
            val match = PATTERN.find(str) ?: throw VncException("Invalid protocol: $str")
            return ProtocolVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
    }
}

data class PixelFormat(
    val bitsPerPixel: Int,
    val depth: Int,
    val bigEndian: Boolean,
    val trueColor: Boolean,
    val redMax: Int,
    val greenMax: Int,
    val blueMax: Int,
    val redShift: Int,
    val greenShift: Int,
    val blueShift: Int,
) : Encodable {
    val bytesPerPixel get() = bitsPerPixel / 8

    override fun encode(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeByte(bitsPerPixel)
        d.writeByte(depth)
        d.writeBoolean(bigEndian)
        d.writeBoolean(trueColor)
        d.writeShort(redMax)
        d.writeShort(greenMax)
        d.writeShort(blueMax)
        d.writeByte(redShift)
        d.writeByte(greenShift)
        d.writeByte(blueShift)
        d.write(ByteArray(3)) // padding
    }

    companion object {
        fun decode(input: InputStream): PixelFormat {
            val d = DataInputStream(input)
            val bpp = d.readUnsignedByte()
            val depth = d.readUnsignedByte()
            val bigEndian = d.readBoolean()
            val trueColor = d.readBoolean()
            val redMax = d.readUnsignedShort()
            val greenMax = d.readUnsignedShort()
            val blueMax = d.readUnsignedShort()
            val redShift = d.readUnsignedByte()
            val greenShift = d.readUnsignedByte()
            val blueShift = d.readUnsignedByte()
            d.readFully(ByteArray(3))
            return PixelFormat(bpp, depth, bigEndian, trueColor, redMax, greenMax, blueMax, redShift, greenShift, blueShift)
        }
    }
}

data class ServerInit(
    val framebufferWidth: Int,
    val framebufferHeight: Int,
    val pixelFormat: PixelFormat,
    val name: String,
) {
    companion object {
        fun decode(input: InputStream): ServerInit {
            val d = DataInputStream(input)
            val w = d.readUnsignedShort()
            val h = d.readUnsignedShort()
            val pf = PixelFormat.decode(input)
            val nameLen = d.readInt()
            val nameBytes = ByteArray(nameLen)
            d.readFully(nameBytes)
            return ServerInit(w, h, pf, String(nameBytes, Charsets.US_ASCII))
        }
    }
}

/** Encoding types used in VNC framebuffer updates. */
enum class Encoding(val code: Int) {
    RAW(0),
    COPYRECT(1),
    RRE(2),
    HEXTILE(5),
    ZLIB(6),
    ZRLE(16),
    DESKTOP_SIZE(-223),
    CURSOR(-239);

    companion object {
        private val map = entries.associateBy { it.code }
        fun resolve(code: Int): Encoding? = map[code]
    }
}

data class Rectangle(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val encoding: Encoding?,
) {
    companion object {
        fun decode(input: InputStream): Rectangle {
            val d = DataInputStream(input)
            return Rectangle(
                x = d.readUnsignedShort(),
                y = d.readUnsignedShort(),
                width = d.readUnsignedShort(),
                height = d.readUnsignedShort(),
                encoding = Encoding.resolve(d.readInt()),
            )
        }
    }
}

data class FramebufferUpdate(val numberOfRectangles: Int) {
    companion object {
        fun decode(input: InputStream): FramebufferUpdate {
            val d = DataInputStream(input)
            d.readUnsignedByte() // type byte (already read by caller via PushbackInputStream)
            d.readUnsignedByte() // padding
            return FramebufferUpdate(d.readUnsignedShort())
        }
    }
}

class FramebufferUpdateRequest(
    private val incremental: Boolean,
    private val x: Int,
    private val y: Int,
    private val width: Int,
    private val height: Int,
) : Encodable {
    override fun encode(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeByte(3) // message type
        d.writeBoolean(incremental)
        d.writeShort(x)
        d.writeShort(y)
        d.writeShort(width)
        d.writeShort(height)
    }
}

class KeyEvent(
    private val keySym: Int,
    private val pressed: Boolean,
) : Encodable {
    override fun encode(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeByte(4) // message type
        d.writeBoolean(pressed)
        d.write(ByteArray(2)) // padding
        d.writeInt(keySym)
    }
}

class PointerEvent(
    private val x: Int,
    private val y: Int,
    private val buttonMask: Int,
) : Encodable {
    override fun encode(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeByte(5) // message type
        d.writeByte(buttonMask)
        d.writeShort(x)
        d.writeShort(y)
    }
}

class ClientCutText(private val text: String) : Encodable {
    override fun encode(out: OutputStream) {
        val d = DataOutputStream(out)
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        d.writeByte(6) // message type
        d.write(ByteArray(3)) // padding
        d.writeInt(bytes.size)
        d.write(bytes)
    }
}

class ClientInit(private val shared: Boolean) : Encodable {
    override fun encode(out: OutputStream) {
        out.write(if (shared) 1 else 0)
        out.flush()
    }
}

class SetPixelFormat(private val pixelFormat: PixelFormat) : Encodable {
    override fun encode(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeByte(0) // message type
        d.write(ByteArray(3)) // padding
        pixelFormat.encode(out)
    }
}

class SetEncodings(private val encodings: List<Encoding>) : Encodable {
    override fun encode(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeByte(2) // message type
        d.writeByte(0) // padding
        d.writeShort(encodings.size)
        for (enc in encodings) d.writeInt(enc.code)
    }
}

data class ColorMapEntry(val red: Int, val green: Int, val blue: Int) {
    companion object {
        fun decode(input: InputStream): ColorMapEntry {
            val d = DataInputStream(input)
            return ColorMapEntry(d.readUnsignedShort(), d.readUnsignedShort(), d.readUnsignedShort())
        }
    }
}

data class SetColorMapEntries(val firstColor: Int, val colors: List<ColorMapEntry>) {
    companion object {
        fun decode(input: InputStream): SetColorMapEntries {
            val d = DataInputStream(input)
            d.readUnsignedByte() // type byte
            d.readUnsignedByte() // padding
            val first = d.readUnsignedShort()
            val count = d.readUnsignedShort()
            val colors = (0 until count).map { ColorMapEntry.decode(input) }
            return SetColorMapEntries(first, colors)
        }
    }
}

object ServerCutText {
    fun decode(input: InputStream): String {
        val d = DataInputStream(input)
        d.readUnsignedByte() // type byte
        d.readFully(ByteArray(3)) // padding
        val length = d.readInt()
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        d.readFully(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }
}

open class VncException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AuthenticationFailedException(message: String) : VncException(message)
class HandshakingFailedException(message: String) : VncException(message)
