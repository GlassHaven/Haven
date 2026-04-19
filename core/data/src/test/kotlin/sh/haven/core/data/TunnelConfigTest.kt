package sh.haven.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.db.entities.typeEnum

class TunnelConfigTest {

    @Test
    fun defaultsHaveUuidIdAndTimestamp() {
        val before = System.currentTimeMillis()
        val cfg = TunnelConfig(
            label = "home-vpn",
            type = TunnelConfigType.WIREGUARD.name,
            configText = "[Interface]".toByteArray(),
        )
        val after = System.currentTimeMillis()

        assertEquals(36, cfg.id.length) // UUID
        org.junit.Assert.assertTrue(cfg.createdAt in before..after)
    }

    @Test
    fun equalityIsIdBased() {
        // Match SshKey semantics — two rows with the same id are equal
        // regardless of differences in mutable fields. Lets lists built
        // from observed flows dedupe by id cleanly.
        val a = TunnelConfig(
            id = "same",
            label = "a",
            type = TunnelConfigType.WIREGUARD.name,
            configText = byteArrayOf(1),
        )
        val b = TunnelConfig(
            id = "same",
            label = "different",
            type = TunnelConfigType.TAILSCALE.name,
            configText = byteArrayOf(2, 3),
        )
        val c = a.copy(id = "other")

        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun typeEnumRoundTripsKnownValues() {
        TunnelConfigType.entries.forEach { t ->
            val cfg = TunnelConfig(
                label = "x",
                type = t.name,
                configText = ByteArray(0),
            )
            assertEquals(t, cfg.typeEnum)
        }
    }

    @Test
    fun typeEnumThrowsOnUnknownStoredValue() {
        // Defensive — a row with an unknown type string shouldn't silently
        // coerce to a default; that hides data corruption / botched
        // migrations. Loud failure forces explicit handling.
        val cfg = TunnelConfig(
            label = "x",
            type = "QUANTUM_ENCRYPTED",
            configText = ByteArray(0),
        )
        assertThrows(IllegalStateException::class.java) { cfg.typeEnum }
    }
}
