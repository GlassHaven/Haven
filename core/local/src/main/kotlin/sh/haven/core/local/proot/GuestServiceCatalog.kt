package sh.haven.core.local.proot

import java.io.File

/**
 * Curated one-tap background service presets for the active PRoot Linux distro (#734).
 *
 * Mirrors [GuestAppCatalog]'s pure-data pattern, but targets long-lived loopback
 * daemons supervised by [sh.haven.core.local.GuestServiceManager] instead of
 * graphical Wayland/X11 windows.
 */
data class GuestServicePreset(
    val id: String,
    val label: String,
    val description: String,
    val port: Int,
    val packages: Map<PackageFamily, List<String>>,
    /** Rootfs-relative paths; install succeeds if at least one exists afterward. */
    val verifyBinaries: List<String>,
    val configWrites: List<PackConfigWrite> = emptyList(),
    /** Optional idempotent shell block executed after packages and config drops. */
    val postInstallScript: String = "",
    /** Foreground shell command supervised by `GuestServiceManager`. */
    val serviceCommand: String,
    val autostart: Boolean = false,
) {
    fun isBinaryPresent(rootfsDir: File): Boolean =
        verifyBinaries.any { rel -> File(rootfsDir, rel).exists() }
}

private fun shqService(s: String) = "'" + s.replace("'", "'\\''") + "'"

/**
 * Build the `set -e` install + first-time initialization script for [preset].
 */
fun buildServicePresetInstallScript(
    preset: GuestServicePreset,
    pkgs: List<String>,
    family: PackageFamily,
): String {
    val ops = PackageOps.forFamily(family)
    val sb = StringBuilder("set -e\n")
    sb.append("export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH\"\n")
    sb.append(ops.updateCmd()).append('\n')
    sb.append(ops.installCmd(pkgs)).append('\n')
    for (w in preset.configWrites) {
        val guard = w.stanza.lineSequence().first()
        sb.append("mkdir -p ${shqService(w.path.substringBeforeLast('/'))}\n")
        sb.append(
            "grep -qxF ${shqService(guard)} ${shqService(w.path)} 2>/dev/null || " +
                "printf '%s\\n' ${shqService(w.stanza)} >> ${shqService(w.path)}\n",
        )
    }
    if (preset.postInstallScript.isNotBlank()) {
        sb.append(preset.postInstallScript.trim()).append('\n')
    }
    return sb.toString()
}

object GuestServiceCatalog {

    /**
     * Local MySQL-compatible SQL server (#734).
     *
     * PRoot specifics verified against Alpine 3.21 (`11.4`) and Ubuntu 24.04 (`10.11`):
     * - `--no-defaults` isolates Haven's instance from distro `/etc/mysql` configs
     *   that set `skip-networking`, `user=mysql`, or `/run/mysqld` paths.
     * - `--user=root` avoids `setuid(mysql)` ownership mismatches under PRoot `-0`.
     * - `--auth-root-authentication-method=normal` allows TCP `127.0.0.1:3306` root
     *   connections from both guest CLI (`mariadb`) and apps without `unix_socket`
     *   plugin rejections (`ERROR 1698`).
     * - `--innodb-use-native-aio=0` avoids Android kernel/seccomp `io_uring` /
     *   `libaio` failures inside PRoot.
     */
    val MARIADB = GuestServicePreset(
        id = "mariadb",
        label = "MariaDB",
        description = "MySQL-compatible SQL server on 127.0.0.1:3306 (mariadb / mysql client included).",
        port = 3306,
        packages = mapOf(
            PackageFamily.APK to listOf("mariadb", "mariadb-client"),
            PackageFamily.APT to listOf("mariadb-server", "mariadb-client"),
            PackageFamily.PACMAN to listOf("mariadb"),
            PackageFamily.XBPS to listOf("mariadb", "mariadb-client"),
        ),
        verifyBinaries = listOf(
            "usr/bin/mariadbd",
            "usr/sbin/mariadbd",
            "usr/bin/mysqld",
            "usr/sbin/mysqld",
        ),
        configWrites = listOf(
            PackConfigWrite(
                path = "/root/.my.cnf",
                stanza = "[client]\nhost=127.0.0.1\nport=3306\nuser=root",
            ),
        ),
        postInstallScript = """
            mkdir -p /var/lib/haven-mariadb /tmp/haven-mariadb
            if [ ! -d /var/lib/haven-mariadb/mysql ]; then
              INSTALL_DB=${'$'}(command -v mariadb-install-db || command -v mysql_install_db)
              "${'$'}INSTALL_DB" --no-defaults --user=root --datadir=/var/lib/haven-mariadb --auth-root-authentication-method=normal \
                || "${'$'}INSTALL_DB" --no-defaults --user=root --datadir=/var/lib/haven-mariadb
            fi
        """.trimIndent(),
        serviceCommand = """
            export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${'$'}PATH"
            mkdir -p /var/lib/haven-mariadb /tmp/haven-mariadb
            DAEMON=${'$'}(command -v mariadbd || command -v mysqld)
            exec "${'$'}DAEMON" --no-defaults --user=root --datadir=/var/lib/haven-mariadb --socket=/tmp/haven-mariadb/mysqld.sock --pid-file=/tmp/haven-mariadb/mysqld.pid --bind-address=127.0.0.1 --port=3306 --skip-networking=0 --innodb-use-native-aio=0
        """.trimIndent(),
    )

    val PRESETS: List<GuestServicePreset> = listOf(MARIADB)

    fun byId(id: String): GuestServicePreset? = PRESETS.find { it.id == id }
}
