package sh.haven.core.ssh

import com.jcraft.jsch.JSchAlgoNegoFailException
import com.jcraft.jsch.JSchException

/**
 * Opt-in fallback for servers that only offer the SHA-1 `ssh-rsa` host key
 * algorithm.
 *
 * mwiede/jsch's default `server_host_key` proposal is
 * `ssh-ed25519,ecdsa-sha2-*,rsa-sha2-512,rsa-sha2-256` — `ssh-rsa` was
 * removed because its signature is SHA-1. An old server (OpenSSH ≤ 7.1, or
 * one with `HostKeyAlgorithms ssh-rsa` pinned) offers only `ssh-rsa`, so the
 * two lists share no member and JSch fails the connect with
 *
 * ```
 * Algorithm negotiation fail: algorithmName="server_host_key"
 *   jschProposal="…,rsa-sha2-512,rsa-sha2-256" serverProposal="ssh-rsa"
 * ```
 *
 * before authentication is ever attempted. Newer clients on the same network
 * (ConnectBot/Trilead, which still lists `ssh-rsa`) connect fine, which is
 * what makes this look like a Haven bug rather than a stale server.
 *
 * [retryProposal] returns the `server_host_key` value to retry with, or null
 * when this failure is not that shape. The retry is appended — modern
 * algorithms stay first, so a server that also offers SHA-2 keeps using it;
 * `ssh-rsa` only wins when it is the sole offer. See [LEGACY_DIRECTIVES] for
 * the per-connection switch (on by default).
 */
object LegacyHostKeyFallback {

    /** The host key algorithm that is re-added on retry. */
    const val LEGACY_HOST_KEY_ALGORITHM = "ssh-rsa"

    /** The algorithm name JSch reports for a host key negotiation failure. */
    private const val HOST_KEY_ALGORITHM_NAME = "server_host_key"

    /**
     * SSH Options directives that turn the fallback off (`no`) or force it on
     * (`yes`). Absent means enabled: a user who cannot connect at all is
     * better served by one extra handshake than by an opaque failure, and the
     * retry is a single bounded attempt, not a loop.
     */
    private val LEGACY_DIRECTIVES = setOf(
        "havenlegacyhostkeyfallback",
        "legacyhostkeyfallback",
    )

    private val FALSY_VALUES = setOf("no", "false", "off", "0")
    private val TRUTHY_VALUES = setOf("yes", "true", "on", "1")

    /**
     * Whether the fallback may run for this parsed SSH Options map.
     *
     * Defaults to true; only an explicit falsy directive disables it. A
     * directive that does not parse is ignored rather than guessed at, so a
     * typo leaves the default (enabled) in place.
     */
    fun isEnabled(sshOptions: Map<String, String>): Boolean {
        val value = sshOptions.entries
            .lastOrNull { it.key.trim().lowercase() in LEGACY_DIRECTIVES }
            ?.value?.trim()?.lowercase() ?: return true
        return when (value) {
            in FALSY_VALUES -> false
            in TRUTHY_VALUES -> true
            else -> true
        }
    }

    /** [isEnabled] for raw `ConnectionProfile.sshOptions` text. */
    fun isEnabledFromOptionsText(sshOptions: String?): Boolean =
        isEnabled(ConnectionConfig.parseSshOptions(sshOptions))

    /**
     * Rewrite [options] text with the fallback enabled or disabled.
     *
     * Enabling drops the directive entirely (the default is on, so writing
     * `yes` would only add noise); disabling writes the explicit `no`.
     * Mirrors `setForwardX11InOptions` and friends in SshEngine.kt.
     */
    fun setEnabledInOptions(options: String, enabled: Boolean): String {
        val kept = options.lines().filterNot { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) return@filterNot false
            val sep = trimmed.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
            if (sep <= 0) return@filterNot false
            val key = trimmed.substring(0, sep).trim().lowercase()
            key in LEGACY_DIRECTIVES
        }
        val base = kept.joinToString("\n").trim('\n')
        return when {
            enabled -> base
            base.isBlank() -> "HavenLegacyHostKeyFallback no"
            else -> "$base\nHavenLegacyHostKeyFallback no"
        }
    }

    /**
     * Whether [e] is a host key algorithm negotiation failure, i.e. the two
     * sides' `server_host_key` lists did not intersect.
     *
     * Read from the typed exception rather than the message: JSch reports
     * several different negotiation failures through the same class and only
     * [JSchAlgoNegoFailException.getAlgorithmName] distinguishes them. A KEX
     * or cipher mismatch is *not* this shape — re-offering `ssh-rsa` would not
     * fix it, and retrying would only cost another timeout.
     */
    fun isHostKeyNegotiationFailure(e: Throwable): Boolean =
        generateSequence(e) { it.cause.takeIf { c -> c !== it } }
            .take(MAX_CAUSE_DEPTH)
            .any { cause ->
                cause is JSchAlgoNegoFailException &&
                    cause.algorithmName.equals(HOST_KEY_ALGORITHM_NAME, ignoreCase = true)
            }

    /**
     * The `server_host_key` proposal to retry with, or null when a retry
     * cannot help.
     *
     * Null in three cases: the failure is not a host key mismatch, the server
     * already offers nothing this retry would add, or the session has already
     * been retried ([alreadyRetried] — the caller passes true on its second
     * attempt, so a server that rejects `ssh-rsa` too cannot cause a loop).
     *
     * [currentProposal] is the session's live `server_host_key` value, read
     * *after* user SSH Options were applied — so a user who narrowed the list
     * themselves still gets the retry, and one who already listed `ssh-rsa`
     * gets no redundant second attempt.
     */
    fun retryProposal(
        e: Throwable,
        currentProposal: String?,
        alreadyRetried: Boolean = false,
    ): String? {
        if (alreadyRetried) return null
        if (!isHostKeyNegotiationFailure(e)) return null

        val current = currentProposal.orEmpty()
        val items = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (LEGACY_HOST_KEY_ALGORITHM in items) return null

        return if (items.isEmpty()) {
            LEGACY_HOST_KEY_ALGORITHM
        } else {
            // Appended, not prepended: a server that also offers SHA-2 must
            // keep using it. The legacy algorithm is the last resort.
            "$current,$LEGACY_HOST_KEY_ALGORITHM"
        }
    }

    /**
     * A user-facing note that the connection only succeeded by falling back to
     * SHA-1, or null when no fallback happened. Callers surface it as a
     * warning so the weakened algorithm is visible rather than silent.
     */
    fun degradedWarning(host: String, port: Int): String =
        "Connected to $host:$port using the legacy ssh-rsa host key algorithm " +
            "(SHA-1). The server offered no modern alternative — consider " +
            "upgrading it or enabling rsa-sha2-256/512 on it."

    private const val MAX_CAUSE_DEPTH = 8
}
