package sh.haven.app.reticulum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Importing a Reticulum identity the user supplies — the second half of #585.
 *
 * The reporter keeps a whitelist keyed to the identity hash, so the damage to
 * avoid is not a failed import but a successful-looking one that loses the
 * identity already in use. Every test here is about that: a bad file must cost
 * nothing, and a good one must be what the next launch loads.
 */
class ClientIdentityImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** An identity file written the way a user would arrive with one. */
    private fun identityFile(name: String): Pair<File, String> {
        val dir = tmp.newFolder(name)
        val resolved = resolveClientIdentity(dir)
        return File(dir, "haven_client_identity") to resolved.identity.hexHash
    }

    @Test
    fun `an imported identity is what the next launch loads`() {
        val configDir = tmp.newFolder("reticulum")
        val (source, sourceHash) = identityFile("elsewhere")

        val result = importClientIdentity(configDir, source)

        assertTrue("import should have installed: $result", result is IdentityImport.Installed)
        assertEquals(sourceHash, (result as IdentityImport.Installed).hexHash)
        assertNull("nothing was there to replace", result.replacedHexHash)
        assertEquals(
            "the point of importing is that the imported hash is the one in use",
            sourceHash,
            resolveClientIdentity(configDir).identity.hexHash,
        )
        assertEquals(ClientIdentityOrigin.LOADED, resolveClientIdentity(configDir).origin)
    }

    @Test
    fun `importing over an existing identity reports what it replaced`() {
        val configDir = tmp.newFolder("reticulum")
        val existingHash = resolveClientIdentity(configDir).identity.hexHash
        val (source, sourceHash) = identityFile("elsewhere")

        val result = importClientIdentity(configDir, source) as IdentityImport.Installed

        assertEquals(sourceHash, result.hexHash)
        assertEquals(
            "the user needs to be told which hash they just stopped being",
            existingHash,
            result.replacedHexHash,
        )
    }

    @Test
    fun `the replaced identity is kept so a mistaken import is recoverable`() {
        val configDir = tmp.newFolder("reticulum")
        val existingHash = resolveClientIdentity(configDir).identity.hexHash
        val (source, _) = identityFile("elsewhere")

        importClientIdentity(configDir, source)

        val backup = File(configDir, "haven_client_identity.previous")
        assertTrue("the previous key must survive the import", backup.exists())
        val recovered = network.reticulum.identity.Identity.fromFile(backup.absolutePath)
        assertNotNull("the backup must still parse as an identity", recovered)
        assertEquals(existingHash, recovered!!.hexHash)
    }

    @Test
    fun `a file that is not an identity leaves the existing one untouched`() {
        val configDir = tmp.newFolder("reticulum")
        val existingHash = resolveClientIdentity(configDir).identity.hexHash
        val junk = tmp.newFile("notes.txt").apply { writeText("this is not a key") }

        val result = importClientIdentity(configDir, junk)

        assertTrue("junk must be refused: $result", result is IdentityImport.NotAnIdentity)
        assertEquals(
            "a refused import must not cost the user their identity — that is #585 again",
            existingHash,
            resolveClientIdentity(configDir).identity.hexHash,
        )
    }

    @Test
    fun `reading the stored hash does not mint an identity`() {
        val configDir = tmp.newFolder("reticulum")

        assertNull(
            "an untouched config dir must report no identity, not invent one",
            storedClientIdentityHash(configDir),
        )
        assertTrue(
            "reading must not have written a key",
            configDir.listFiles().orEmpty().isEmpty(),
        )

        val created = resolveClientIdentity(configDir).identity.hexHash
        assertEquals(created, storedClientIdentityHash(configDir))
    }

    @Test
    fun `a missing source is refused and says so`() {
        val configDir = tmp.newFolder("reticulum")
        val existingHash = resolveClientIdentity(configDir).identity.hexHash

        val result = importClientIdentity(configDir, File(configDir, "no_such_file"))

        assertTrue(result is IdentityImport.NotAnIdentity)
        assertTrue(
            "the reason should name the path so the user can see what was looked for",
            (result as IdentityImport.NotAnIdentity).reason.contains("no_such_file"),
        )
        assertEquals(existingHash, resolveClientIdentity(configDir).identity.hexHash)
    }
}
