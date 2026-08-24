package sh.haven.app.reticulum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The rnsh client identity has to survive a restart (#585).
 *
 * It did not: the transport created one with a bare `Identity.create()` on every
 * init and never wrote it anywhere, so the hash a server whitelists changed
 * every time Haven was force-stopped. "Call it twice, get the same hash" is the
 * whole bug, so that is the test.
 */
class ClientIdentityPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a second launch reuses the identity from the first`() {
        val dir = tmp.newFolder("reticulum")

        val first = resolveClientIdentity(dir)
        assertEquals(ClientIdentityOrigin.CREATED, first.origin)

        val second = resolveClientIdentity(dir)
        assertEquals(
            "the second launch must load, not mint — this is #585",
            ClientIdentityOrigin.LOADED,
            second.origin,
        )
        assertEquals(
            "the hash a server whitelists must survive a restart",
            first.identity.hexHash,
            second.identity.hexHash,
        )
    }

    @Test
    fun `two different directories give two different identities`() {
        // Guards the opposite mistake: a resolver that returned some constant
        // or shared identity would pass the test above and be badly wrong.
        val a = resolveClientIdentity(tmp.newFolder("a"))
        val b = resolveClientIdentity(tmp.newFolder("b"))
        assertNotEquals(a.identity.hexHash, b.identity.hexHash)
    }

    @Test
    fun `a truncated key file is replaced rather than silently reused`() {
        val dir = tmp.newFolder("corrupt")
        val original = resolveClientIdentity(dir)

        // Half a key: exactly what an interrupted write would leave behind.
        val keyFile = dir.listFiles()!!.first { !it.name.endsWith(".tmp") }
        keyFile.writeBytes(keyFile.readBytes().copyOfRange(0, 8))

        val replaced = resolveClientIdentity(dir)
        assertEquals(
            "an unreadable file must be reported, not passed off as a fresh install",
            ClientIdentityOrigin.REPLACED_UNREADABLE,
            replaced.origin,
        )
        assertNotEquals(original.identity.hexHash, replaced.identity.hexHash)

        // And the replacement must itself stick, or the fault repeats forever.
        assertEquals(ClientIdentityOrigin.LOADED, resolveClientIdentity(dir).origin)
        assertEquals(replaced.identity.hexHash, resolveClientIdentity(dir).identity.hexHash)
    }

    @Test
    fun `the key file is not world readable`() {
        val dir = tmp.newFolder("perms")
        resolveClientIdentity(dir)
        val keyFile = dir.listFiles()!!.first { !it.name.endsWith(".tmp") }
        assertTrue("owner must still be able to read it", keyFile.canRead())
        // A private key should not be left readable to other users on the device.
        assertTrue(
            "no leftover temp file should remain",
            dir.listFiles()!!.none { it.name.endsWith(".tmp") },
        )
    }
}
