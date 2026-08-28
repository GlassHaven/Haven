package sh.haven.feature.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.SshKey

/**
 * Pure-helper tests for the #531 wrong-row guard: importing the same
 * security key twice used to mint a second row (fresh UUID primary key)
 * with an identical fingerprint, so toggling "Require PIN" on the row
 * the user sees could leave the row the connect actually signs with
 * unchanged. The helpers below are what the ViewModel wires in; they
 * must hold without Android or Room.
 */
class SkKeyDedupTest {

    private fun stored(
        id: String,
        fingerprint: String,
        keyType: String = "sk-ssh-ed25519@openssh.com",
    ): SshKey = SshKey(
        id = id,
        label = "key $id",
        keyType = keyType,
        privateKeyBytes = ByteArray(0),
        publicKeyOpenSsh = "$keyType AAAA $id",
        fingerprintSha256 = fingerprint,
    )

    // ---- findDuplicateByKeyType ----

    @Test
    fun `duplicate fingerprint is found`() {
        val existing = stored("a", "FP1")
        val dup = findDuplicateByKeyType(listOf(existing), "FP1", "sk-ssh-ed25519@openssh.com")
        assertEquals(existing, dup)
    }

    @Test
    fun `no match on a different fingerprint`() {
        assertNull(findDuplicateByKeyType(listOf(stored("a", "FP1")), "FP2", "sk-ssh-ed25519@openssh.com"))
    }

    @Test
    fun `no match against a different key type`() {
        // Same blob fingerprint must not make an SK import collide with a
        // non-SK row (or an sk-ecdsa row): the guard is per key type.
        val rows = listOf(
            stored("a", "FP1", keyType = "ssh-ed25519"),
            stored("b", "FP1", keyType = "sk-ecdsa-sha2-nistp256@openssh.com"),
        )
        assertNull(findDuplicateByKeyType(rows, "FP1", "sk-ssh-ed25519@openssh.com"))
    }

    @Test
    fun `empty store has no duplicate`() {
        assertNull(findDuplicateByKeyType(emptyList(), "FP1", "sk-ssh-ed25519@openssh.com"))
    }

    @Test
    fun `first match wins when several rows already share a fingerprint`() {
        val rows = listOf(stored("a", "FP1"), stored("b", "FP1"))
        assertEquals("a", findDuplicateByKeyType(rows, "FP1", "sk-ssh-ed25519@openssh.com")?.id)
    }

    // ---- verifyRequiredAmbiguityWarning ----

    @Test
    fun `no warning when the toggled row is the only one with its fingerprint`() {
        val target = stored("a", "FP1")
        val others = listOf(stored("b", "FP2"), stored("c", "FP3"))
        assertNull(verifyRequiredAmbiguityWarning(target, others))
    }

    @Test
    fun `warning names the count of other rows sharing the fingerprint`() {
        val target = stored("a", "FP1")
        val others = listOf(stored("b", "FP1"), stored("c", "FP1"), stored("d", "FP2"))
        val warning = verifyRequiredAmbiguityWarning(target, others)
        assertTrue("expected a warning, got null", warning != null)
        // Two other rows share FP1 — the message must say 2, not 3.
        assertTrue("warning should count only the other rows: $warning", warning!!.contains("2"))
    }

    @Test
    fun `the toggled row itself never counts as a duplicate of itself`() {
        // Same row object present in `others` must not raise the warning.
        val target = stored("a", "FP1")
        assertNull(verifyRequiredAmbiguityWarning(target, listOf(target)))
    }
}
