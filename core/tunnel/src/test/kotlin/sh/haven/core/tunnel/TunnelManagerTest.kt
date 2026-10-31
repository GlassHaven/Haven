package sh.haven.core.tunnel

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.db.entities.typeEnum
import sh.haven.core.data.repository.TunnelConfigRepository
import java.io.IOException

/**
 * Unit tests for the manager's caching and refcount behaviour. Real
 * tunnel creation is behind [TunnelFactory]; these tests inject a counting
 * fake so they don't hit libgojni.so (not loadable in a plain JVM test).
 */
class TunnelManagerTest {

    @Test
    fun acquireReturnsNullWhenConfigMissing() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("missing") } returns null
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        assertNull(manager.acquire("missing", "p1"))
        assertEquals(0, factory.createCount)
    }

    @Test
    fun acquireCachesSameInstanceAcrossDependents() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-1") } returns wgConfig("cfg-1")
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val a = manager.acquire("cfg-1", "profile-A")
        val b = manager.acquire("cfg-1", "profile-B")

        assertNotNull(a)
        assertSame("two profiles on one config share one tunnel", a, b)
        assertEquals("factory called once even with two acquirers", 1, factory.createCount)
        assertEquals(2, manager.dependentCount("cfg-1"))
    }

    @Test
    fun reAcquireSameProfileSameConfigIsIdempotent() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-1") } returns wgConfig("cfg-1")
        val manager = TunnelManager(repo, CountingFactory())

        manager.acquire("cfg-1", "profile-A")
        manager.acquire("cfg-1", "profile-A")

        assertEquals("re-acquire by same profile leaves dep count at 1", 1, manager.dependentCount("cfg-1"))
    }

    @Test
    fun releaseWithRemainingDependentsKeepsTunnelOpen() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-1") } returns wgConfig("cfg-1")
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val first = manager.acquire("cfg-1", "p1") as FakeTunnel
        manager.acquire("cfg-1", "p2")
        manager.release("p1")

        assertFalse("tunnel must stay open while p2 still holds it", first.closed)
        assertEquals(1, manager.dependentCount("cfg-1"))

        // Re-acquire same config — still the same instance (factory not called again)
        val third = manager.acquire("cfg-1", "p3")
        assertSame(first, third)
        assertEquals(1, factory.createCount)
    }

    @Test
    fun releaseAfterLastDependentClosesTunnel() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-1") } returns wgConfig("cfg-1")
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val first = manager.acquire("cfg-1", "p1") as FakeTunnel
        manager.acquire("cfg-1", "p2")
        manager.release("p1")
        manager.release("p2")

        assertTrue("last release tears down the tunnel", first.closed)
        assertEquals(0, manager.dependentCount("cfg-1"))

        // Re-acquire creates a fresh instance
        val second = manager.acquire("cfg-1", "p3")
        assertNotSame(first, second)
        assertEquals(2, factory.createCount)
    }

    @Test
    fun releaseUnknownProfileIsNoOp() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        // Should not throw, should not call repo / factory
        manager.release("never-acquired")

        assertEquals(0, factory.createCount)
    }

    @Test
    fun switchingConfigsAutoReleasesPrior() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-A") } returns wgConfig("cfg-A")
        coEvery { repo.getById("cfg-B") } returns wgConfig("cfg-B")
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val a = manager.acquire("cfg-A", "p1") as FakeTunnel
        manager.acquire("cfg-B", "p1") // switch without explicit release

        assertTrue("cfg-A torn down on auto-release", a.closed)
        assertEquals(0, manager.dependentCount("cfg-A"))
        assertEquals(1, manager.dependentCount("cfg-B"))
    }

    @Test
    fun switchingConfigsKeepsPriorAliveIfOtherDependentsRemain() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-A") } returns wgConfig("cfg-A")
        coEvery { repo.getById("cfg-B") } returns wgConfig("cfg-B")
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val a = manager.acquire("cfg-A", "p1") as FakeTunnel
        manager.acquire("cfg-A", "p2")
        manager.acquire("cfg-B", "p1") // p1 switches, p2 still on cfg-A

        assertFalse("cfg-A must stay open for p2", a.closed)
        assertEquals(1, manager.dependentCount("cfg-A"))
        assertEquals(1, manager.dependentCount("cfg-B"))
    }

    @Test
    fun netbirdConfigReachesFactoryThroughManager() = runTest {
        // NETBIRD flows through the same acquire/cache/refcount path as the
        // older types; the factory decides the backend, so this stays off
        // libgojni.
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-nb") } returns nbConfig("cfg-nb")
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val tunnel = manager.acquire("cfg-nb", "p1")
        val again = manager.acquire("cfg-nb", "p2")

        assertNotNull(tunnel)
        assertSame(tunnel, again)
        assertEquals(1, factory.createCount)
        assertEquals(TunnelConfigType.NETBIRD, factory.lastConfig?.typeEnum)
        assertEquals(2, manager.dependentCount("cfg-nb"))
    }

    @Test
    fun defaultFactoryRejectsNetbirdConfigWithoutSetupKey() {
        // The parse guard in DefaultTunnelFactory runs before the native
        // start, so a config saved without a usable setup key fails fast
        // with an actionable message rather than a 60 s native failure.
        // The guard fires before Context use, so a mock Context is enough.
        val factory = DefaultTunnelFactory(mockk(), OkHttpClient(), TunnelAuthBroker(mockk()), mockk<SystemVpnAddressProbe>(relaxed = true))
        val ex = assertThrows(IOException::class.java) {
            factory.create(
                TunnelConfig(
                    id = "nb-broken",
                    label = "nb",
                    type = TunnelConfigType.NETBIRD.name,
                    configText = "garbage".toByteArray(),
                ),
            )
        }
        assertTrue(ex.message!!.contains("setup key"))
    }

    @Test
    fun anyconnectConfigReachesFactoryThroughManager() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-ac") } returns TunnelConfig(
            id = "cfg-ac",
            label = "corp vpn",
            type = TunnelConfigType.ANYCONNECT.name,
            configText = AnyConnectConfigBlob("vpn.example.com", "alice", "pw").encode(),
        )
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val tunnel = manager.acquire("cfg-ac", "p1")
        assertSame(tunnel, manager.acquire("cfg-ac", "p2"))
        assertEquals(1, factory.createCount)
        assertEquals(TunnelConfigType.ANYCONNECT, factory.lastConfig?.typeEnum)
    }

    @Test
    fun defaultFactoryRejectsAnyConnectConfigWithoutServer() {
        // Parse guard runs before the native start (and before Context use).
        val factory = DefaultTunnelFactory(mockk(), OkHttpClient(), TunnelAuthBroker(mockk()), mockk<SystemVpnAddressProbe>(relaxed = true))
        val ex = assertThrows(IOException::class.java) {
            factory.create(
                TunnelConfig(
                    id = "ac-broken",
                    label = "ac",
                    type = TunnelConfigType.ANYCONNECT.name,
                    configText = """{"username":"alice"}""".toByteArray(),
                ),
            )
        }
        assertTrue(ex.message!!.contains("server or username"))
    }

    @Test
    fun openvpnConfigReachesFactoryThroughManager() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-ovpn") } returns TunnelConfig(
            id = "cfg-ovpn",
            label = "office ovpn",
            type = TunnelConfigType.OPENVPN.name,
            configText = OpenVpnConfigBlob("client\ndev tun\nremote vpn.example.com 1194").encode(),
        )
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val tunnel = manager.acquire("cfg-ovpn", "p1")
        assertSame(tunnel, manager.acquire("cfg-ovpn", "p2"))
        assertEquals(1, factory.createCount)
        assertEquals(TunnelConfigType.OPENVPN, factory.lastConfig?.typeEnum)
        assertEquals(2, manager.dependentCount("cfg-ovpn"))
    }

    @Test
    fun defaultFactoryRejectsOpenVpnConfigWithoutProfileText() {
        val factory = DefaultTunnelFactory(mockk(), OkHttpClient(), TunnelAuthBroker(mockk()), mockk<SystemVpnAddressProbe>(relaxed = true))
        val ex = assertThrows(IOException::class.java) {
            factory.create(
                TunnelConfig(
                    id = "ovpn-broken",
                    label = "ovpn",
                    type = TunnelConfigType.OPENVPN.name,
                    configText = """{"ovpnConfigText":"   "}""".toByteArray(),
                ),
            )
        }
        assertTrue(ex.message!!.contains("no profile text"))
    }

    @Test
    fun deadTunnelIsEvictedAndRecreatedOnNextAcquire() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-ovpn") } returns TunnelConfig(
            id = "cfg-ovpn",
            label = "office ovpn",
            type = TunnelConfigType.OPENVPN.name,
            configText = OpenVpnConfigBlob("client\ndev tun\nremote vpn.example.com 1194").encode(),
        )
        val factory = CountingFactory()
        val manager = TunnelManager(repo, factory)

        val first = manager.acquire("cfg-ovpn", "p1") as FakeTunnel
        first.isDead = true

        val second = manager.acquire("cfg-ovpn", "p2") as FakeTunnel
        assertTrue("dead cached tunnel must be closed when evicted", first.closed)
        assertNotSame("dead cached tunnel must be replaced by a fresh instance", first, second)
        assertEquals(2, factory.createCount)
    }

    @Test
    fun lingeringTunnelSurvivesLastReleaseAndIsReused() = runTest {
        // "Stay connected": the AnyConnect-style tunnel outlives its last
        // connection, and the next acquire gets the same (still signed-in) one.
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-A") } returns wgConfig("cfg-A")
        val factory = CountingFactory(linger = true)
        val manager = TunnelManager(repo, factory)

        val first = manager.acquire("cfg-A", "p1") as FakeTunnel
        manager.release("p1")
        assertFalse("lingering tunnel must stay open", first.closed)
        assertEquals(setOf("cfg-A"), manager.live.value.keys)
        assertEquals(0, manager.dependentCount("cfg-A"))

        assertSame(first, manager.acquire("cfg-A", "p2"))
        assertEquals(1, factory.createCount)
    }

    @Test
    fun disconnectClosesEvenWithDependentsAndPublishes() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-A") } returns wgConfig("cfg-A")
        val factory = CountingFactory(linger = true)
        val manager = TunnelManager(repo, factory)

        val t = manager.acquire("cfg-A", "p1") as FakeTunnel
        assertTrue(manager.disconnect("cfg-A"))
        assertTrue(t.closed)
        assertTrue(manager.live.value.isEmpty())
        assertEquals(0, manager.dependentCount("cfg-A"))
        assertFalse("nothing left to disconnect", manager.disconnect("cfg-A"))

        // The profile's next acquire builds a fresh tunnel.
        manager.acquire("cfg-A", "p1")
        assertEquals(2, factory.createCount)
    }

    @Test
    fun nonLingeringTunnelStillClosesOnLastRelease() = runTest {
        val repo = mockk<TunnelConfigRepository>()
        coEvery { repo.getById("cfg-A") } returns wgConfig("cfg-A")
        val manager = TunnelManager(repo, CountingFactory(linger = false))
        val t = manager.acquire("cfg-A", "p1") as FakeTunnel
        assertEquals(setOf("cfg-A"), manager.live.value.keys)
        manager.release("p1")
        assertTrue(t.closed)
        assertTrue(manager.live.value.isEmpty())
    }

    private fun wgConfig(id: String) = TunnelConfig(
        id = id,
        label = "test",
        type = TunnelConfigType.WIREGUARD.name,
        configText = "[Interface]".toByteArray(),
    )

    private fun nbConfig(id: String) = TunnelConfig(
        id = id,
        label = "netbird test",
        type = TunnelConfigType.NETBIRD.name,
        configText = """{"setupKey":"NBSETUPKEY-123"}""".toByteArray(),
    )

    private class FakeTunnel(override val lingerAfterRelease: Boolean = false) : Tunnel {
        var closed = false
        override var isDead: Boolean = false
        override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection =
            throw UnsupportedOperationException("fake")

        override fun close() { closed = true }
    }

    private class CountingFactory(private val linger: Boolean = false) : TunnelFactory {
        var createCount = 0
        var lastConfig: TunnelConfig? = null
        override fun create(config: TunnelConfig): Tunnel {
            createCount++
            lastConfig = config
            return FakeTunnel(lingerAfterRelease = linger)
        }
    }
}
