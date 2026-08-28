package sh.haven.core.ssh

data class ConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.Password(""),
    val sshOptions: Map<String, String> = emptyMap(),
    /** Enable SSH agent forwarding (OpenSSH `ForwardAgent`). */
    val forwardAgent: Boolean = false,
    /** SSH exec request to send instead of opening an interactive shell. */
    val remoteCommand: String? = null,
    /** Whether the [remoteCommand] exec channel requests a terminal PTY. */
    val requestPty: Boolean = true,
    /**
     * Address-family preference (#137). [AddressFamily.AUTO] keeps the
     * dual-stack default; [AddressFamily.IPV4_ONLY] skips AAAA records
     * (broken-IPv6 networks); [AddressFamily.IPV6_ONLY] skips A records
     * (rarer, but real — broken-IPv4 paths exist too).
     */
    val addressFamily: AddressFamily = AddressFamily.AUTO,
    /**
     * Keys to expose via the forwarded agent channel. Only consulted when
     * [forwardAgent] is true. JSch's ChannelAgentForwarding silently skips
     * identities whose `isEncrypted()` returns true, so a passphrase-protected
     * key MUST carry its passphrase — JSch then decrypts it at addIdentity
     * time and the identity becomes forwardable (#377). Encrypted keys with
     * no available passphrase must be excluded by the caller.
     */
    val agentIdentities: List<AgentIdentity> = emptyList(),
    /**
     * Per-profile reconnect policy (#150). Defaults preserve the prior
     * always-on / 5-attempt-cap / honour-network-flip behaviour, so
     * existing call sites that don't supply a policy keep working.
     */
    val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) {
    init {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in 1..65535) { "Port must be 1-65535, got $port" }
        require(username.isNotBlank()) { "Username must not be blank" }
    }

    enum class AddressFamily { AUTO, IPV4_ONLY, IPV6_ONLY }

    /**
     * One key exposed over the forwarded agent channel.
     *
     * @param keyBytes For an unencrypted key: PEM the JSch parser accepts.
     *   For a passphrase-protected key: the ORIGINAL encrypted container
     *   (PEM/OpenSSH) exactly as stored — same contract as the auth path.
     * @param passphrase UTF-8 passphrase for an encrypted key; null for an
     *   unencrypted one. JSch decrypts at add time, so the plaintext key
     *   never round-trips through Haven's own memory.
     */
    data class AgentIdentity(
        val label: String,
        val keyBytes: ByteArray,
        val passphrase: ByteArray? = null,
        /**
         * #602: serialized SkKeyData handle for a FIDO2/SK key (the value
         * `SshKey.privateKeyBytes` holds for `sk-*` key types). Non-null means
         * [keyBytes] is NOT key material — the agent registers a hardware-
         * backed FidoIdentity instead of handing raw bytes to JSch, and each
         * signature from the remote prompts for a touch on the phone.
         */
        val skKeyData: ByteArray? = null,
        /** Optional CA certificate pairing the SK key (#133-style cert path). */
        val certBytes: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AgentIdentity) return false
            return label == other.label &&
                keyBytes.contentEquals(other.keyBytes) &&
                (passphrase ?: ByteArray(0)).contentEquals(other.passphrase ?: ByteArray(0)) &&
                (skKeyData ?: ByteArray(0)).contentEquals(other.skKeyData ?: ByteArray(0)) &&
                (certBytes ?: ByteArray(0)).contentEquals(other.certBytes ?: ByteArray(0))
        }

        override fun hashCode(): Int {
            var result = label.hashCode()
            result = 31 * result + keyBytes.contentHashCode()
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            result = 31 * result + (skKeyData?.contentHashCode() ?: 0)
            result = 31 * result + (certBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * @param autoReconnect Whether to fire the backoff loop at all when
     *   a transport drops. False = the session goes straight to
     *   DISCONNECTED.
     * @param maxAttempts Cap on the backoff loop. 0 means unlimited
     *   (useful for tunnel-only profiles holding port forwards).
     * @param onNetworkChange Whether the NetworkMonitor-driven
     *   "WiFi/cellular/VPN flip" path should also trigger a reconnect.
     *   Independent of [autoReconnect] so users can opt into one
     *   without the other.
     */
    data class ReconnectPolicy(
        val autoReconnect: Boolean = true,
        val maxAttempts: Int = 5,
        val onNetworkChange: Boolean = true,
    )

    sealed interface AuthMethod {
        /**
         * True if a FIDO2 SK key appears anywhere in this method, including
         * inside a [Multi] chain. A profile that lists several security keys
         * is a `Multi([FidoKey, …])`, so a plain `is FidoKey` check misses it
         * and the auth-failure UX wrongly offers a password fallback even when
         * the server is publickey-only (#237).
         */
        fun containsFidoKey(): Boolean = when (this) {
            is FidoKey -> true
            is Multi -> methods.any { it.containsFidoKey() }
            else -> false
        }

        /**
         * Several auth methods presented together in one connect attempt,
         * in [methods] order, so a server requiring a multi-factor chain
         * (e.g. `AuthenticationMethods publickey,password`) can complete it
         * — JSch registers every credential and the server drives the
         * partial-success sequence. Keyboard-interactive is always
         * available via the prompter, so it needn't appear here. Nesting a
         * [Multi] inside [methods] is flattened on apply. (#166)
         */
        data class Multi(val methods: List<AuthMethod>) : AuthMethod

        /** Password auth. Use [clear] to zero the password from memory after authentication. */
        class Password(val password: CharArray) : AuthMethod {
            constructor(passwordString: String) : this(passwordString.toCharArray())
            fun clear() { password.fill('\u0000') }
        }
        /**
         * Private-key auth. The passphrase is held as a [CharArray] so it
         * can be zeroed via [clear] after the SSH session has consumed it
         * — `String` would intern/move the value out of reach. Use the
         * String secondary constructor only at boundaries you don't
         * control (UI text fields).
         */
        data class PrivateKey(
            val keyBytes: ByteArray,
            val passphrase: CharArray = CharArray(0),
            /**
             * Optional OpenSSH certificate bytes (raw `id_xxx-cert.pub`
             * content) to pair with [keyBytes] for cert-based SSH auth
             * (#133 phase 1). When non-null, [SshClient] uses
             * `OpenSshCertificateAwareIdentityFile.newInstance` so the
             * server validates against the CA signature alongside the
             * public key.
             */
            val certificateBytes: ByteArray? = null,
        ) : AuthMethod {
            constructor(keyBytes: ByteArray, passphrase: String) :
                this(keyBytes, passphrase.toCharArray())

            fun clear() { passphrase.fill('\u0000') }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is PrivateKey) return false
                return keyBytes.contentEquals(other.keyBytes) &&
                    passphrase.contentEquals(other.passphrase) &&
                    (certificateBytes?.contentEquals(other.certificateBytes ?: byteArrayOf())
                        ?: (other.certificateBytes == null))
            }
            override fun hashCode(): Int =
                keyBytes.contentHashCode() * 31 +
                    passphrase.contentHashCode() * 17 +
                    (certificateBytes?.contentHashCode() ?: 0)
        }
        /**
         * "Try every stored key" bundle used when no explicit key is
         * assigned to a profile. Each [KeyEntry] may carry an optional
         * OpenSSH certificate — without it, a key destined for a CA-only
         * server (`TrustedUserCAKeys`, no `authorized_keys` entry) only
         * offers its bare public key and the server rejects it with
         * `Auth fail for methods 'publickey'`. (#185)
         */
        data class PrivateKeys(val keys: List<KeyEntry>) : AuthMethod {
            /** A single candidate key, with its optional CA certificate. */
            data class KeyEntry(
                val label: String,
                val keyBytes: ByteArray,
                val certificateBytes: ByteArray? = null,
                /**
                 * UTF-8 SSH passphrase for a passphrase-protected [keyBytes]
                 * (null = the key is unencrypted). Lets the "try any saved key"
                 * pool include an encrypted key whose passphrase is stored
                 * (#381), not just plaintext keys. Passed as JSch's addIdentity
                 * passphrase, so the key is decrypted at auth time.
                 */
                val passphrase: ByteArray? = null,
            ) {
                override fun equals(other: Any?): Boolean {
                    if (this === other) return true
                    if (other !is KeyEntry) return false
                    return label == other.label &&
                        keyBytes.contentEquals(other.keyBytes) &&
                        (certificateBytes?.contentEquals(other.certificateBytes ?: byteArrayOf())
                            ?: (other.certificateBytes == null)) &&
                        (passphrase?.contentEquals(other.passphrase ?: byteArrayOf())
                            ?: (other.passphrase == null))
                }
                override fun hashCode(): Int =
                    label.hashCode() * 31 +
                        keyBytes.contentHashCode() * 17 +
                        (certificateBytes?.contentHashCode() ?: 0) * 13 +
                        (passphrase?.contentHashCode() ?: 0)
            }
        }
        /**
         * A key held by another app — signing delegated over the SSH
         * Authentication API, usually to OpenKeychain and through it to an
         * OpenPGP authentication subkey, often on a hardware token (#487).
         *
         * [keyData] is a serialised
         * [sh.haven.core.ssh.openkeychain.OpenKeychainKeyData]: which app to
         * ask, which key, and the public half to offer. Structurally this is
         * [FidoKey] with a different signer at the far end — neither carries
         * a private key.
         */
        data class ProviderKey(
            val keyData: ByteArray,
            /** Key name, shown in the provider's unlock prompt context. */
            val keyLabel: String? = null,
        ) : AuthMethod {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is ProviderKey) return false
                return keyData.contentEquals(other.keyData) && keyLabel == other.keyLabel
            }

            override fun hashCode(): Int = keyData.contentHashCode() * 31 + (keyLabel?.hashCode() ?: 0)
        }

        /**
         * FIDO2 SK key — signing delegated to hardware security key.
         * Optional [certBytes] (raw `id_xxx-cert.pub` content) pairs the
         * hardware-resident key with a CA-issued certificate so servers
         * with `TrustedUserCAKeys` accept the connection without the
         * sk-pubkey itself appearing in `authorized_keys`. The cert path
         * for sk-keys can't reuse JSch's `OpenSshCertificateAwareIdentityFile`
         * (that wrapper assumes a software [com.jcraft.jsch.IdentityFile]);
         * [SshClient] wraps the live [FidoIdentity] in
         * [CertificateWrappedIdentity] instead.
         */
        data class FidoKey(
            val skKeyData: ByteArray,
            val certBytes: ByteArray? = null,
            /** Profile key name, surfaced in the touch prompt so the user
             * presents the right key when several are listed (#237). */
            val keyLabel: String? = null,
            /**
             * True when this key belongs to an "Any hardware key" either-of
             * pool — [SshClient] detects the presented key and offers only it.
             * False (pinned / explicitly listed) ⇒ offer it unconditionally, so
             * a chain of several required keys can satisfy a multi-key server.
             * Not part of [equals]/[hashCode] — it's a routing flag, not identity.
             */
            val anyOf: Boolean = false,
        ) : AuthMethod {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is FidoKey) return false
                return skKeyData.contentEquals(other.skKeyData) &&
                    (certBytes?.contentEquals(other.certBytes ?: byteArrayOf())
                        ?: (other.certBytes == null))
            }
            override fun hashCode(): Int =
                skKeyData.contentHashCode() * 31 + (certBytes?.contentHashCode() ?: 0)
        }
    }

    companion object {
        /**
         * Parse ssh_config-style option lines ("Key Value" or "Key=Value")
         * into a map. Lines starting with # are comments.
         */
        fun parseSshOptions(text: String?): Map<String, String> {
            if (text.isNullOrBlank()) return emptyMap()
            return text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val sep = line.indexOfFirst { it == ' ' || it == '=' }
                    if (sep > 0) {
                        val key = line.substring(0, sep).trim()
                        val value = line.substring(sep + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
                    } else null
                }
                .toMap()
        }

        private val QUICK_CONNECT_REGEX = Regex(
            """^(?:([^@]+)@)?([^:]+)(?::(\d+))?$"""
        )

        /**
         * Parse a quick-connect string like "user@host:port", "user@host", or "host".
         * Returns null if the string doesn't match.
         */
        fun parseQuickConnect(input: String): ConnectionConfig? {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return null

            val match = QUICK_CONNECT_REGEX.matchEntire(trimmed) ?: return null
            val username = match.groupValues[1] // may be empty
            val host = match.groupValues[2]
            val port = match.groupValues[3].ifEmpty { "22" }.toIntOrNull() ?: return null

            if (host.isBlank()) return null

            return try {
                ConnectionConfig(host = host, port = port, username = username)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
