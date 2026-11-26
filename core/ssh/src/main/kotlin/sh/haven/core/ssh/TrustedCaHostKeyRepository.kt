package sh.haven.core.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * JSch [HostKeyRepository] combining `@cert-authority` host-certificate trust
 * (#133) with Haven's pre-authentication host-key gate.
 *
 * Installing this on a session with `StrictHostKeyChecking=yes` runs host-key
 * verification during KEX (`Session.checkHost`), strictly BEFORE JSch enters
 * `UserAuth`:
 *  1. When the server presents a `*-cert-v01@openssh.com` host key signed by
 *     one of [caPublicKeys] — valid signature, within validity window,
 *     matching principal, not revoked — `Session.checkHost` returns early
 *     before consulting [check], leaving `Session.hostKey` unset.
 *  2. Otherwise (plain host key, or an untrusted/invalid certificate falling
 *     back via `host_certificate_to_key_fallback`), `Session.doCheckHostKey`
 *     populates `Session.hostKey` and calls [check]. When [onHostKey] is
 *     supplied, [check] invokes it synchronously during KEX:
 *      - Normal return → [approvedByGate] = true and `OK` is returned so JSch
 *        proceeds to user authentication on the same handshake.
 *      - Any thrown exception → stored in [rejectionError] and `NOT_INCLUDED`
 *        is returned, causing JSch (`StrictHostKeyChecking=yes`) to abort KEX
 *        with `JSchUnknownHostKeyException` before sending any credentials.
 *
 * Read-only by design: [add]/[remove] are no-ops. Haven's known hosts live in
 * Room, not in this repository.
 */
class TrustedCaHostKeyRepository(
    caPublicKeys: List<String>,
    private val hostname: String? = null,
    private val port: Int = 22,
    private val sessionHostKeyProvider: (() -> HostKey?)? = null,
    private val onHostKey: ((KnownHostEntry) -> Unit)? = null,
) : HostKeyRepository {

    /**
     * True once JSch consulted [check] — i.e. the plain-key (TOFU) path ran
     * for this session, so the host was NOT accepted via a CA-signed cert.
     */
    @Volatile
    var checkConsulted: Boolean = false
        private set

    /** True when [onHostKey] ran during KEX and returned without throwing. */
    @Volatile
    var approvedByGate: Boolean = false
        private set

    /** Non-null when [onHostKey] rejected the host key during KEX. */
    @Volatile
    var rejectionError: Throwable? = null
        private set

    private val caEntries: List<HostKey> = caPublicKeys.mapNotNull(::parseCaLine)

    /** Number of CA keys that parsed successfully. */
    val caCount: Int get() = caEntries.size

    override fun check(host: String?, key: ByteArray?): Int {
        checkConsulted = true
        val gate = onHostKey ?: return HostKeyRepository.NOT_INCLUDED
        val hk = try {
            sessionHostKeyProvider?.invoke()
                ?: if (key != null) HostKey(host ?: hostname.orEmpty(), key) else null
        } catch (t: Throwable) {
            rejectionError = t
            return HostKeyRepository.NOT_INCLUDED
        }
        if (hk == null) {
            rejectionError = IllegalStateException("No host key available during KEX for ${hostname ?: host}:$port")
            return HostKeyRepository.NOT_INCLUDED
        }
        val entry = KnownHostEntry(
            hostname = hostname ?: host.orEmpty(),
            port = port,
            keyType = hk.type,
            publicKeyBase64 = hk.key,
        )
        return try {
            gate(entry)
            approvedByGate = true
            HostKeyRepository.OK
        } catch (t: Throwable) {
            rejectionError = t
            HostKeyRepository.NOT_INCLUDED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) { /* read-only */ }

    override fun remove(host: String?, type: String?) { /* read-only */ }

    override fun remove(host: String?, type: String?, key: ByteArray?) { /* read-only */ }

    override fun getKnownHostsRepositoryID(): String = "haven-trusted-host-cas"

    override fun getHostKey(): Array<HostKey> = caEntries.toTypedArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> =
        caEntries.filter { type == null || it.type == type }.toTypedArray()

    companion object {
        /**
         * Parse one stored CA public key — an OpenSSH public-key line
         * ("ssh-ed25519 AAAA… comment") or a bare base64 blob — into an
         * `@cert-authority` [HostKey] matching every host. Returns null for
         * unparseable input (a malformed config entry must not break
         * connects; it just contributes no trust).
         */
        private fun parseCaLine(line: String): HostKey? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split(Regex("\\s+"))
            // "ssh-ed25519 AAAA… [comment]" vs a bare base64 blob
            val typed = parts.size >= 2 &&
                (parts[0].startsWith("ssh-") || parts[0].startsWith("ecdsa-") || parts[0].startsWith("sk-"))
            val b64 = if (typed) parts[1] else parts[0]
            val comment = (if (typed) parts.drop(2) else parts.drop(1)).joinToString(" ")
            val blob = try {
                java.util.Base64.getDecoder().decode(b64)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return try {
                HostKey("@cert-authority", "*", HostKey.GUESS, blob, comment.ifEmpty { null })
            } catch (_: Exception) {
                null
            }
        }
    }
}
