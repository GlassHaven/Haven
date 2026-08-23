package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The jump-host scan asks the far side which networks it is on and sweeps the
 * /24 around each answer. Everything downstream of this parse is 254 probes
 * through someone else's SSH connection, so the parse is where the blast radius
 * is decided.
 */
class JumpSubnetParseTest {

    private val typical = """
        1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever preferred_lft forever
        2: eth0    inet 192.168.1.50/24 brd 192.168.1.255 scope global eth0\       valid_lft forever preferred_lft forever
    """.trimIndent()

    @Test
    fun `takes the 24 around a global address`() {
        assertEquals(listOf("192.168.1"), NetworkDiscovery.parseSubnetBases(typical))
    }

    /**
     * Real `ip -o -4 addr` output from a developer workstation (msi-z790,
     * 2026-08-23). Without the default route this yields FOUR networks and the
     * sweep becomes 1016 probes across docker0, virbr0 and a compose bridge —
     * three of which nobody asked to scan. This is the case that made a live
     * scan look like it found nothing.
     */
    private val devBox = """
        2: enp11s0    inet 192.168.0.180/24 brd 192.168.0.255 scope global dynamic noprefixroute enp11s0
        5: virbr0    inet 192.168.122.1/24 brd 192.168.122.255 scope global virbr0
        6: docker0    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0
        7: br-d36e782dbae6    inet 172.16.0.1/24 brd 172.16.0.255 scope global br-d36e782dbae6
    """.trimIndent()

    private val defaultRoute =
        "default via 192.168.0.1 dev enp11s0 proto dhcp src 192.168.0.180 metric 100"

    @Test
    fun `the default route picks the one network the host is really on`() {
        assertEquals(
            listOf("192.168.0"),
            NetworkDiscovery.parseSubnetBases(devBox, defaultRoute),
        )
    }

    @Test
    fun `without a default route every bridge is swept - the case to avoid`() {
        val bases = NetworkDiscovery.parseSubnetBases(devBox)
        assertEquals(listOf("192.168.0", "192.168.122", "172.17.0", "172.16.0"), bases)
        assertEquals("1016 probes is why the route lookup exists", 1016, bases.size * 254)
    }

    /** A route naming an interface with no global address must not scan nothing. */
    @Test
    fun `an unmatched default route falls back to every network`() {
        val bases = NetworkDiscovery.parseSubnetBases(devBox, "default via 10.0.0.1 dev wg0")
        assertTrue("must not silently scan nothing", bases.isNotEmpty())
        assertTrue(bases.contains("192.168.0"))
    }

    @Test
    fun `drops loopback`() {
        val bases = NetworkDiscovery.parseSubnetBases(typical)
        assertTrue("loopback must never be swept", bases.none { it.startsWith("127.") })
    }

    @Test
    fun `drops link-local`() {
        val out = "3: eth1    inet 169.254.3.7/16 brd 169.254.255.255 scope global eth1"
        assertEquals(emptyList<String>(), NetworkDiscovery.parseSubnetBases(out))
    }

    /**
     * The one that matters. Honouring a declared /16 would mean 65,536 probes
     * through a single SSH connection — so the prefix is deliberately ignored
     * and only the /24 containing the address is swept.
     */
    @Test
    fun `a 16 is narrowed to the 24 containing the address, not expanded`() {
        val out = "2: eth0    inet 10.1.2.3/16 brd 10.1.255.255 scope global eth0"
        assertEquals(listOf("10.1.2"), NetworkDiscovery.parseSubnetBases(out))
    }

    @Test
    fun `a multi-homed jump host yields each distinct network once`() {
        val out = """
            2: eth0    inet 192.168.1.50/24 scope global eth0
            3: eth1    inet 10.8.0.2/24 scope global eth1
            4: eth2    inet 192.168.1.51/24 scope global eth2
        """.trimIndent()
        assertEquals(listOf("192.168.1", "10.8.0"), NetworkDiscovery.parseSubnetBases(out))
    }

    /**
     * `ip` missing, permission denied, a login banner — anything that is not
     * address output must scan nothing rather than guess a network.
     */
    @Test
    fun `unparseable output scans nothing`() {
        assertEquals(emptyList<String>(), NetworkDiscovery.parseSubnetBases(""))
        assertEquals(emptyList<String>(), NetworkDiscovery.parseSubnetBases("ip: command not found"))
        assertEquals(emptyList<String>(), NetworkDiscovery.parseSubnetBases("Welcome to Ubuntu 24.04 LTS"))
    }

    @Test
    fun `an out-of-range octet is rejected rather than probed`() {
        assertEquals(emptyList<String>(), NetworkDiscovery.parseSubnetBases("inet 999.1.1.1/24 scope global"))
    }
}
