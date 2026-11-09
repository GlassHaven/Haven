package sh.haven.core.vnc.protocol

import android.util.Log
import sh.haven.core.vnc.VncSession
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "VncHandshaker"

// --- RFB security types ---
private const val SEC_NONE = 1
private const val SEC_VNC_AUTH = 2
private const val SEC_VENCRYPT = 19
private const val SEC_ARD = 30

// --- VeNCrypt sub-types ---
// 256 = Plain (plain auth, no TLS — rarely used)
// 257 = TLSNone  (TLS tunnel, no auth)
// 258 = TLSVnc   (TLS tunnel, VNC DES auth)
// 259 = TLSPlain (TLS tunnel, plain username/password) ← primary target for wayvnc
// 260 = X509None
// 261 = X509Vnc
// 262 = X509Plain (X509 cert TLS, plain auth)
private const val VENCRYPT_PLAIN = 256
private const val VENCRYPT_TLS_NONE = 257
private const val VENCRYPT_TLS_VNC = 258
private const val VENCRYPT_TLS_PLAIN = 259
private const val VENCRYPT_X509_NONE = 260
private const val VENCRYPT_X509_VNC = 261
private const val VENCRYPT_X509_PLAIN = 262

/**
 * Performs the VNC handshake: protocol version, security type, authentication.
 *
 * Supported security types:
 *   - 1 (None)
 *   - 2 (VncAuth — DES challenge/response, password only, 8-char max)
 *   - 19 (VeNCrypt) with sub-types Plain, TLSNone, TLSVnc, TLSPlain,
 *                                 X509None, X509Vnc, X509Plain
 *   - 30 (Apple Remote Desktop — macOS Screen Sharing account login,
 *         chosen when a username is configured; see [ArdAuth])
 *
 * For VeNCrypt TLS variants, the socket is upgraded to an SSLSocket after
 * the sub-type is selected; subsequent RFB messages (including ServerInit
 * and framebuffer updates) flow over TLS.
 */
object Handshaker {

    fun handshake(session: VncSession, socket: Socket, host: String) {
        negotiateProtocolVersion(session)
        negotiateSecurityType(session, socket, host)
    }

    /** Security type None: neither encrypted nor authenticated (finding #11). */
    private fun warnUnauthenticated(session: VncSession) {
        Log.w(TAG, "VNC security type None: connection is unencrypted and unauthenticated")
        session.config.onSecurityWarning?.invoke(
            "This VNC connection is unencrypted and unauthenticated (security type None).",
        )
    }

    private fun negotiateProtocolVersion(session: VncSession) {
        val serverVersion = ProtocolVersion.decode(session.inputStream)
        if (serverVersion.major < 3 || (serverVersion.major == 3 && serverVersion.minor < 3)) {
            throw HandshakingFailedException("Server version too old: $serverVersion")
        }
        val clientVersion = ProtocolVersion(
            major = minOf(serverVersion.major, 3),
            minor = minOf(serverVersion.minor, 8),
        )
        clientVersion.encode(session.outputStream)
        session.protocolVersion = clientVersion
    }

    private fun negotiateSecurityType(session: VncSession, socket: Socket, host: String) {
        val version = session.protocolVersion!!
        val d = DataInputStream(session.inputStream)

        if (version.atLeast(3, 7)) {
            // 3.7+: server sends list of supported types
            val count = d.readUnsignedByte()
            if (count == 0) {
                val errLen = d.readInt()
                val errBytes = ByteArray(errLen)
                d.readFully(errBytes)
                throw HandshakingFailedException(String(errBytes, Charsets.US_ASCII))
            }
            val types = (0 until count).map { d.readUnsignedByte() }
            Log.d(TAG, "Server security types: $types")

            // Preference order: VeNCrypt > VncAuth > None
            // Prefer VeNCrypt only when the user supplied a username (otherwise
            // plain VncAuth password-only is what most users expect).
            val hasUsername = session.config.usernameSupplier?.invoke()?.isNotEmpty() == true
            when {
                SEC_VENCRYPT in types && hasUsername -> {
                    session.outputStream.write(SEC_VENCRYPT)
                    session.outputStream.flush()
                    negotiateVeNCrypt(session, socket, host)
                }
                // macOS Screen Sharing: with a username, log into that macOS
                // account so the server shares the user's console session.
                // Password-only VncAuth is tied to no account, and current
                // macOS parks it on a separate, never-refreshing login window.
                SEC_ARD in types && hasUsername -> {
                    session.outputStream.write(SEC_ARD)
                    session.outputStream.flush()
                    authenticateArd(session)
                }
                SEC_VNC_AUTH in types -> {
                    session.outputStream.write(SEC_VNC_AUTH)
                    session.outputStream.flush()
                    authenticateVnc(session)
                }
                SEC_VENCRYPT in types -> {
                    // No username, but VeNCrypt is the only option with a
                    // password path (via TLSPlain). Use it with empty username.
                    session.outputStream.write(SEC_VENCRYPT)
                    session.outputStream.flush()
                    negotiateVeNCrypt(session, socket, host)
                }
                SEC_NONE in types -> {
                    session.outputStream.write(SEC_NONE)
                    session.outputStream.flush()
                    warnUnauthenticated(session)
                }
                else -> throw HandshakingFailedException("No supported security types: $types")
            }

            if (version.atLeast(3, 8)) {
                readSecurityResult(session)
            }
        } else {
            // 3.3: server selects a single type
            val type = d.readInt()
            when (type) {
                0 -> {
                    val errLen = d.readInt()
                    val errBytes = ByteArray(errLen)
                    d.readFully(errBytes)
                    throw HandshakingFailedException(String(errBytes, Charsets.US_ASCII))
                }
                SEC_NONE -> warnUnauthenticated(session)
                SEC_VNC_AUTH -> authenticateVnc(session)
                else -> throw HandshakingFailedException("Unsupported security type: $type")
            }
        }
    }

    // --- VeNCrypt (type 19) ---

    private fun negotiateVeNCrypt(session: VncSession, socket: Socket, host: String) {
        val d = DataInputStream(session.inputStream)
        val o = DataOutputStream(session.outputStream)

        // Server sends its max VeNCrypt version as [major, minor]
        val serverMajor = d.readUnsignedByte()
        val serverMinor = d.readUnsignedByte()
        Log.d(TAG, "Server VeNCrypt version: $serverMajor.$serverMinor")

        // We speak VeNCrypt 0.2 — clamp if server is newer
        val clientMajor = 0
        val clientMinor = if (serverMajor == 0 && serverMinor < 2) serverMinor else 2
        o.writeByte(clientMajor)
        o.writeByte(clientMinor)
        o.flush()

        if (clientMajor == 0 && clientMinor < 2) {
            throw HandshakingFailedException("VeNCrypt version too old: $clientMajor.$clientMinor")
        }

        // Server ACK: 0 = accepted, non-zero = refused
        val ack = d.readUnsignedByte()
        if (ack != 0) throw HandshakingFailedException("VeNCrypt version negotiation refused")

        // Server sends list of sub-types (count: 1 byte, then count * 4-byte ints)
        val subCount = d.readUnsignedByte()
        if (subCount == 0) throw HandshakingFailedException("VeNCrypt server offered no sub-types")
        val subTypes = (0 until subCount).map { d.readInt() }
        Log.d(TAG, "VeNCrypt sub-types: $subTypes")

        // Pick the best supported sub-type. Prefer the X509 family over the
        // anonymous-DH (plain TLS) family: X509 carries a server certificate
        // we can pin (trust-on-first-use), whereas anonymous-DH TLS is
        // encrypted but unauthenticated and cannot be verified. Within each
        // family prefer Plain (user/pass) > Vnc (DES) > None. (security-review
        // critical #1)
        //   X509Plain > X509Vnc > X509None > TLSPlain > TLSVnc > TLSNone > Plain
        val chosen = when {
            VENCRYPT_X509_PLAIN in subTypes -> VENCRYPT_X509_PLAIN
            VENCRYPT_X509_VNC in subTypes -> VENCRYPT_X509_VNC
            VENCRYPT_X509_NONE in subTypes -> VENCRYPT_X509_NONE
            VENCRYPT_TLS_PLAIN in subTypes -> VENCRYPT_TLS_PLAIN
            VENCRYPT_TLS_VNC in subTypes -> VENCRYPT_TLS_VNC
            VENCRYPT_TLS_NONE in subTypes -> VENCRYPT_TLS_NONE
            VENCRYPT_PLAIN in subTypes -> VENCRYPT_PLAIN
            else -> throw HandshakingFailedException(
                "No supported VeNCrypt sub-type (offered: $subTypes)",
            )
        }
        Log.d(TAG, "Chose VeNCrypt sub-type $chosen")
        o.writeInt(chosen)
        o.flush()

        // Sub-types that require a TLS upgrade. X509 variants carry a server
        // certificate we pin; the anonymous-DH (plain TLS) variants do not.
        val isX509 = chosen in listOf(
            VENCRYPT_X509_NONE, VENCRYPT_X509_VNC, VENCRYPT_X509_PLAIN,
        )
        val isAnonTls = chosen in listOf(
            VENCRYPT_TLS_NONE, VENCRYPT_TLS_VNC, VENCRYPT_TLS_PLAIN,
        )
        if (isX509 || isAnonTls) {
            // For TLS-family sub-types, the server sends an extra ack byte
            // (0 = accepted) before the TLS handshake begins.
            val tlsAck = d.readUnsignedByte()
            if (tlsAck != 1) throw HandshakingFailedException("VeNCrypt sub-type refused: ack=$tlsAck")
            upgradeToTls(session, socket, host, requireCert = isX509)
            if (isAnonTls) {
                Log.w(TAG, "VeNCrypt anonymous-DH TLS: encrypted but server identity is unverified")
                session.config.onSecurityWarning?.invoke(
                    "This VNC server uses anonymous TLS: the connection is encrypted " +
                        "but the server's identity cannot be verified.",
                )
            }
        }

        // Sub-type-specific auth now runs over the (possibly) TLS-wrapped streams
        when (chosen) {
            VENCRYPT_TLS_NONE, VENCRYPT_X509_NONE -> {
                // no further auth
            }
            VENCRYPT_PLAIN, VENCRYPT_TLS_PLAIN, VENCRYPT_X509_PLAIN -> {
                authenticatePlain(session)
            }
            VENCRYPT_TLS_VNC, VENCRYPT_X509_VNC -> {
                authenticateVnc(session)
            }
        }
    }

    /**
     * Upgrade the socket to TLS.
     *
     * For X509 sub-types ([requireCert] = true) the server presents a real
     * certificate: we use regular (non-anonymous) cipher suites and hand the
     * leaf cert to [VncConfig.verifyServerCert] for trust-on-first-use pinning.
     * That callback throws to abort if the cert is untrusted/changed — closing
     * the "accepts any server certificate" MITM hole (critical #1).
     *
     * For anonymous-DH sub-types ([requireCert] = false) there is no
     * certificate to verify; we enable ADH suites and the connection is
     * encrypted but unauthenticated (the caller surfaces a warning).
     */
    private fun upgradeToTls(session: VncSession, socket: Socket, host: String, requireCert: Boolean) {
        Log.d(TAG, "Upgrading socket to TLS (requireCert=$requireCert)")
        // Anonymous-DH suites have no certificate, so a TrustManager never
        // sees one. For X509 we still install an accept-all TrustManager here
        // and enforce trust ourselves against the pinned fingerprint after the
        // handshake (a chain-of-trust check would reject the self-signed certs
        // that VNC servers overwhelmingly use; TOFU pinning is the right model).
        val ctx = SSLContext.getInstance("TLS")
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        ctx.init(null, trustAll, java.security.SecureRandom())

        val factory: SSLSocketFactory = ctx.socketFactory
        // Wrap the existing socket. autoClose=true so closing the SSLSocket
        // closes the underlying plain socket too.
        val sslSocket = factory.createSocket(socket, host, socket.port, true) as SSLSocket
        sslSocket.useClientMode = true

        if (!requireCert) {
            // VeNCrypt TLSxxx allows anonymous DH cipher suites. Enable both
            // ADH and regular TLS suites so we negotiate with whatever the
            // server supports.
            try {
                val anonSuites = sslSocket.supportedCipherSuites.filter {
                    it.contains("anon", ignoreCase = true)
                }
                val regularSuites = sslSocket.enabledCipherSuites.toList()
                val combined = (anonSuites + regularSuites).distinct().toTypedArray()
                if (combined.isNotEmpty()) {
                    sslSocket.enabledCipherSuites = combined
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable anon cipher suites: ${e.message}")
            }
        }
        // X509 sub-types keep the default (non-anonymous) suites so the server
        // is forced to present a certificate.

        sslSocket.startHandshake()
        Log.d(TAG, "TLS handshake complete: ${sslSocket.session.protocol} ${sslSocket.session.cipherSuite}")

        if (requireCert) {
            val leaf = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw HandshakingFailedException(
                    "VeNCrypt X509: server presented no certificate",
                )
            val verifier = session.config.verifyServerCert
            if (verifier != null) {
                // Throws to abort on an untrusted / changed certificate.
                verifier.invoke(leaf)
            } else {
                Log.w(TAG, "No certificate verifier configured — X509 cert left unvalidated")
            }
        }

        // Swap the session's streams for the TLS-wrapped ones
        session.inputStream = BufferedInputStream(sslSocket.inputStream)
        session.outputStream = sslSocket.outputStream
    }

    /**
     * VeNCrypt Plain auth (and the Plain half of TLSPlain / X509Plain).
     * Message format: [ulen: 4-byte BE][plen: 4-byte BE][username][password]
     * Both strings are UTF-8, no null terminator.
     */
    private fun authenticatePlain(session: VncSession) {
        val username = session.config.usernameSupplier?.invoke().orEmpty()
        val password = session.config.passwordSupplier?.invoke().orEmpty()
        val uBytes = username.toByteArray(Charsets.UTF_8)
        val pBytes = password.toByteArray(Charsets.UTF_8)

        val o = DataOutputStream(session.outputStream)
        o.writeInt(uBytes.size)
        o.writeInt(pBytes.size)
        o.write(uBytes)
        o.write(pBytes)
        o.flush()
        // Zero the password copy we made
        pBytes.fill(0)
    }

    // --- Apple Remote Desktop (type 30) ---

    private fun authenticateArd(session: VncSession) {
        val username = session.config.usernameSupplier?.invoke().orEmpty()
        val password = session.config.passwordSupplier?.invoke()
            ?: throw AuthenticationFailedException("Password required")
        val params = ArdAuth.readParams(DataInputStream(session.inputStream))
        session.outputStream.write(ArdAuth.response(params, username, password))
        session.outputStream.flush()
        // SecurityResult is read by the caller (type 30 implies RFB 3.8).
    }

    // --- Classic VncAuth (type 2) ---

    private fun authenticateVnc(session: VncSession) {
        val d = DataInputStream(session.inputStream)
        val challenge = ByteArray(16)
        d.readFully(challenge)

        val password = session.config.passwordSupplier?.invoke()
            ?: throw AuthenticationFailedException("Password required")

        val keyBytes = ByteArray(8)
        val pwBytes = password.toByteArray(Charsets.US_ASCII)
        System.arraycopy(pwBytes, 0, keyBytes, 0, minOf(pwBytes.size, 8))
        pwBytes.fill(0) // zero plaintext password bytes

        // VNC reverses bits in each byte of the key
        for (i in keyBytes.indices) {
            keyBytes[i] = reverseBits(keyBytes[i])
        }

        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
        keyBytes.fill(0) // zero DES key material
        val response = cipher.doFinal(challenge)

        session.outputStream.write(response)
        session.outputStream.flush()

        if (!session.protocolVersion!!.atLeast(3, 8)) {
            readSecurityResult(session)
        }
    }

    private fun readSecurityResult(session: VncSession) {
        val d = DataInputStream(session.inputStream)
        val result = d.readInt()
        if (result != 0) {
            val errMsg = if (session.protocolVersion!!.atLeast(3, 8)) {
                val len = d.readInt()
                val bytes = ByteArray(len)
                d.readFully(bytes)
                String(bytes, Charsets.US_ASCII)
            } else {
                "Authentication failed"
            }
            throw AuthenticationFailedException(errMsg)
        }
    }

    private fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xFF
        var result = 0
        for (i in 0 until 8) {
            result = (result shl 1) or (v and 1)
            v = v shr 1
        }
        return result.toByte()
    }
}
