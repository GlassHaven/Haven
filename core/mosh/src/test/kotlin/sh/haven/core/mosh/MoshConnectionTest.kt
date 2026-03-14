package sh.haven.core.mosh

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import sh.haven.core.mosh.network.MoshConnection

class MoshConnectionTest {

    @Test
    fun `zlib compress decompress roundtrip`() {
        val original = "Hello, mosh! This is a test of zlib compression.".toByteArray()
        val compressed = MoshConnection.zlibCompress(original)
        val decompressed = MoshConnection.zlibDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    @Test
    fun `zlib roundtrip with empty data`() {
        val original = ByteArray(0)
        val compressed = MoshConnection.zlibCompress(original)
        val decompressed = MoshConnection.zlibDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    @Test
    fun `zlib roundtrip with binary data`() {
        val original = ByteArray(1024) { (it % 256).toByte() }
        val compressed = MoshConnection.zlibCompress(original)
        val decompressed = MoshConnection.zlibDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    @Test
    fun `zlib roundtrip with protobuf-like data`() {
        // Simulate a TransportInstruction with a small diff
        val original = byteArrayOf(
            0x08, 0x02,             // field 1 varint: protocol_version=2
            0x10, 0x00,             // field 2 varint: old_num=0
            0x18, 0x05,             // field 3 varint: new_num=5
            0x20, 0x03,             // field 4 varint: ack_num=3
            0x32, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F, // field 6 bytes: "Hello"
        )
        val compressed = MoshConnection.zlibCompress(original)
        val decompressed = MoshConnection.zlibDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }
}
