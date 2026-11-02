package sh.haven.core.tunnel

import android.util.Log
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS4
import com.jcraft.jsch.ProxySOCKS5
import sh.haven.core.redact.LogRedact
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.HavenProxy
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * Single dispatch point for "how does this connection profile reach the
 * network?". Replaces ad-hoc `if (profile.tunnelConfigId != null) ...`
 * checks scattered across per-transport viewmodels.
 *
 * Three surfaces, each picked by the consumer based on what its transport
 * library can accept:
 *
 *  - [dial] — direct in-process [TunneledConnection]. Simplest path; for
 *    transports that own their socket creation in Kotlin (VNC, Reticulum).
 *  - [socketFactory] — javax.net.SocketFactory wrapping the same tunnel.
 *    For transports that take a factory (smbj's SmbConfig).
 *  - [socksEndpoint] — localhost SOCKS5 listener address. For transports
 *    behind FFI (rclone gomobile, IronRDP Rust) that can talk SOCKS5 but
 *    can't have their socket creation intercepted from Kotlin. Currently
 *    returns null; wired up when [Tunnel.socksAddress] (and the matching
 *    Go / tsnet listener) lands.
 *
 * All three return null when the profile has no tunnel selected — the
 * caller falls through to dialling a real Socket directly.
 *
 * A fourth surface, [havenProxy], is for SSH consumers. It wraps the
 * tunnel as an opaque [HavenProxy] (over JSch's `Proxy` for now) and
 * *also* honours the legacy `proxyType`/`proxyHost`/`proxyPort` columns
 * on [ConnectionProfile] (SOCKS5 / SOCKS4 / HTTP). [dial] and
 * [socketFactory] don't yet honour
 * the SOCKS columns; that will land alongside non-SSH transport adoption
 * (step 7 of #149).
 */
@Singleton
class TunnelResolver @Inject constructor(
    private val tunnelManager: TunnelManager,
) {

    private companion object {
        const val TAG = "TunnelResolver"
    }

    suspend fun dial(
        profile: ConnectionProfile,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): TunneledConnection? {
        tunnelFor(profile)?.let { return it.dial(host, port, timeoutMs) }
        if (profile.tunnelConfigId != null) {
            Log.i(TAG, "tunnel ${profile.tunnelConfigId} unavailable — dialling ${LogRedact.host(host, port)} directly (rides any system VPN routing)")
            // Expected case: the tunnel bring-up was refused because the
            // config's address is held by an active system VPN (same config
            // in a VPN app). Direct dial is then the RIGHT path: the system
            // VPN routes the destination. Other dial failures surface as
            // their own errors without this extra attempt.
        }
        // Fall through to legacy SOCKS / HTTP proxy if configured.
        proxySocketFactoryFor(profile, timeoutMs)?.let { factory ->
            val socket = factory.createSocket(host, port)
            return SocketTunneledConnection(socket)
        }
        return null
    }

    suspend fun socketFactory(profile: ConnectionProfile): SocketFactory? {
        tunnelFor(profile)?.let { return TunnelSocketFactory(it) }
        return proxySocketFactoryFor(profile)
    }

    /**
     * Resolve a UDP transport for [profile]. Returns a
     * [TunneledDatagramSocket] when the profile selects a tunnel that
     * can carry UDP (WireGuard, Tailscale); returns null when no tunnel
     * is selected or the selected backend can't carry UDP (Cloudflare
     * Access, legacy SOCKS/HTTP proxies — UDP ASSOCIATE is not
     * supported here). Callers fall through to a plain
     * [java.net.DatagramSocket] on null.
     *
     * Acquires the tunnel via [tunnelManager]; pair with [release] on
     * the [ConnectionProfile.id] when the UDP socket is closed, same
     * lifecycle contract as [dial].
     *
     * One-shot use. For callers that need to mint a fresh tunneled
     * socket on every Mosh roaming-rebind, see [udpSocketSupplier].
     */
    suspend fun dialUdp(profile: ConnectionProfile): TunneledDatagramSocket? {
        val tunnel = tunnelFor(profile) ?: return null
        return tunnel.listenUdp()
    }

    /**
     * Pre-acquire the tunnel and return a non-suspend factory that
     * mints a fresh [TunneledDatagramSocket] each call. Mirrors
     * [socketDialer] for UDP. Mosh's rebind path calls the factory
     * each time the upstream socket goes stale; the underlying tunnel
     * handle is reused across rebinds.
     *
     * Returns null when the profile has no tunnel or the tunnel can't
     * carry UDP — caller falls through to a raw
     * [java.net.DatagramSocket] (same behaviour as today's Mosh).
     *
     * The factory throws [java.io.IOException] if the tunnel was torn
     * down between acquisition and a rebind attempt (e.g. user toggled
     * the tunnel off mid-session). [MoshConnection.rebindSocket] swallows
     * that failure and keeps the current socket, so the send loop survives
     * and the next stall cycle retries once the tunnel is back (#421).
     */
    suspend fun udpSocketSupplier(
        profile: ConnectionProfile,
    ): (() -> TunneledDatagramSocket)? {
        val tunnel = tunnelFor(profile) ?: return null
        return {
            tunnel.listenUdp() ?: throw java.io.IOException(
                "Tunnel ${profile.tunnelConfigId} does not support UDP",
            )
        }
    }

    suspend fun socksEndpoint(profile: ConnectionProfile): InetSocketAddress? {
        val tunnel = tunnelFor(profile) ?: return null
        return tunnel.socksAddress()
    }

    /**
     * Proxy / tunnel chain for a profile, as an opaque [HavenProxy] that
     * `SshClient` consumes. WireGuard / Tailscale tunnel takes precedence
     * over the legacy `proxyType` columns; both are mutually exclusive at
     * the UI level. Returns null for direct dialling.
     *
     * Does **not** handle SSH jump-host — that needs a live SSH session,
     * so callers resolve it before delegating here.
     */
    suspend fun havenProxy(profile: ConnectionProfile): HavenProxy? =
        jschProxy(profile)?.let(::HavenProxy)

    /**
     * Test-only accessor returning the underlying JSch `Proxy` so unit tests
     * can assert the proxy variant (`TunnelProxy` / `ProxySOCKS5` / etc.).
     * Production code uses [havenProxy] instead.
     */
    internal suspend fun jschProxy(profile: ConnectionProfile): com.jcraft.jsch.Proxy? {
        tunnelFor(profile)?.let { return TunnelProxy(it) }
        val (proxyHost, proxyPort) = normalizeProxyEndpoint(
            rawHost = profile.proxyHost,
            rawPort = profile.proxyPort,
            proxyType = profile.proxyType,
        ) ?: return null
        // #227: when the proxy requires authentication, JSch throws
        // JSchProxyException("…username/password authentication requested by
        // server with no username/password configured") unless we call
        // setUserPasswd before the proxy handshake runs. SOCKS4 only carries
        // a userid (password ignored); SOCKS5 uses RFC 1929 user/pass; HTTP
        // uses Proxy-Authorization: Basic.
        val user = profile.proxyUser?.takeIf { it.isNotEmpty() }
        val pass = profile.proxyPassword ?: ""
        return when (profile.proxyType) {
            // #227: an authenticated SOCKS5 proxy goes through ProxySocketFactory
            // (byte-correct RFC 1929) rather than JSch's ProxySOCKS5, whose
            // username/password length octets use the character count, corrupting
            // non-ASCII credentials. No-auth SOCKS5 keeps JSch's proxy unchanged.
            "SOCKS5" ->
                if (user != null) {
                    AuthenticatedProxy(
                        ProxySocketFactory(
                            proxyType = "SOCKS5",
                            proxyHost = proxyHost,
                            proxyPort = proxyPort,
                            proxyUser = user,
                            proxyPassword = pass,
                        ),
                    )
                } else {
                    ProxySOCKS5(proxyHost, proxyPort)
                }
            // SOCKS4 sends a NUL-terminated userid (no length octet → no
            // char/byte mismatch) and HTTP Basic base64s the raw UTF-8 bytes;
            // both are byte-correct in JSch, so they stay on JSch's proxies.
            "SOCKS4" -> ProxySOCKS4(proxyHost, proxyPort).apply {
                if (user != null) setUserPasswd(user, pass)
            }
            "HTTP" -> ProxyHTTP(proxyHost, proxyPort).apply {
                if (user != null) setUserPasswd(user, pass)
            }
            else -> null
        }
    }

    /**
     * Release the tunnel acquired for [profileId]. Pair with any prior
     * call that returned a non-null result from [dial], [socketFactory],
     * [socketDialer], or [havenProxy]. Idempotent — safe to call on
     * disconnect even if the profile never acquired anything.
     */
    suspend fun release(profileId: String) {
        tunnelManager.release(profileId)
    }

    /**
     * Returns a non-suspend dialer closure that the caller invokes once
     * per TCP connection — useful for libraries that own their socket
     * lifecycle and dial outside a coroutine context (reticulum-kt's
     * `TCPClientInterface`, for example). The tunnel is acquired
     * up-front; subsequent dials reuse the same handle.
     *
     * Returns null when the profile has neither a tunnel nor a proxy
     * configured — caller falls through to its default `Socket()` dial.
     */
    suspend fun socketDialer(
        profile: ConnectionProfile,
    ): ((String, Int, Int) -> java.net.Socket)? {
        tunnelFor(profile)?.let { tunnel ->
            return { host, port, timeoutMs ->
                val conn = tunnel.dial(host, port, timeoutMs)
                TunneledSocket(conn, host, port)
            }
        }
        proxySocketFactoryFor(profile)?.let { factory ->
            return { host, port, _ ->
                factory.createSocket(host, port)
            }
        }
        return null
    }

    private suspend fun tunnelFor(profile: ConnectionProfile): Tunnel? {
        val tunnelId = profile.tunnelConfigId ?: return null
        return try {
            tunnelManager.acquire(tunnelId, profile.id)
        } catch (e: WireGuardSystemVpnConflict) {
            Log.i(
                TAG,
                "WG tunnel $tunnelId refused: ${e.message} — falling back to direct dial",
            )
            null
        }
    }

    private fun proxySocketFactoryFor(
        profile: ConnectionProfile,
        timeoutMs: Int = 30_000,
    ): ProxySocketFactory? {
        val type = profile.proxyType ?: return null
        val (host, port) = normalizeProxyEndpoint(
            rawHost = profile.proxyHost,
            rawPort = profile.proxyPort,
            proxyType = type,
        ) ?: return null
        return ProxySocketFactory(
            proxyType = type,
            proxyHost = host,
            proxyPort = port,
            connectTimeoutMs = timeoutMs,
            proxyUser = profile.proxyUser?.takeIf { it.isNotEmpty() },
            proxyPassword = profile.proxyPassword,
        )
    }

    /**
     * Normalize [rawHost] / [rawPort] for a configured proxy (#777):
     *  - Trims whitespace and returns null if blank or [proxyType] is null.
     *  - Strips common proxy URL schemes (`socks5://`, `socks5h://`, `socks4://`,
     *    `socks4a://`, `socks://`, `http://`, `https://`) and any trailing path.
     *  - Splits inline `host:port` (or `[ipv6]:port`) so JSch's 2-argument
     *    `ProxySOCKS5(host, port)` / `ProxyHTTP(host, port)` receives a clean
     *    hostname and port instead of treating `host:port` as a hostname.
     */
    private fun normalizeProxyEndpoint(
        rawHost: String?,
        rawPort: Int,
        proxyType: String?,
    ): Pair<String, Int>? {
        if (proxyType.isNullOrBlank()) return null
        var host = rawHost?.trim().orEmpty()
        if (host.isEmpty()) return null
        val schemeIdx = host.indexOf("://")
        if (schemeIdx >= 0) {
            host = host.substring(schemeIdx + 3)
        }
        host = host.substringBefore('/').trim()
        if (host.isEmpty()) return null

        var port = rawPort
        if (host.startsWith("[")) {
            val closeBracket = host.indexOf(']')
            if (closeBracket > 0) {
                val remainder = host.substring(closeBracket + 1)
                host = host.substring(1, closeBracket)
                if (remainder.startsWith(":")) {
                    remainder.substring(1).toIntOrNull()?.takeIf { it in 1..65535 }?.let { port = it }
                }
            }
        } else if (host.count { it == ':' } == 1) {
            val h = host.substringBefore(':').trim()
            val p = host.substringAfter(':').trim().toIntOrNull()
            if (h.isNotEmpty() && p != null && p in 1..65535) {
                host = h
                port = p
            }
        }
        if (host.isEmpty()) return null
        if (port !in 1..65535) {
            port = if (proxyType.equals("HTTP", ignoreCase = true)) 8080 else 1080
        }
        return host to port
    }
}

/**
 * Adapts a [Socket] (typically returned by [ProxySocketFactory]) to the
 * [TunneledConnection] interface so callers of [TunnelResolver.dial]
 * see a uniform shape regardless of whether the routing layer is a
 * userspace tunnel or a JDK-style proxy.
 */
private class SocketTunneledConnection(
    private val socket: java.net.Socket,
) : TunneledConnection {
    override val inputStream: java.io.InputStream = socket.getInputStream()
    override val outputStream: java.io.OutputStream = socket.getOutputStream()
    override fun close() {
        try { socket.close() } catch (_: Throwable) { /* best-effort */ }
    }
}
