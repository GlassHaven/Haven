package sh.haven.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A RETICULUM profile is addressed by its destination hash, so create_connection
 * has to reject a bad one up front.
 *
 * Left to connect time it is indistinguishable from an unreachable destination:
 * awaitPath waits 20s for a path that can never resolve and then reports a
 * timeout, which reads as the mesh being down rather than as a typo.
 */
class ReticulumDestinationHashTest {

    private val valid = "a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun `a well formed hash is accepted`() {
        assertEquals(valid, normaliseReticulumDestinationHash(valid))
    }

    @Test
    fun `case and surrounding space are normalised away`() {
        assertEquals(valid, normaliseReticulumDestinationHash("  ${valid.uppercase()}  "))
    }

    @Test
    fun `a blank hash is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            normaliseReticulumDestinationHash("   ")
        }
    }

    @Test
    fun `a hash of the wrong length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            normaliseReticulumDestinationHash(valid.dropLast(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            normaliseReticulumDestinationHash(valid + "ab")
        }
    }

    @Test
    fun `a non hex character is rejected`() {
        // Right length, wrong alphabet: hexToBytes would throw deep inside the
        // connect instead, long after the profile was saved.
        assertThrows(IllegalArgumentException::class.java) {
            normaliseReticulumDestinationHash(valid.dropLast(1) + "z")
        }
    }
}
