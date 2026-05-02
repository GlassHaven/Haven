package sh.haven.core.data.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollbackRingTest {

    @Test
    fun `empty ring returns empty snapshot`() {
        val ring = ScrollbackRing(capacity = 64)
        assertEquals(0, ring.snapshot().size)
    }

    @Test
    fun `single small append round-trips`() {
        val ring = ScrollbackRing(capacity = 64)
        val data = "hello".toByteArray()
        ring.append(data, 0, data.size)
        assertArrayEquals(data, ring.snapshot())
    }

    @Test
    fun `multiple appends concatenate in order`() {
        val ring = ScrollbackRing(capacity = 64)
        ring.append("hello ".toByteArray(), 0, 6)
        ring.append("world".toByteArray(), 0, 5)
        assertEquals("hello world", String(ring.snapshot()))
    }

    @Test
    fun `wraparound preserves chronological order`() {
        val ring = ScrollbackRing(capacity = 8)
        ring.append("AAAAAAA".toByteArray(), 0, 7)        // 7/8 used
        ring.append("BBBB".toByteArray(), 0, 4)            // wraps; "AA" + "AAAAA"+"BBBB" trimmed to last 8
        // After: ring holds the last 8 bytes written: "AAAAABBBB" → trim oldest "A" → "AAAABBBB"
        assertEquals("AAAABBBB", String(ring.snapshot()))
    }

    @Test
    fun `oversize append keeps only the tail`() {
        val ring = ScrollbackRing(capacity = 4)
        val data = "0123456789".toByteArray()
        ring.append(data, 0, data.size)
        assertEquals("6789", String(ring.snapshot()))
    }

    @Test
    fun `oversize append at non-zero state still keeps only the tail`() {
        val ring = ScrollbackRing(capacity = 4)
        ring.append("XX".toByteArray(), 0, 2)
        ring.append("0123456789".toByteArray(), 0, 10)
        assertEquals("6789", String(ring.snapshot()))
    }

    @Test
    fun `zero-length append is a no-op`() {
        val ring = ScrollbackRing(capacity = 8)
        ring.append("hi".toByteArray(), 0, 2)
        ring.append(ByteArray(8), 0, 0)
        assertEquals("hi", String(ring.snapshot()))
    }

    @Test
    fun `snapshot returns a fresh copy each call`() {
        val ring = ScrollbackRing(capacity = 8)
        ring.append("abc".toByteArray(), 0, 3)
        val first = ring.snapshot()
        first[0] = 'Z'.code.toByte()
        assertEquals("abc", String(ring.snapshot()))
    }

    @Test
    fun `offset slice is respected`() {
        val ring = ScrollbackRing(capacity = 16)
        val source = "PREFIX:hello".toByteArray()
        ring.append(source, 7, 5)  // skip "PREFIX:"
        assertEquals("hello", String(ring.snapshot()))
    }
}
