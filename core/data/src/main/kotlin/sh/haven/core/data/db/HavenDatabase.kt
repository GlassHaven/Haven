package sh.haven.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import sh.haven.core.data.db.entities.AgentAuditEvent
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.db.entities.PasteQueueEntry
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.db.entities.WorkspaceProfile

@Database(
    entities = [
        ConnectionProfile::class,
        ConnectionGroup::class,
        KnownHost::class,
        ConnectionLog::class,
        SshKey::class,
        PortForwardRule::class,
        AgentAuditEvent::class,
        TunnelConfig::class,
        PasteQueueEntry::class,
        WorkspaceProfile::class,
        WorkspaceItem::class,
    ],
    version = 46,
    exportSchema = true,
)
abstract class HavenDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun connectionGroupDao(): ConnectionGroupDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun connectionLogDao(): ConnectionLogDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun portForwardRuleDao(): PortForwardRuleDao
    abstract fun agentAuditEventDao(): AgentAuditEventDao
    abstract fun tunnelConfigDao(): TunnelConfigDao
    abstract fun pasteQueueDao(): PasteQueueDao
    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ssh_keys` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `keyType` TEXT NOT NULL,
                        `privateKeyBytes` BLOB NOT NULL,
                        `publicKeyOpenSsh` TEXT NOT NULL,
                        `fingerprintSha256` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN connectionType TEXT NOT NULL DEFAULT 'SSH'")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN destinationHash TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN reticulumHost TEXT NOT NULL DEFAULT '127.0.0.1'")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN reticulumPort INTEGER NOT NULL DEFAULT 37428")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `port_forward_rules` (
                        `id` TEXT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `bindAddress` TEXT NOT NULL,
                        `bindPort` INTEGER NOT NULL,
                        `targetHost` TEXT NOT NULL,
                        `targetPort` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`profileId`) REFERENCES `connection_profiles`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_port_forward_rules_profileId` ON `port_forward_rules` (`profileId`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN jumpProfileId TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN sshOptions TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN vncPort INTEGER")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN vncPassword TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN vncSshForward INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN sessionManager TEXT")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN useMosh INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN useEternalTerminal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN etPort INTEGER NOT NULL DEFAULT 2022")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpPort INTEGER NOT NULL DEFAULT 3389")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpUsername TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpDomain TEXT")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpSshForward INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpSshProfileId TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rdpPassword TEXT")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbPort INTEGER NOT NULL DEFAULT 445")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbShare TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbDomain TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbPassword TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbSshForward INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN smbSshProfileId TEXT")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN sshPassword TEXT")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_logs ADD COLUMN details TEXT")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_logs ADD COLUMN verboseLog TEXT")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN proxyType TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN proxyHost TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN proxyPort INTEGER NOT NULL DEFAULT 1080")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `connection_groups` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `colorTag` INTEGER NOT NULL DEFAULT 0,
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        `collapsed` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN groupId TEXT")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN lastSessionName TEXT")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN disableAltScreen INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ssh_keys ADD COLUMN isEncrypted INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rcloneRemoteName TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN rcloneProvider TEXT")
            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN useAndroidShell INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN moshServerCommand TEXT")
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN forwardAgent INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN reticulumNetworkName TEXT")
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN reticulumPassphrase TEXT")
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN postLoginCommand TEXT")
            }
        }

        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN postLoginBeforeSessionManager INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN vncUsername TEXT")
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_audit_events` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `timestamp` INTEGER NOT NULL,
                        `clientHint` TEXT,
                        `method` TEXT NOT NULL,
                        `toolName` TEXT,
                        `argsJson` TEXT,
                        `resultSummary` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `errorMessage` TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_audit_events_timestamp` ON `agent_audit_events` (`timestamp`)")
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN fileTransport TEXT NOT NULL DEFAULT 'AUTO'")
            }
        }

        /**
         * Per-app WireGuard (#102): add the `tunnel_configs` table for
         * named tunnel configurations the user manages in settings, and
         * an optional `tunnelConfigId` reference on each connection
         * profile. Existing profiles get NULL and behave as before.
         */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tunnel_configs` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `configText` BLOB NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE connection_profiles ADD COLUMN tunnelConfigId TEXT")
            }
        }

        /**
         * Persistent paste queue so a long-running copy/paste survives app
         * backgrounding, process death, reboots, and transient connection
         * drops. Each row is a leaf file; [status] flips from PENDING to
         * DONE as each transfer completes, and [bytesTransferred] lets
         * the resume path pick up mid-file via ChannelSftp.RESUME.
         */
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `paste_queue_entries` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `indexInBatch` INTEGER NOT NULL,
                        `sourceBackendType` TEXT NOT NULL,
                        `sourceProfileId` TEXT NOT NULL,
                        `sourceRemoteName` TEXT,
                        `sourcePath` TEXT NOT NULL,
                        `sourceName` TEXT NOT NULL,
                        `sourceSize` INTEGER NOT NULL,
                        `sourceIsDirectory` INTEGER NOT NULL DEFAULT 0,
                        `destBackendType` TEXT NOT NULL,
                        `destProfileId` TEXT NOT NULL,
                        `destRemote` TEXT,
                        `destPath` TEXT NOT NULL,
                        `isCut` INTEGER NOT NULL DEFAULT 0,
                        `conflictAction` TEXT NOT NULL DEFAULT 'OVERWRITE',
                        `bytesTransferred` INTEGER NOT NULL DEFAULT 0,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_paste_queue_entries_status` " +
                        "ON `paste_queue_entries` (`status`)",
                )
            }
        }

        /**
         * Bring VNC saved-on-profile fields in line with RDP/SMB: a VNC
         * connection can now opt into SSH tunneling via a paired SSH
         * profile, same as RDP's rdpSshProfileId. Default null = no tunnel.
         */
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN vncSshProfileId TEXT DEFAULT NULL"
                )
            }
        }

        /**
         * Per-profile VNC pixel format (24-bit / 16-bit / 8-bit). Defaults
         * to existing behaviour. Added so users on slow mobile paths can
         * downshift to 256 colours and have a usable session, mirroring
         * RealVNC's behaviour (Nesos-ita on #107).
         */
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN vncColorDepth TEXT NOT NULL DEFAULT 'BPP_24_TRUE'"
                )
            }
        }

        /**
         * Per-profile NLA / CredSSP toggle for RDP, default on. Allows
         * users to fall back to SSL-only security on servers where
         * ironrdp's CredSSP implementation doesn't interoperate
         * (#109 — Windows Server 2025 Datacenter).
         */
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN rdpUseNla INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * Per-profile RDP colour depth, default 16bpp. 16 was the
         * only value verified to work against every server type
         * pre-EGFX; 24 fails on Windows (server resets connection)
         * and 32 was thought to fail on xrdp (custom RLE). #109.
         *
         * The "default 16" assumption is invalidated by v5.24.69's
         * EGFX patch — see MIGRATION_40_41.
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN rdpColorDepth INTEGER NOT NULL DEFAULT 16"
                )
            }
        }

        /**
         * EGFX (v5.24.69) sets the `SUPPORT_DYN_VC_GFX_PROTOCOL`
         * early-capability flag, which makes Windows servers
         * stricter about the legacy `color_depth` field in the GCC
         * core data: `Bpp8` (= 16-bit) now causes the server to
         * TCP-FIN after MCS Connect, mid-handshake, with no useful
         * error. Bumping to 32 fixes Windows but is risky for xrdp
         * users (whose pre-EGFX matrix had 32 marked as broken).
         *
         * NLA-on strongly correlates with Windows-class servers, so
         * this migration only auto-bumps profiles with
         * `rdpUseNla = 1`. Profiles with NLA off (typically xrdp
         * targets) keep 16. Users who hit the new failure mode and
         * have NLA off can flip the depth manually in the dialog.
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE connection_profiles SET rdpColorDepth = 32 WHERE rdpColorDepth = 16 AND rdpUseNla = 1"
                )
            }
        }

        /**
         * Per-key biometric protection (#129 stage 5). Adds the
         * `biometricProtected` flag on each SSH key; when true, the
         * unified Keystore.fetch path requires a successful
         * BiometricPrompt before returning the key material. Default
         * 0 — every existing key behaves as before until the user
         * flips the toggle in Settings → Security audit.
         */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ssh_keys ADD COLUMN biometricProtected INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * SSH certificate auth (#133 phase 1). Adds an optional
         * `certificateBytes` BLOB column on each SSH key, holding the
         * contents of an `id_xxx-cert.pub` file signed by a CA the
         * remote server trusts. SshClient wraps private + cert via
         * OpenSshCertificateAwareIdentityFile when this is non-null.
         * Default null — existing keys behave as plain key auth.
         */
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ssh_keys ADD COLUMN certificateBytes BLOB DEFAULT NULL"
                )
            }
        }

        /**
         * Per-connection IPv4-only toggle (#137). On networks where
         * AAAA records resolve but the IPv6 path doesn't route, the
         * user can flip this to skip IPv6 entirely. Default 0 — every
         * existing profile keeps the dual-stack behaviour.
         */
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connection_profiles ADD COLUMN forceIpv4 INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Generalise the `forceIpv4` Boolean to a tri-state
         * `addressFamily` selector — `AUTO`, `IPV4_ONLY`, `IPV6_ONLY`
         * (#137 follow-up). Networks with broken IPv4 paths exist too,
         * so a Boolean undersells the failure mode (pannal).
         *
         * SQLite ≥ 3.35 supports `DROP COLUMN`, but Haven's minSdk 26
         * ships SQLite 3.18, which doesn't — so do the table-recreate
         * dance: copy through a `_new` table, dropping `forceIpv4` and
         * carrying its value into the new `addressFamily` column.
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE `connection_profiles_new` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `host` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `username` TEXT NOT NULL,
                        `sshPassword` TEXT,
                        `authType` TEXT NOT NULL,
                        `keyId` TEXT,
                        `colorTag` INTEGER NOT NULL,
                        `lastConnected` INTEGER,
                        `sortOrder` INTEGER NOT NULL,
                        `connectionType` TEXT NOT NULL,
                        `destinationHash` TEXT,
                        `reticulumHost` TEXT NOT NULL,
                        `reticulumPort` INTEGER NOT NULL,
                        `jumpProfileId` TEXT,
                        `sshOptions` TEXT,
                        `vncPort` INTEGER,
                        `vncUsername` TEXT,
                        `vncPassword` TEXT,
                        `vncSshForward` INTEGER NOT NULL,
                        `vncSshProfileId` TEXT,
                        `vncColorDepth` TEXT NOT NULL,
                        `sessionManager` TEXT,
                        `useMosh` INTEGER NOT NULL,
                        `useEternalTerminal` INTEGER NOT NULL,
                        `etPort` INTEGER NOT NULL,
                        `rdpPort` INTEGER NOT NULL,
                        `rdpUsername` TEXT,
                        `rdpDomain` TEXT,
                        `rdpPassword` TEXT,
                        `rdpSshForward` INTEGER NOT NULL,
                        `rdpSshProfileId` TEXT,
                        `rdpUseNla` INTEGER NOT NULL,
                        `rdpColorDepth` INTEGER NOT NULL,
                        `smbPort` INTEGER NOT NULL,
                        `smbShare` TEXT,
                        `smbDomain` TEXT,
                        `smbPassword` TEXT,
                        `smbSshForward` INTEGER NOT NULL,
                        `smbSshProfileId` TEXT,
                        `proxyType` TEXT,
                        `proxyHost` TEXT,
                        `proxyPort` INTEGER NOT NULL,
                        `groupId` TEXT,
                        `lastSessionName` TEXT,
                        `disableAltScreen` INTEGER NOT NULL,
                        `rcloneRemoteName` TEXT,
                        `rcloneProvider` TEXT,
                        `useAndroidShell` INTEGER NOT NULL,
                        `moshServerCommand` TEXT,
                        `forwardAgent` INTEGER NOT NULL,
                        `addressFamily` TEXT NOT NULL DEFAULT 'AUTO',
                        `reticulumNetworkName` TEXT,
                        `reticulumPassphrase` TEXT,
                        `postLoginCommand` TEXT,
                        `postLoginBeforeSessionManager` INTEGER NOT NULL,
                        `fileTransport` TEXT NOT NULL,
                        `tunnelConfigId` TEXT,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO connection_profiles_new
                    SELECT
                        id, label, host, port, username, sshPassword, authType,
                        keyId, colorTag, lastConnected, sortOrder, connectionType,
                        destinationHash, reticulumHost, reticulumPort, jumpProfileId,
                        sshOptions, vncPort, vncUsername, vncPassword, vncSshForward,
                        vncSshProfileId, vncColorDepth, sessionManager, useMosh,
                        useEternalTerminal, etPort, rdpPort, rdpUsername, rdpDomain,
                        rdpPassword, rdpSshForward, rdpSshProfileId, rdpUseNla,
                        rdpColorDepth, smbPort, smbShare, smbDomain, smbPassword,
                        smbSshForward, smbSshProfileId, proxyType, proxyHost,
                        proxyPort, groupId, lastSessionName, disableAltScreen,
                        rcloneRemoteName, rcloneProvider, useAndroidShell,
                        moshServerCommand, forwardAgent,
                        CASE WHEN forceIpv4 = 1 THEN 'IPV4_ONLY' ELSE 'AUTO' END,
                        reticulumNetworkName, reticulumPassphrase, postLoginCommand,
                        postLoginBeforeSessionManager, fileTransport, tunnelConfigId
                    FROM connection_profiles
                """.trimIndent())
                db.execSQL("DROP TABLE connection_profiles")
                db.execSQL("ALTER TABLE connection_profiles_new RENAME TO connection_profiles")
            }
        }

        /**
         * Workspace profiles: a named, ordered composition of launchable
         * items (terminal sessions, file-browser tabs, remote desktops,
         * Wayland) the user can fire in one tap. `workspace_item.kind`
         * is a TEXT enum; `connectionProfileId` is `SET NULL` on profile
         * delete so the workspace stays intact and the launcher reports
         * the broken item rather than silently dropping it.
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_profile` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspace_item` (
                        `id` TEXT NOT NULL,
                        `workspaceId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `connectionProfileId` TEXT,
                        `path` TEXT,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`workspaceId`) REFERENCES `workspace_profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`connectionProfileId`) REFERENCES `connection_profiles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workspace_item_workspaceId` " +
                        "ON `workspace_item` (`workspaceId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workspace_item_connectionProfileId` " +
                        "ON `workspace_item` (`connectionProfileId`)",
                )
            }
        }
    }
}
