package sh.haven.feature.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class InputCoalescerTest {

    @Test
    fun `Vietnamese IME replacement preserves two DEL bytes`() {
        val input = listOf(
            0x7F.toByte(),
            0x7F.toByte(),
        )

        val output = normalizeCoalescedInput(input)

        assertArrayEquals(
            byteArrayOf(
                0x7F.toByte(),
                0x7F.toByte(),
            ),
            output,
        )
    }

    @Test
    fun `historical duplicate printable ASCII workaround is preserved`() {
        val input = listOf(
            'a'.code.toByte(),
            'a'.code.toByte(),
        )

        val output = normalizeCoalescedInput(input)

        assertArrayEquals(
            byteArrayOf('a'.code.toByte()),
            output,
        )
    }

    @Test
    fun `repeated control bytes are never deduplicated`() {
        val controls = listOf(
            0x03, // Ctrl-C
            0x09, // TAB
            0x0D, // CR / Enter
            0x1B, // ESC
            0x7F, // DEL / Backspace
        )

        for (value in controls) {
            val b = value.toByte()

            assertArrayEquals(
                "control byte 0x%02X must be preserved twice".format(value),
                byteArrayOf(b, b),
                normalizeCoalescedInput(listOf(b, b)),
            )
        }
    }

    @Test
    fun `different printable bytes are preserved`() {
        val input = listOf(
            'a'.code.toByte(),
            'b'.code.toByte(),
        )

        assertArrayEquals(
            byteArrayOf(
                'a'.code.toByte(),
                'b'.code.toByte(),
            ),
            normalizeCoalescedInput(input),
        )
    }

    @Test
    fun `three identical printable bytes are preserved`() {
        val a = 'a'.code.toByte()

        assertArrayEquals(
            byteArrayOf(a, a, a),
            normalizeCoalescedInput(listOf(a, a, a)),
        )
    }
}
