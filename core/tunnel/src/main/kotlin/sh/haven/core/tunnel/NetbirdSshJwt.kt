package sh.haven.core.tunnel

import sh.haven.core.ssh.ConnectionConfig

/**
 * Per-connection opt-in for NetBird SSH JWT authentication (#875).
 *
 * A NetBird SSH server (with `--disable-ssh-auth` off) does not accept a
 * password or SSH key: it expects a JWT obtained through NetBird's OIDC
 * device flow, presented as the SSH password. When this directive is set on
 * a connection routed through a NetBird tunnel, Haven runs the device flow
 * (browser sign-in) before connecting and uses the resulting JWT as the
 * password.
 *
 * Stored in the otherwise-unused [sh.haven.core.data.db.entities.ConnectionProfile.sshOptions]
 * column — no schema bump, mirroring [sh.haven.core.ssh.LegacyHostKeyFallback].
 * Off by default: only NetBird SSH servers need it.
 */
object NetbirdSshJwt {

    private val DIRECTIVES = setOf(
        "havennetbirdsshjwt",
        "netbirdsshjwt",
    )

    private val FALSY_VALUES = setOf("no", "false", "off", "0")
    private val TRUTHY_VALUES = setOf("yes", "true", "on", "1")

    /** Whether NetBird SSH JWT auth is requested for this parsed SSH Options map. */
    fun isEnabled(sshOptions: Map<String, String>): Boolean {
        val value = sshOptions.entries
            .lastOrNull { it.key.trim().lowercase() in DIRECTIVES }
            ?.value?.trim()?.lowercase() ?: return false
        return value in TRUTHY_VALUES && value !in FALSY_VALUES
    }

    /** [isEnabled] for raw `ConnectionProfile.sshOptions` text. */
    fun isEnabledFromOptionsText(sshOptions: String?): Boolean =
        isEnabled(ConnectionConfig.parseSshOptions(sshOptions))

    /**
     * Rewrite [options] text with NetBird SSH JWT auth enabled or disabled.
     * Disabling drops the directive (the default is off); enabling writes
     * the explicit `yes`. Mirrors `LegacyHostKeyFallback.setEnabledInOptions`.
     */
    fun setEnabledInOptions(options: String, enabled: Boolean): String {
        val kept = options.lines().filterNot { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) return@filterNot false
            val sep = trimmed.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
            if (sep <= 0) return@filterNot false
            val key = trimmed.substring(0, sep).trim().lowercase()
            key in DIRECTIVES
        }
        val base = kept.joinToString("\n").trim('\n')
        return when {
            !enabled -> base
            base.isBlank() -> "HavenNetbirdSshJwt yes"
            else -> "$base\nHavenNetbirdSshJwt yes"
        }
    }
}