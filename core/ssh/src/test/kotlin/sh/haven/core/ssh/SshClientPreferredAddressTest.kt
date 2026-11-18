package sh.haven.core.ssh

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Prefer-not-force address family (#855): the preferred family is tried first,
 * but an unreachable preferred address falls back to a reachable address of the
 * other family — unlike the hard IPV4_ONLY / IPV6_ONLY filters.
 */
class SshClientPreferredAddressTest {

    private val v4a = InetAddress.getByName("192.0.2.1")
    private val v4b = InetAddress.getByName("192.0.2.2")
    private val v6a = InetAddress.getByName("2001:db8::1")
    private val v6b = InetAddress.getByName("2001:db8::2")

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `ipv6 preferred picks reachable ipv6 ahead of ipv4 listed first`() {
        val picked = SshClient.selectPreferredAddress(listOf(v4a, v6a), preferIpv6 = true) { true }
        assertEquals(v6a, picked)
    }

    @Test
    fun `ipv6 preferred falls back to ipv4 when ipv6 unreachable`() {
        val picked = SshClient.selectPreferredAddress(listOf(v6a, v4a), preferIpv6 = true) { it == v4a }
        assertEquals(v4a, picked)
    }

    @Test
    fun `ipv4 preferred picks reachable ipv4 ahead of ipv6 listed first`() {
        val picked = SshClient.selectPreferredAddress(listOf(v6a, v4a), preferIpv6 = false) { true }
        assertEquals(v4a, picked)
    }

    @Test
    fun `ipv4 preferred falls back to ipv6 when ipv4 unreachable`() {
        val picked = SshClient.selectPreferredAddress(listOf(v4a, v6a), preferIpv6 = false) { it == v6a }
        assertEquals(v6a, picked)
    }

    @Test
    fun `preferred family preserves dns order within the family`() {
        val picked = SshClient.selectPreferredAddress(listOf(v4a, v6a, v6b), preferIpv6 = true) { true }
        assertEquals(v6a, picked)
    }

    @Test
    fun `no reachable address falls back to the preferred family first`() {
        val picked = SshClient.selectPreferredAddress(listOf(v4a, v6a), preferIpv6 = true) { false }
        assertEquals(v6a, picked)
    }
}