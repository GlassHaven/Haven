package sh.haven.core.tunnel

import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS4
import com.jcraft.jsch.ProxySOCKS5
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class TunnelResolverTest {

    @Test
    fun dialReturnsNullWhenProfileHasNoTunnelConfigId() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.dial(profile(tunnelConfigId = null), "h", 80, 30_000))
    }

    @Test
    fun dialReturnsNullWhenTunnelConfigDeleted() = runTest {
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("missing", any()) } returns null
        }
        val resolver = TunnelResolver(mgr)
        assertNull(resolver.dial(profile(tunnelConfigId = "missing"), "h", 80, 30_000))
    }

    @Test
    fun dialDelegatesToTunnelWhenConfigPresent() = runTest {
        val conn = stubConn()
        val tunnel = mockk<Tunnel> {
            every { dial("example.com", 443, 5_000) } returns conn
        }
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val result = resolver.dial(profile(tunnelConfigId = "tid"), "example.com", 443, 5_000)

        assertSame(conn, result)
    }

    @Test
    fun socketFactoryReturnsNullWhenNoTunnel() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.socketFactory(profile(tunnelConfigId = null)))
    }

    @Test
    fun socketFactoryReturnsTunnelSocketFactoryWhenConfigPresent() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val factory = resolver.socketFactory(profile(tunnelConfigId = "tid"))

        assertNotNull(factory)
        assertTrue(factory is TunnelSocketFactory)
    }

    @Test
    fun socksEndpointReturnsNullWhenProfileHasNoTunnel() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.socksEndpoint(profile(tunnelConfigId = null)))
    }

    @Test
    fun socksEndpointForwardsToTunnelSocksAddress() = runTest {
        val expected = java.net.InetSocketAddress("127.0.0.1", 41234)
        val tunnel = mockk<Tunnel> {
            every { socksAddress() } returns expected
        }
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val addr = resolver.socksEndpoint(profile(tunnelConfigId = "tid"))

        assertSame(expected, addr)
    }

    @Test
    fun socksEndpointReturnsNullWhenTunnelOptsOut() = runTest {
        val tunnel = mockk<Tunnel> {
            every { socksAddress() } returns null
        }
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)
        assertNull(resolver.socksEndpoint(profile(tunnelConfigId = "tid")))
    }

    @Test
    fun jschProxyReturnsTunnelProxyWhenConfigPresent() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val proxy = resolver.jschProxy(profile(tunnelConfigId = "tid"))

        assertNotNull(proxy)
        assertTrue(proxy is TunnelProxy)
    }

    @Test
    fun jschProxyReturnsSocks5WhenSet() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(profile(proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080))
        assertTrue(proxy is ProxySOCKS5)
    }

    @Test
    fun jschProxyReturnsSocks4WhenSet() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(profile(proxyType = "SOCKS4", proxyHost = "127.0.0.1", proxyPort = 1080))
        assertTrue(proxy is ProxySOCKS4)
    }

    @Test
    fun jschProxyReturnsHttpWhenSet() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(profile(proxyType = "HTTP", proxyHost = "127.0.0.1", proxyPort = 8080))
        assertTrue(proxy is ProxyHTTP)
    }

    @Test
    fun jschProxyTunnelTakesPrecedenceOverSocks() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)
        val p = profile(tunnelConfigId = "tid", proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080)

        val proxy = resolver.jschProxy(p)

        assertTrue("tunnel must take precedence over legacy SOCKS proxy", proxy is TunnelProxy)
    }

    @Test
    fun jschProxyReturnsNullForDirectProfile() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.jschProxy(profile()))
    }

    @Test
    fun jschProxyReturnsNullForUnknownProxyType() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.jschProxy(profile(proxyType = "WAT", proxyHost = "x", proxyPort = 1)))
    }

    // #227: proxy auth — setUserPasswd must be applied before the JSch
    // handshake, else the proxy throws JSchProxyException("…username/password
    // authentication requested by server with no username/password configured").

    private fun proxyField(proxy: Any, name: String): String? =
        proxy.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(proxy) as String?

    @Test
    fun jschProxySocks5WithCredsUsesAuthenticatedProxy() = runTest {
        // #227: an authenticated SOCKS5 proxy must bypass JSch's ProxySOCKS5
        // (whose non-ASCII RFC 1929 auth encoding is broken) and route through
        // the byte-correct ProxySocketFactory via AuthenticatedProxy.
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(
            profile(proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080,
                proxyUser = "alice", proxyPassword = "s3cr3t"),
        )
        assertTrue("auth'd SOCKS5 must use AuthenticatedProxy", proxy is AuthenticatedProxy)
    }

    @Test
    fun jschProxySocks5LeavesUserNullWhenNoCreds() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(profile(proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080))
        assertTrue(proxy is ProxySOCKS5)
        assertNull(proxyField(proxy!!, "user"))
    }

    @Test
    fun jschProxyHttpAppliesUserPasswdWhenCredsPresent() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(
            profile(proxyType = "HTTP", proxyHost = "127.0.0.1", proxyPort = 8080,
                proxyUser = "bob", proxyPassword = "pw"),
        )
        assertTrue(proxy is ProxyHTTP)
        assertEquals("bob", proxyField(proxy!!, "user"))
        assertEquals("pw", proxyField(proxy, "passwd"))
    }

    @Test
    fun jschProxySocks4AppliesUserid() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(
            profile(proxyType = "SOCKS4", proxyHost = "127.0.0.1", proxyPort = 1080,
                proxyUser = "carol", proxyPassword = ""),
        )
        assertTrue(proxy is ProxySOCKS4)
        assertEquals("carol", proxyField(proxy!!, "user"))
    }

    @Test
    fun jschProxyIgnoresBlankProxyUser() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(
            profile(proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080,
                proxyUser = "", proxyPassword = "x"),
        )
        assertTrue(proxy is ProxySOCKS5)
        assertNull("blank username must not enable proxy auth", proxyField(proxy!!, "user"))
    }

    @Test
    fun socketFactoryReturnsProxyFactoryWhenOnlyProxySet() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        val factory = resolver.socketFactory(
            profile(proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080),
        )
        assertNotNull(factory)
        assertTrue(factory is ProxySocketFactory)
    }

    @Test
    fun socketFactoryPrefersTunnelOverProxy() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val mgr = mockk<TunnelManager> {
            coEvery { acquire("tid", any()) } returns tunnel
        }
        val resolver = TunnelResolver(mgr)
        val p = profile(tunnelConfigId = "tid", proxyType = "SOCKS5", proxyHost = "127.0.0.1", proxyPort = 1080)

        val factory = resolver.socketFactory(p)

        assertTrue("tunnel must take precedence", factory is TunnelSocketFactory)
    }

    @Test
    fun socketFactoryReturnsNullWhenNeitherTunnelNorProxy() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.socketFactory(profile()))
    }

    @Test
    fun jschProxyReturnsNullWhenProxyHostIsBlank() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.jschProxy(profile(proxyType = "SOCKS5", proxyHost = "   ", proxyPort = 1080)))
    }

    @Test
    fun jschProxyNormalizesSchemeWhitespaceAndHostPort() = runTest {
        // #777: users paste "socks5://127.0.0.1:9050" or trailing spaces into
        // Proxy Host; JSch's 2-arg ProxySOCKS5(host, port) treats the raw string
        // as a hostname instead of stripping scheme / splitting port.
        val resolver = TunnelResolver(mockk(relaxed = true))
        val proxy = resolver.jschProxy(
            profile(proxyType = "SOCKS5", proxyHost = "  socks5://127.0.0.1:9050 ", proxyPort = 1080),
        )
        assertTrue(proxy is ProxySOCKS5)
        assertEquals("127.0.0.1", proxyField(proxy!!, "proxy_host"))
        val portField = proxy.javaClass.getDeclaredField("proxy_port").apply { isAccessible = true }.getInt(proxy)
        assertEquals(9050, portField)
    }

    @Test
    fun releaseForwardsToManager() = runTest {
        val mgr = mockk<TunnelManager>(relaxed = true)
        val resolver = TunnelResolver(mgr)

        resolver.release("profile-A")

        coVerify { mgr.release("profile-A") }
    }

    private fun profile(
        tunnelConfigId: String? = null,
        proxyType: String? = null,
        proxyHost: String? = null,
        proxyPort: Int = 0,
        proxyUser: String? = null,
        proxyPassword: String? = null,
    ): ConnectionProfile = ConnectionProfile(
        label = "test",
        host = "example.com",
        username = "user",
        tunnelConfigId = tunnelConfigId,
        proxyType = proxyType,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        proxyUser = proxyUser,
        proxyPassword = proxyPassword,
    )

    private fun stubConn(): TunneledConnection = object : TunneledConnection {
        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        override fun close() {}
    }
}
