package sh.haven.feature.rdp

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Render a Rust-side rustls failure message into something a human can act on.
 * The Rust layer (`rdp-kotlin/rust/src/lib.rs::diagnose_tls_error`) already
 * names the specific failure mode; we re-shape it for the end user and tack
 * on a hint about what to try server-side. (#109)
 */
private fun describeTlsFailure(msg: String): String {
    val classified = when {
        msg.contains("no shared TLS parameters", ignoreCase = true) ||
            msg.contains("PeerIncompatible", ignoreCase = true) -> buildString {
            append("TLS negotiation failed: no shared cipher / key-exchange / signature with the server.\n\n")
            append("Haven uses the rustls/ring TLS stack, which supports a narrower cipher set than ")
            append("Microsoft's Windows RDP client (SChannel) or OpenSSL. The most compatible server-side ")
            append("setting is ECDHE-RSA + AES-GCM over TLS 1.2 or 1.3.")
        }
        msg.contains("HandshakeFailure", ignoreCase = true) -> buildString {
            append("TLS handshake failed: server rejected our cipher list.\n\n")
            append("This usually means the server has no cipher suite in common with Haven. ")
            append("Enable ECDHE-RSA + AES-GCM (TLS 1.2 or 1.3) on the server, or check the server's RDP TLS configuration.")
        }
        msg.contains("certificate problem", ignoreCase = true) ||
            msg.contains("InvalidCertificate", ignoreCase = true) -> buildString {
            append("TLS handshake failed: server certificate problem.\n\n")
            append("Detail: $msg")
        }
        msg.contains("ALPN", ignoreCase = true) -> buildString {
            append("TLS negotiation failed: server requires an application protocol Haven doesn't advertise.\n\n")
            append("Detail: $msg")
        }
        else -> "TLS negotiation failed.\n\nDetail: $msg"
    }
    return classified + "\n\nIf this persists please file an issue at https://github.com/GlassHaven/Haven/issues with the message above."
}

/** Map RDP/network exceptions to user-friendly messages. */
fun describeRdpError(e: Exception, host: String? = null, port: Int? = null): String {
    val portStr = port?.toString() ?: "3389"
    val hostStr = host ?: "the remote host"
    return when (e) {
        is ConnectException -> buildString {
            append("Connection refused")
            if (e.message?.contains("refused", ignoreCase = true) == true) {
                append(". No RDP server appears to be listening on $hostStr:$portStr.\n\n")
                append("Check:\n")
                append("  - Remote Desktop is enabled on the target machine\n")
                append("  - Linux: xrdp is installed and running\n")
                append("  - Port $portStr is not blocked by a firewall\n")
                append("  - Verify: ss -tlnp | grep $portStr")
            }
        }
        is SocketTimeoutException -> buildString {
            append("Connection timed out reaching $hostStr:$portStr.\n\n")
            append("Check:\n")
            append("  - Host address is correct\n")
            append("  - Port $portStr is not blocked by a firewall\n")
            append("  - If tunneling through SSH, the SSH session is still connected")
        }
        is UnknownHostException ->
            "Could not resolve hostname \"$hostStr\". Check the address is correct."
        is NoRouteToHostException ->
            "No route to $hostStr. Check your network connection and that the host is reachable."
        else -> {
            val msg = e.message ?: "Unknown error"
            when {
                // CredSSP MessageAltered = pub_key_auth hash mismatch.
                // Most often caused by gnome-remote-desktop /
                // FreeRDP-server interop bugs. The fix is server-side
                // (uncheck NLA on the connection profile).
                msg.contains("MessageAltered", ignoreCase = true) ||
                    msg.contains("public-key hash", ignoreCase = true) -> buildString {
                    append("Authentication failed: server rejected the CredSSP public-key hash.\n\n")
                    append("This is usually a server-side interop bug — Linux gnome-remote-desktop ")
                    append("and certain xrdp builds compute the hash differently than Haven's ironrdp/sspi-rs.\n\n")
                    append("Workaround: edit this connection profile and uncheck \"Network Level Authentication (NLA)\". ")
                    append("Without NLA, RDP authenticates after channel setup instead — slower but unaffected by this mismatch.\n\n")
                    append("Detail: $msg")
                }
                msg.contains("LogonDenied", ignoreCase = true) ||
                    msg.contains("wrong username", ignoreCase = true) -> buildString {
                    append("Authentication failed: wrong username or password.\n\n")
                    append("For Linux/xrdp the credentials should match a system account; ")
                    append("Domain can usually be left empty.\n\nDetail: $msg")
                }
                msg.contains("TimeSkew", ignoreCase = true) -> buildString {
                    append("Authentication failed: clock skew with server is too large.\n\n")
                    append("Check that the device's clock is correct (Settings → Date & time → Set automatically).\n\n")
                    append("Detail: $msg")
                }
                msg.contains("(CredSSP)", ignoreCase = true) ||
                    msg.contains("Credssp", ignoreCase = true) -> buildString {
                    append("Authentication failed during CredSSP/NLA.\n\n")
                    append("Detail: $msg\n\n")
                    append("If this is a Linux server, try unchecking \"Network Level Authentication (NLA)\" in the connection profile.")
                }
                msg.contains("Authentication", ignoreCase = true) -> buildString {
                    append("Authentication failed.\n\n")
                    append("Check your username and password.\n")
                    append("For xrdp, the username/password should match a system account.\n")
                    append("Domain can usually be left empty for Linux/xrdp connections.\n\n")
                    append("Detail: $msg")
                }
                msg.contains("TLS", ignoreCase = true) || msg.contains("SSL", ignoreCase = true) ->
                    describeTlsFailure(msg)
                msg.contains("RDP security negotiation", ignoreCase = true) -> buildString {
                    append("RDP security negotiation failed.\n\n")
                    append("Detail: $msg")
                }
                else -> msg
            }
        }
    }
}
