package sh.haven.core.ssh

data class ConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.Password(""),
    val sshOptions: Map<String, String> = emptyMap(),
    /** Enable SSH agent forwarding (OpenSSH `ForwardAgent`). */
    val forwardAgent: Boolean = false,
    /**
     * Keys to expose via the forwarded agent channel. Each pair is (label, private key bytes).
     * Only consulted when [forwardAgent] is true. Encrypted keys must be excluded by the caller
     * — JSch's ChannelAgentForwarding silently skips identities whose `isEncrypted()` returns true.
     */
    val agentIdentities: List<Pair<String, ByteArray>> = emptyList(),
) {
    init {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in 1..65535) { "Port must be 1-65535, got $port" }
        require(username.isNotBlank()) { "Username must not be blank" }
    }

    sealed interface AuthMethod {
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
        ) : AuthMethod {
            constructor(keyBytes: ByteArray, passphrase: String) :
                this(keyBytes, passphrase.toCharArray())

            fun clear() { passphrase.fill('\u0000') }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is PrivateKey) return false
                return keyBytes.contentEquals(other.keyBytes) &&
                    passphrase.contentEquals(other.passphrase)
            }
            override fun hashCode(): Int =
                keyBytes.contentHashCode() * 31 + passphrase.contentHashCode()
        }
        data class PrivateKeys(val keys: List<Pair<String, ByteArray>>) : AuthMethod
        /** FIDO2 SK key — signing delegated to hardware security key. */
        data class FidoKey(val skKeyData: ByteArray) : AuthMethod {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is FidoKey) return false
                return skKeyData.contentEquals(other.skKeyData)
            }
            override fun hashCode(): Int = skKeyData.contentHashCode()
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
