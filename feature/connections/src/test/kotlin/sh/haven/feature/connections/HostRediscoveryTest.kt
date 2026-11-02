package sh.haven.feature.connections

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ssh.KnownHostEntry

/**
 * #376 — follow a device across DHCP address changes by SSH host key. Pins
 * the matching rules: exactly one key match follows (persist host + seed the
 * known-hosts row for the new address); ambiguity, wrong keys, no stored key,
 * and non-private addresses all fail closed with no writes.
 */
class HostRediscoveryTest {

    private val storedKey = KnownHost(
        id = 7, hostname = "192.168.43.5", port = 22,
        keyType = "ssh-ed25519", publicKeyBase64 = "AAAAtheRightKey",
        fingerprint = "SHA256:right", firstSeen = 1L,
    )
    private val profile = ConnectionProfile(
        id = "p1", label = "comma", host = "192.168.43.5", username = "comma",
    )

    private fun entry(ip: String, key: String, type: String = "ssh-ed25519") =
        KnownHostEntry(hostname = ip, port = 22, keyType = type, publicKeyBase64 = key)

    private fun rediscovery(
        dao: KnownHostDao,
        repo: ConnectionRepository = mockk(relaxed = true),
        responders: Set<String> = emptySet(),
        keys: Map<String, KnownHostEntry> = emptyMap(),
    ): HostRediscovery = HostRediscovery(
        knownHostDao = dao,
        connectionRepository = repo,
    ).apply {
        localBases = { setOf("192.168.43") }
        probe = { host, _, _ -> host in responders }
        keyScan = { host, _ -> keys[host] }
    }

    private fun daoWithStored(): KnownHostDao = mockk(relaxed = true) {
        coEvery { findByHostPort("192.168.43.5", 22) } returns storedKey
    }

    @Test
    fun `single key match persists host and seeds known-hosts row`() = runTest {
        val dao = daoWithStored()
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(
            dao, repo,
            responders = setOf("192.168.43.23", "192.168.43.1"),
            keys = mapOf(
                "192.168.43.23" to entry("192.168.43.23", "AAAAtheRightKey"),
                "192.168.43.1" to entry("192.168.43.1", "AAAArouterKey"),
            ),
        )

        assertEquals("192.168.43.23", r.rediscover(profile))
        coVerify(exactly = 1) { repo.updateHost("p1", "192.168.43.23") }
        coVerify(exactly = 1) {
            dao.upsert(match { it.hostname == "192.168.43.23" && it.publicKeyBase64 == "AAAAtheRightKey" })
        }
    }

    @Test
    fun `finds a device on the phone's hotspot after Android reassigns the subnet`() = runTest {
        // #367 ground truth (ehoeve786): the phone is the DHCP server for the comma
        // over its hotspot. Android hands the AP a fresh RANDOM subnet each session,
        // so the device moved 10.235.30.225 -> 10.50.150.4 — a DIFFERENT /24. Neither
        // its old /24 nor the phone's cellular default network is right; the device
        // is on the phone's TETHER interface (10.50.150.1), which only interface
        // enumeration exposes.
        val storedOnOldSubnet = KnownHost(
            id = 7, hostname = "10.235.30.225", port = 22,
            keyType = "ssh-ed25519", publicKeyBase64 = "AAAAtheRightKey",
            fingerprint = "SHA256:right", firstSeen = 1L,
        )
        val dao = mockk<KnownHostDao>(relaxed = true) {
            coEvery { findByHostPort("10.235.30.225", 22) } returns storedOnOldSubnet
        }
        val commaProfile = profile.copy(host = "10.235.30.225")
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(
            dao, repo,
            responders = setOf("10.50.150.4"), // comma's new lease on the tether /24
            keys = mapOf("10.50.150.4" to entry("10.50.150.4", "AAAAtheRightKey")),
        ).apply {
            // The phone's interfaces: cellular (default) + the hotspot AP at 10.50.150.1.
            // The old /24 (10.235.30) is gone — nothing there to find.
            localBases = { setOf("100.72.14", "10.50.150") }
        }

        assertEquals("10.50.150.4", r.rediscover(commaProfile))
        coVerify(exactly = 1) { repo.updateHost("p1", "10.50.150.4") }
        coVerify(exactly = 1) {
            dao.upsert(match { it.hostname == "10.50.150.4" && it.publicKeyBase64 == "AAAAtheRightKey" })
        }
    }

    @Test
    fun `two machines with the same key is ambiguous - no follow`() = runTest {
        val dao = daoWithStored()
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(
            dao, repo,
            responders = setOf("192.168.43.23", "192.168.43.24"),
            keys = mapOf(
                "192.168.43.23" to entry("192.168.43.23", "AAAAtheRightKey"),
                "192.168.43.24" to entry("192.168.43.24", "AAAAtheRightKey"),
            ),
        )

        assertNull(r.rediscover(profile))
        coVerify(exactly = 0) { repo.updateHost(any(), any()) }
    }

    @Test
    fun `no stored known-host key - no scan, no follow`() = runTest {
        val dao = mockk<KnownHostDao>(relaxed = true) {
            coEvery { findByHostPort(any(), any()) } returns null
        }
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(dao, repo, responders = setOf("192.168.43.23"))
            .apply { localBases = { throw AssertionError("scan must not run without a stored key") } }

        assertNull(r.rediscover(profile))
        coVerify(exactly = 0) { repo.updateHost(any(), any()) }
    }

    @Test
    fun `key type mismatch does not match`() = runTest {
        val dao = daoWithStored()
        val r = rediscovery(
            dao,
            responders = setOf("192.168.43.23"),
            keys = mapOf("192.168.43.23" to entry("192.168.43.23", "AAAAtheRightKey", type = "ssh-rsa")),
        )

        assertNull(r.rediscover(profile))
    }

    @Test
    fun `public or hostname address is never rediscovered`() = runTest {
        val dao = daoWithStored()
        val r = rediscovery(dao)
        assertNull(r.rediscover(profile.copy(host = "example.com")))
        assertNull(r.rediscover(profile.copy(host = "8.8.8.8")))
    }

    @Test
    fun `session-only mode connects via new host and seeds known-hosts without updating profile host`() = runTest {
        // #776: profile.host is a permanent WireGuard IP (10.6.0.6), while the
        // same machine is reachable on the home LAN (192.168.43.23) when the
        // tunnel is down. With `HavenHostRediscovery session`, rediscover
        // returns 192.168.43.23 and seeds known_hosts so TOFU succeeds, but
        // leaves profile.host untouched in ConnectionRepository.
        val wgStored = storedKey.copy(hostname = "10.6.0.6")
        val dao = mockk<KnownHostDao>(relaxed = true) {
            coEvery { findByHostPort("10.6.0.6", 22) } returns wgStored
        }
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(
            dao, repo,
            responders = setOf("192.168.43.23"),
            keys = mapOf("192.168.43.23" to entry("192.168.43.23", "AAAAtheRightKey")),
        )
        val wgProfile = profile.copy(
            host = "10.6.0.6",
            sshOptions = "ServerAliveInterval 60\nHavenHostRediscovery session",
        )

        assertEquals("192.168.43.23", r.rediscover(wgProfile))
        coVerify(exactly = 0) { repo.updateHost(any(), any()) }
        coVerify(exactly = 1) {
            dao.upsert(match { it.hostname == "192.168.43.23" && it.publicKeyBase64 == "AAAAtheRightKey" })
        }
    }

    @Test
    fun `off mode skips scan and returns null`() = runTest {
        val dao = daoWithStored()
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(dao, repo, responders = setOf("192.168.43.23"))
            .apply { localBases = { throw AssertionError("scan must not run when HavenHostRediscovery is off") } }

        assertNull(r.rediscover(profile.copy(sshOptions = "HavenHostRediscovery off")))
        coVerify(exactly = 0) { repo.updateHost(any(), any()) }
    }

    @Test
    fun `routed profile via proxy, tunnel, or jump host skips local LAN rediscovery`() = runTest {
        // #777: when a profile routes through a SOCKS/HTTP proxy, tunnel, or
        // jump host and the proxy dial fails, HostRediscovery must not scan the
        // local /24 directly and bypass the configured route.
        val dao = daoWithStored()
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(dao, repo, responders = setOf("192.168.43.23"))
            .apply { localBases = { throw AssertionError("scan must not run for routed profile") } }

        assertNull(r.rediscover(profile.copy(proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080)))
        assertNull(r.rediscover(profile.copy(tunnelConfigId = "wg-1")))
        assertNull(r.rediscover(profile.copy(jumpProfileId = "jump-1")))
        coVerify(exactly = 0) { repo.updateHost(any(), any()) }
    }

    @Test
    fun `hostRediscoveryMode and setHostRediscoveryInOptions round-trip cleanly`() {
        assertEquals(HostRediscoveryMode.PERSIST, hostRediscoveryMode(""))
        assertEquals(HostRediscoveryMode.PERSIST, hostRediscoveryMode("# HavenHostRediscovery session"))
        assertEquals(HostRediscoveryMode.SESSION_ONLY, hostRediscoveryMode("HavenHostRediscovery session"))
        assertEquals(HostRediscoveryMode.SESSION_ONLY, hostRediscoveryMode("HavenFollowAddressChanges no"))
        assertEquals(HostRediscoveryMode.OFF, hostRediscoveryMode("HavenHostRediscovery=off"))

        val withSession = setHostRediscoveryInOptions("ServerAliveInterval 60", HostRediscoveryMode.SESSION_ONLY)
        assertEquals("ServerAliveInterval 60\nHavenHostRediscovery session", withSession)
        assertEquals(HostRediscoveryMode.SESSION_ONLY, hostRediscoveryMode(withSession))

        val backToPersist = setHostRediscoveryInOptions(withSession, HostRediscoveryMode.PERSIST)
        assertEquals("ServerAliveInterval 60", backToPersist)
        assertEquals(HostRediscoveryMode.PERSIST, hostRediscoveryMode(backToPersist))
    }
}
